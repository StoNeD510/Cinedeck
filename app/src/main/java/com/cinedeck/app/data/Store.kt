package com.cinedeck.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Everything the app remembers, kept in one JSON file on the device. */
object Store {
    private lateinit var file: File
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = Channel<Unit>(Channel.CONFLATED)

    fun init(context: Context) {
        file = File(context.filesDir, "cinedeck.json")
        if (file.exists()) {
            runCatching { AppJson.decodeFromString<AppData>(file.readText()) }
                .onSuccess { _data.value = it.copy(addons = it.addons.ifEmpty { DefaultAddons }) }
        }
        // ReelDJ restyle: the Neon Booth layout uses the top bar, so switch once; the setting still works after.
        if (_data.value.settings.uiVersion < 1) {
            _data.value = _data.value.copy(settings = _data.value.settings.copy(navPosition = "top", uiVersion = 1))
        }
        com.cinedeck.app.ui.ThemeState.palette = com.cinedeck.app.ui.paletteFor(_data.value.settings.theme)
        scope.launch {
            for (s in saveSignal) {
                val text = AppJson.encodeToString(AppData.serializer(), _data.value)
                val tmp = File(file.parentFile, "cinedeck.json.tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    fun edit(block: (AppData) -> AppData) {
        _data.update(block)
        com.cinedeck.app.ui.ThemeState.palette = com.cinedeck.app.ui.paletteFor(_data.value.settings.theme)
        saveSignal.trySend(Unit)
    }

    val settings get() = _data.value.settings

    // ----- library -----
    fun inLibrary(id: String) = _data.value.library.any { it.id == id }

    fun toggleLibrary(meta: Meta) = edit { d ->
        if (d.library.any { it.id == meta.id }) d.copy(library = d.library.filterNot { it.id == meta.id })
        else d.copy(library = listOf(LibraryItem(meta.id, meta.type, meta.name, meta.poster, meta.background)) + d.library)
    }

    fun removeFromLibrary(id: String) = edit { d -> d.copy(library = d.library.filterNot { it.id == id }) }

    // ----- progress / watched -----
    fun saveProgress(req: PlayRequest, position: Long, duration: Long) = edit { d ->
        val watched = duration > 0 && position >= duration * 0.97
        val old = d.progress[req.videoId]
        val p = Progress(
            videoId = req.videoId, metaId = req.metaId, type = req.type, name = req.name,
            poster = req.poster, background = req.background, season = req.season, episode = req.episode,
            episodeTitle = req.episodeTitle,
            position = if (watched) 0 else position, duration = duration,
            watched = watched || (old?.watched == true && position < 60_000),
            updatedAt = System.currentTimeMillis(),
        )
        d.copy(progress = d.progress + (req.videoId to p))
    }

    fun setWatched(meta: Meta, videos: List<Video>, watched: Boolean) = edit { d ->
        val map = d.progress.toMutableMap()
        if (videos.isEmpty()) {
            mark(map, meta, meta.id, null, watched)
        } else videos.forEach { mark(map, meta, it.id, it, watched) }
        d.copy(progress = map)
    }

    private fun mark(map: MutableMap<String, Progress>, meta: Meta, videoId: String, v: Video?, watched: Boolean) {
        if (!watched) { map.remove(videoId); return }
        val old = map[videoId]
        map[videoId] = Progress(
            videoId = videoId, metaId = meta.id, type = meta.type, name = meta.name,
            poster = meta.poster, background = meta.background, season = v?.season, episode = v?.ep,
            episodeTitle = v?.label, position = 0, duration = old?.duration ?: 0, watched = true,
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun removeProgressForMeta(metaId: String) = edit { d -> d.copy(progress = d.progress.filterValues { it.metaId != metaId }) }
    fun clearHistory() = edit { it.copy(progress = emptyMap()) }

    // ----- addons -----
    fun setAddons(list: List<AddonEntry>) = edit { it.copy(addons = list) }
    fun updateSettings(block: (Settings) -> Settings) = edit { it.copy(settings = block(it.settings)) }
}
