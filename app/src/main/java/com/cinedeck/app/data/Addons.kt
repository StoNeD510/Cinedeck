package com.cinedeck.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Http {
    @Volatile var client: OkHttpClient = build(null)
        private set

    /** Call once at startup: adds a disk cache so catalogs, show details and streams reload instantly. */
    fun init(cacheDir: java.io.File) { client = build(cacheDir) }

    private fun build(cacheDir: java.io.File?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .apply { if (cacheDir != null) cache(okhttp3.Cache(java.io.File(cacheDir, "http"), 100L * 1024 * 1024)) }
        .build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", "ReelDJ/0.3")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.string() ?: ""
        }
    }
}

/** Client for the Stremio addon protocol. Works with any addon URL you could add in Stremio. */
object Addons {
    private val metaCache = ConcurrentHashMap<String, Meta>()

    fun baseOf(url: String): String = url.trim()
        .replace(Regex("^stremio://"), "https://")
        .removeSuffix("/")
        .removeSuffix("/manifest.json")

    fun normalize(url: String) = baseOf(url) + "/manifest.json"

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    suspend fun manifest(url: String): Manifest =
        AppJson.decodeFromString(Manifest.serializer(), Http.get(normalize(url)))

    /** Fetch manifests for any addon that doesn't have one cached yet (or all, when forced). */
    suspend fun refreshManifests(force: Boolean = false) = coroutineScope {
        val current = Store.data.value.addons
        val updated = current.map { a ->
            async {
                if (a.manifest != null && !force) a
                else runCatching { a.copy(manifest = manifest(a.url)) }.getOrElse { a }
            }
        }.awaitAll()
        Store.setAddons(updated)
    }

    private fun active() = Store.data.value.addons.filter { it.manifest != null }

    data class CatalogRef(val addon: AddonEntry, val def: CatalogDef) {
        val key get() = "${addon.manifest?.id}/${def.type}/${def.id}"
        val title: String
            get() {
                val t = when (def.type) { "movie" -> "Movies"; "series" -> "TV Shows"; else -> def.type.replaceFirstChar { it.uppercase() } }
                return "${def.name ?: def.id} $t"
            }
    }

    fun catalogs(): List<CatalogRef> = active().flatMap { a -> a.manifest!!.catalogs.map { CatalogRef(a, it) } }

    fun homeCatalogs() = catalogs().filter { !it.def.requiresExtra() }

    fun searchCatalogs() = catalogs().filter { it.def.supportsExtra("search") }

    suspend fun catalog(ref: CatalogRef, extra: Map<String, String> = emptyMap()): List<Meta> {
        val base = baseOf(ref.addon.url)
        val path = if (extra.isEmpty()) "/catalog/${ref.def.type}/${ref.def.id}.json"
        else "/catalog/${ref.def.type}/${ref.def.id}/" + extra.entries.joinToString("&") { "${it.key}=${enc(it.value)}" } + ".json"
        val res = AppJson.decodeFromString(CatalogResponse.serializer(), Http.get(base + path))
        return res.metas.map { it.copy(type = it.type.ifBlank { ref.def.type }) }
    }

    suspend fun meta(type: String, id: String): Meta? {
        metaCache["$type/$id"]?.let { return it }
        for (a in active().filter { it.manifest!!.supports("meta", type, id) }) {
            val m = runCatching {
                AppJson.decodeFromString(MetaResponse.serializer(), Http.get("${baseOf(a.url)}/meta/$type/${enc(id)}.json")).meta
            }.getOrNull()
            if (m != null) {
                metaCache["$type/$id"] = m
                return m
            }
        }
        return null
    }

    fun cachedMeta(type: String, id: String) = metaCache["$type/$id"]

    data class AddonStreams(val addon: AddonEntry, val streams: List<Stream>, val error: String? = null)

    suspend fun streams(type: String, id: String): List<AddonStreams> = coroutineScope {
        active().filter { it.manifest!!.supports("stream", type, id) }.map { a ->
            async {
                try {
                    val r = withTimeoutOrNull(45_000) {
                        AppJson.decodeFromString(StreamResponse.serializer(), Http.get("${baseOf(a.url)}/stream/$type/${enc(id)}.json"))
                    }
                    if (r == null) AddonStreams(a, emptyList(), "Timed out")
                    else AddonStreams(a, r.streams)
                } catch (e: Exception) {
                    AddonStreams(a, emptyList(), e.message ?: "Failed")
                }
            }
        }.awaitAll()
    }

    /** Like [streams], but each addon's answer is delivered as soon as it arrives. */
    fun streamsFlow(type: String, id: String): kotlinx.coroutines.flow.Flow<AddonStreams> = kotlinx.coroutines.flow.channelFlow {
        active().filter { it.manifest!!.supports("stream", type, id) }.forEach { a ->
            launch {
                val r = try {
                    withTimeoutOrNull(45_000) {
                        AppJson.decodeFromString(StreamResponse.serializer(), Http.get("${baseOf(a.url)}/stream/$type/${enc(id)}.json"))
                    }?.let { AddonStreams(a, it.streams) } ?: AddonStreams(a, emptyList(), "Timed out")
                } catch (e: Exception) {
                    AddonStreams(a, emptyList(), e.message ?: "Failed")
                }
                send(r)
            }
        }
    }

    suspend fun subtitles(type: String, id: String): List<Subtitle> = coroutineScope {
        active().filter { it.manifest!!.supports("subtitles", type, id) }.map { a ->
            async {
                runCatching {
                    withTimeoutOrNull(8_000) {
                        AppJson.decodeFromString(SubtitleResponse.serializer(), Http.get("${baseOf(a.url)}/subtitles/$type/${enc(id)}.json")).subtitles
                    } ?: emptyList()
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /** Fetch metas for many ids at once, a few at a time. */
    suspend fun metas(items: List<Pair<String, String>>): List<Meta> = coroutineScope {
        val gate = Semaphore(5)
        items.map { (type, id) -> async { gate.withPermit { runCatching { meta(type, id) }.getOrNull() } } }
            .awaitAll().filterNotNull()
    }
}
