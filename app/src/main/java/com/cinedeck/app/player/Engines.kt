package com.cinedeck.app.player

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cinedeck.app.data.Http
import com.cinedeck.app.data.Subtitle
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

data class TrackOpt(val id: String, val label: String, val selected: Boolean)

interface Engine {
    val label: String
    val view: View
    var onError: (String) -> Unit
    var onEnded: () -> Unit
    fun start(url: String, headers: Map<String, String>, subs: List<Subtitle>, startMs: Long, preferLangs: List<String>)
    fun play()
    fun pause()
    val isPlaying: Boolean
    val isBuffering: Boolean
    val position: Long
    val duration: Long
    fun seekTo(ms: Long)
    fun audioTracks(): List<TrackOpt>
    fun textTracks(): List<TrackOpt>
    fun selectAudio(id: String)
    fun selectText(id: String?) // null = off
    fun setResize(mode: Int) // 0 fit, 1 zoom/crop, 2 stretch
    fun videoInfo(): String
    fun release()
}

private fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    return runCatching { Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() && it != code } }.getOrNull() ?: code
}

// ---------------------------------------------------------------- ExoPlayer

@OptIn(UnstableApi::class)
class ExoEngine(private val ctx: Context) : Engine {
    override val label = "ExoPlayer"
    override var onError: (String) -> Unit = {}
    override var onEnded: () -> Unit = {}
    private val selector = DefaultTrackSelector(ctx)
    private var player: ExoPlayer? = null
    private val pv = PlayerView(ctx).apply {
        useController = false
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        keepScreenOn = true
    }
    override val view: View get() = pv

    override fun start(url: String, headers: Map<String, String>, subs: List<Subtitle>, startMs: Long, preferLangs: List<String>) {
        val http = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(headers)
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { http.setUserAgent(it.value) }
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        selector.setParameters(
            selector.buildUponParameters()
                .setPreferredTextLanguages(*preferLangs.toTypedArray())
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedAudioConstraintsIfNecessary(true)
                .setSelectUndeterminedTextLanguage(false),
        )
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 2_000, 4_000)
            .setTargetBufferBytes(200 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build()
        player = p
        pv.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onError("${error.errorCodeName}: ${error.cause?.message ?: error.message}") }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onEnded() }
        })
        val item = MediaItem.Builder().setUri(url)
        val lower = url.lowercase().substringBefore("?")
        when {
            lower.endsWith(".m3u8") || "/hls/" in lower -> item.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> item.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        item.setSubtitleConfigurations(subs.map { s ->
            val u = s.url.lowercase().substringBefore("?")
            val mime = when {
                u.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                u.endsWith(".ass") || u.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                u.endsWith(".ttml") || u.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url)).setMimeType(mime).setLanguage(s.lang)
                .setLabel("${langName(s.lang) ?: s.lang} (addon)").setId(s.id ?: s.url).build()
        })
        p.setMediaItem(item.build(), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override val isPlaying get() = player?.isPlaying == true
    override val isBuffering get() = player?.playbackState == Player.STATE_BUFFERING
    override val position get() = player?.currentPosition ?: 0
    override val duration get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0
    override fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }

    private fun groups(type: Int): List<Tracks.Group> = player?.currentTracks?.groups?.filter { it.type == type }.orEmpty()

    private fun describe(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(i)
        val parts = mutableListOf<String>()
        parts += f.label ?: langName(f.language) ?: "Track"
        if (f.label != null) langName(f.language)?.let { if (it !in parts[0]) parts += it }
        f.sampleMimeType?.substringAfter("/")?.uppercase()?.let { parts += it.replace("X-", "") }
        if (f.channelCount > 0) parts += when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" }
        if (!g.isTrackSupported(i)) parts += "unsupported"
        return parts.joinToString(" · ")
    }

    override fun audioTracks() = groups(C.TRACK_TYPE_AUDIO).flatMapIndexed { gi, g ->
        (0 until g.length).map { i -> TrackOpt("$gi:$i", describe(g, i), g.isTrackSelected(i)) }
    }

    override fun textTracks(): List<TrackOpt> {
        val disabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        return groups(C.TRACK_TYPE_TEXT).flatMapIndexed { gi, g ->
            (0 until g.length).map { i -> TrackOpt("$gi:$i", describe(g, i), !disabled && g.isTrackSelected(i)) }
        }
    }

    private fun select(type: Int, id: String) {
        val p = player ?: return
        val (gi, ti) = id.split(":").map { it.toInt() }
        val g = groups(type).getOrNull(gi) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
            .build()
    }

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)
    override fun selectText(id: String?) {
        val p = player ?: return
        if (id == null) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        else select(C.TRACK_TYPE_TEXT, id)
    }

    override fun setResize(mode: Int) {
        pv.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    override fun videoInfo(): String {
        val f = player?.videoFormat ?: return ""
        val codec = f.sampleMimeType?.substringAfter("/")?.uppercase() ?: ""
        return "${f.width}×${f.height} $codec".trim()
    }

    override fun release() {
        pv.player = null
        player?.release()
        player = null
    }
}

// ---------------------------------------------------------------- VLC

class VlcEngine(ctx: Context) : Engine {
    override val label = "VLC"
    override var onError: (String) -> Unit = {}
    override var onEnded: () -> Unit = {}
    private val lib = LibVLC(ctx, arrayListOf("--network-caching=3000", "--http-reconnect", "--audio-time-stretch", "--sub-autodetect-file"))
    private val mp = MediaPlayer(lib)
    private val layout = VLCVideoLayout(ctx).apply { keepScreenOn = true }
    override val view: View get() = layout
    private var buffering = 0f

    override fun start(url: String, headers: Map<String, String>, subs: List<Subtitle>, startMs: Long, preferLangs: List<String>) {
        mp.attachViews(layout, null, true, false)
        val media = Media(lib, Uri.parse(url))
        media.setHWDecoderEnabled(true, false)
        headers.forEach { (k, v) ->
            when (k.lowercase()) {
                "user-agent" -> media.addOption(":http-user-agent=$v")
                "referer" -> media.addOption(":http-referrer=$v")
            }
        }
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        if (preferLangs.isNotEmpty()) media.addOption(":sub-language=${preferLangs.joinToString(",")}")
        mp.media = media
        media.release()
        subs.forEach { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it.url), false) }
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.EncounteredError -> onError("VLC couldn't play this stream")
                MediaPlayer.Event.EndReached -> onEnded()
                MediaPlayer.Event.Buffering -> buffering = e.buffering
                MediaPlayer.Event.Playing -> buffering = 100f
            }
        }
        mp.play()
    }

    override fun play() { mp.play() }
    override fun pause() { mp.pause() }
    override val isPlaying get() = mp.isPlaying
    override val isBuffering get() = buffering < 100f
    override val position get() = mp.time.coerceAtLeast(0)
    override val duration get() = mp.length.coerceAtLeast(0)
    override fun seekTo(ms: Long) { mp.time = ms.coerceAtLeast(0) }

    override fun audioTracks() = mp.audioTracks.orEmpty().filter { it.id >= 0 }.map { TrackOpt(it.id.toString(), it.name, it.id == mp.audioTrack) }
    override fun textTracks() = mp.spuTracks.orEmpty().filter { it.id >= 0 }.map { TrackOpt(it.id.toString(), it.name, it.id == mp.spuTrack) }
    override fun selectAudio(id: String) { mp.audioTrack = id.toInt() }
    override fun selectText(id: String?) { mp.spuTrack = id?.toInt() ?: -1 }

    override fun setResize(mode: Int) {
        mp.videoScale = when (mode) {
            1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            2 -> MediaPlayer.ScaleType.SURFACE_FILL
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
    }

    override fun videoInfo(): String {
        val t = mp.currentVideoTrack ?: return ""
        return "${t.width}×${t.height} ${t.codec ?: ""}".trim()
    }

    override fun release() {
        mp.setEventListener(null)
        runCatching { mp.stop() }
        mp.detachViews()
        mp.release()
        lib.release()
    }
}
