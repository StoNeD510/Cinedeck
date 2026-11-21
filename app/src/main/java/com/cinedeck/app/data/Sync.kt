package com.cinedeck.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Keeps watch history, My List and addons the same on every device, using one JSON file in a PRIVATE GitHub
 * repository that belongs to the user. The app refuses to sync to a repository that isn't private.
 *
 * Each device pulls the file, merges it with what it has (newest change per item wins, removals are remembered),
 * and pushes the result back. Settings, the Real-Debrid key and the GitHub token itself never leave the device.
 */
object Sync {
    const val REPO_NAME = "reeldj-sync"
    private const val FILE = "sync.json"
    private const val API = "https://api.github.com"
    private const val TOMBSTONE_TTL = 180L * 24 * 3600 * 1000

    data class Status(val state: String = "off", val message: String = "Not set up", val lastOk: Long = 0) // off | idle | syncing | error

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> get() = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null
    private var firstDirtyAt = 0L
    @Volatile private var lastRun = 0L

    fun init() {
        Store.onLocalChange = { schedule() }
        if (enabled) now("start") else _status.value = Status()
    }

    val enabled get() = Store.settings.syncToken.isNotBlank()

    /** A local change happened: push once things have been quiet for 45 s (at most 15 min after the first change). */
    private fun schedule() {
        if (!enabled) return
        val t = System.currentTimeMillis()
        if (firstDirtyAt == 0L) firstDirtyAt = t
        val wait = if (t - firstDirtyAt > 15 * 60_000) 0L else 45_000L
        pending?.cancel()
        pending = scope.launch { delay(wait); run("change") }
    }

    /** App came to the front: pick up what other devices did, unless we synced within the last minute. */
    fun onForeground() { if (enabled && System.currentTimeMillis() - lastRun > 60_000) now("foreground") }

    /** App is leaving the screen: push anything still waiting. */
    fun onBackground() { if (enabled && firstDirtyAt != 0L) now("background") }

    fun now(reason: String = "manual") { pending?.cancel(); pending = scope.launch { run(reason) } }

    /** Saves a token typed on the TV or sent from the phone page, then syncs. */
    fun setToken(token: String) {
        Store.updateSettings { it.copy(syncToken = token.trim(), syncRepo = "") }
        if (token.isBlank()) { pending?.cancel(); _status.value = Status() } else now("token")
    }

    private suspend fun run(reason: String) = lock.withLock {
        val token = Store.settings.syncToken
        if (token.isBlank()) return@withLock
        _status.value = _status.value.copy(state = "syncing", message = "Syncing…")
        try {
            val repo = resolveRepo(token)
            var tries = 0
            while (true) {
                val (remote, sha) = fetch(token, repo)
                val local = Store.data.value
                val merged = merge(local.toDoc(), remote ?: SyncDoc(), System.currentTimeMillis())
                apply(merged)
                val upToDate = remote != null && merged == remote
                if (upToDate || push(token, repo, merged, sha)) break
                if (++tries >= 4) throw IOException("Another device kept changing the file; will retry")
            }
            firstDirtyAt = 0L
            lastRun = System.currentTimeMillis()
            _status.value = Status("idle", "Synced", lastRun)
            com.cinedeck.app.player.PlayerLog.add("Sync ($reason): ok")
        } catch (e: Exception) {
            lastRun = System.currentTimeMillis()
            _status.value = _status.value.copy(state = "error", message = e.message ?: "Sync failed")
            com.cinedeck.app.player.PlayerLog.add("Sync ($reason) failed: ${e.message}")
        }
    }

    // ---------- merge ----------

    private fun AppData.toDoc() = SyncDoc(
        progress = progress, library = library, addons = addons.map { it.url }, addonTimes = addonTimes, deleted = deleted,
    )

    /** Pure merge of two copies: newest change per item wins; an item removed after its last change stays removed. */
    fun merge(a: SyncDoc, b: SyncDoc, now: Long): SyncDoc {
        val deleted = HashMap<String, Long>()
        (a.deleted.entries + b.deleted.entries).forEach { (k, t) -> if (t > (deleted[k] ?: 0)) deleted[k] = t }

        val progress = HashMap<String, Progress>()
        (a.progress.values + b.progress.values).forEach { p ->
            val cur = progress[p.videoId]
            if (cur == null || p.updatedAt > cur.updatedAt) progress[p.videoId] = p
        }
        progress.entries.removeAll { (k, p) -> (deleted["p:$k"] ?: 0) >= p.updatedAt }

        val library = LinkedHashMap<String, LibraryItem>()
        (a.library + b.library).forEach { l ->
            val cur = library[l.id]
            if (cur == null || l.addedAt > cur.addedAt) library[l.id] = l
        }
        library.entries.removeAll { (k, l) -> (deleted["l:$k"] ?: 0) >= l.addedAt }

        val addonTimes = HashMap<String, Long>()
        (a.addonTimes.entries + b.addonTimes.entries).forEach { (k, t) -> if (t > (addonTimes[k] ?: 0)) addonTimes[k] = t }
        // The cloud copy's order comes first so every device settles on the same list order.
        val addons = (b.addons + a.addons).distinct().filter { url ->
            val removedAt = deleted["a:$url"] ?: return@filter true
            (addonTimes[url] ?: 0) > removedAt
        }

        // A tombstone is only needed while it could still beat a copy of the item on some device.
        deleted.entries.removeAll { (k, t) ->
            now - t > TOMBSTONE_TTL ||
                (k.startsWith("p:") && progress.containsKey(k.substring(2))) ||
                (k.startsWith("l:") && library.containsKey(k.substring(2))) ||
                (k.startsWith("a:") && k.substring(2) in addons)
        }
        return SyncDoc(
            progress = progress.toSortedMap(), library = library.values.sortedByDescending { it.addedAt }, addons = addons,
            addonTimes = addonTimes.filterKeys { it in addons }.toSortedMap(), deleted = deleted.toSortedMap(),
        )
    }

    private suspend fun apply(merged: SyncDoc) {
        var newAddons = false
        Store.applySync { d ->
            // Merge once more against the live data: the player may have saved progress while we were talking to GitHub.
            val m = merge(d.toDoc(), merged, System.currentTimeMillis())
            val known = d.addons.associateBy { it.url }
            newAddons = m.addons.any { it !in known }
            d.copy(
                progress = m.progress, library = m.library, deleted = m.deleted, addonTimes = m.addonTimes,
                addons = m.addons.map { known[it] ?: AddonEntry(it) }.ifEmpty { DefaultAddons },
            )
        }
        if (newAddons) runCatching { Addons.refreshManifests() }
    }

    // ---------- GitHub ----------

    private fun req(token: String, url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ReelDJ-sync")
        .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)

    private fun call(r: Request): Pair<Int, String> = Http.client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }

    private fun obj(text: String): JsonObject = AppJson.parseToJsonElement(text).jsonObject

    /** Finds the user's sync repository, creates it (private) if the token is allowed to, and insists that it is private. */
    private fun resolveRepo(token: String): String {
        val saved = Store.settings.syncRepo
        val repo = saved.ifBlank {
            val (code, body) = call(req(token, "$API/user").build())
            if (code == 401) throw IOException("GitHub rejected the token (expired or mistyped)")
            if (code != 200) throw IOException("GitHub said $code when checking the token")
            obj(body)["login"]!!.jsonPrimitive.content + "/" + REPO_NAME
        }
        var (code, body) = call(req(token, "$API/repos/$repo").build())
        if (code == 404 || code == 403) {
            val make = buildJsonObject { put("name", REPO_NAME); put("private", true); put("auto_init", true); put("description", "ReelDJ sync data") }
            val (c2, b2) = call(req(token, "$API/user/repos").post(make.toString().toRequestBody("application/json".toMediaType())).build())
            if (c2 != 201) throw IOException("Can't reach the private repository \"$REPO_NAME\". Create it on GitHub (Private) and give the token access to it.")
            code = 200; body = b2
        }
        if (code == 401) throw IOException("GitHub rejected the token (expired or mistyped)")
        if (code != 200) throw IOException("GitHub said $code for $repo")
        if (obj(body)["private"]?.jsonPrimitive?.content != "true") throw IOException("$repo is not private — sync refused. Make the repository private on GitHub.")
        if (saved != repo) Store.updateSettings { it.copy(syncRepo = repo) }
        return repo
    }

    /** Returns the remote document (null if the file doesn't exist yet) and the file's version id. */
    private fun fetch(token: String, repo: String): Pair<SyncDoc?, String?> {
        val (code, body) = call(req(token, "$API/repos/$repo/contents/$FILE").build())
        if (code == 404) return null to null
        if (code != 200) throw IOException("GitHub said $code when reading the sync file")
        val o = obj(body)
        val sha = o["sha"]!!.jsonPrimitive.content
        val inline = o["content"]?.jsonPrimitive?.content.orEmpty()
        val text = if (inline.isNotBlank()) String(android.util.Base64.decode(inline, android.util.Base64.DEFAULT))
        else { // files over 1 MB come back without content: ask for the raw bytes
            val (c2, raw) = call(req(token, "$API/repos/$repo/contents/$FILE").header("Accept", "application/vnd.github.raw+json").build())
            if (c2 != 200) throw IOException("GitHub said $c2 when reading the sync file")
            raw
        }
        val doc = runCatching { AppJson.decodeFromString(SyncDoc.serializer(), text) }.getOrElse { throw IOException("The sync file is damaged; delete $FILE in $repo to start fresh") }
        return doc to sha
    }

    /** Writes the document; false means someone else wrote first and we should merge again. */
    private fun push(token: String, repo: String, doc: SyncDoc, sha: String?): Boolean {
        val text = AppJson.encodeToString(SyncDoc.serializer(), doc)
        val payload = buildJsonObject {
            put("message", "sync from ${android.os.Build.MODEL}")
            put("content", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP))
            if (sha != null) put("sha", sha)
        }
        val (code, _) = call(req(token, "$API/repos/$repo/contents/$FILE").put(payload.toString().toRequestBody("application/json".toMediaType())).build())
        if (code == 200 || code == 201) return true
        if (code == 409 || code == 422) return false
        if (code == 403 || code == 404) throw IOException("The token can't write to $repo (needs Contents: Read and write)")
        throw IOException("GitHub said $code when saving the sync file")
    }
}
