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
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
    /** Subtitles from addons that arrived after playback started. */
    fun addSubtitles(subs: List<Subtitle>, applyNow: Boolean)
    fun setResize(mode: Int) // 0 fit, 1 zoom/crop, 2 stretch
    fun videoInfo(): String
    fun release()
}

private fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    return runCatching { Locale.forLanguageTag(code).displayLanguage.takeIf { it.isNotBlank() && it != code } }.getOrNull() ?: code
}

/** Picture modes: 0 Fit, 1 Fill screen (crop), 2 Stretch, 3 Remove black bars (zooms widescreen films that have bars in the picture). */
val PictureModes = listOf("Fit", "Fill screen", "Stretch", "Remove black bars")

internal fun cinemaScale(v: View, mode: Int) {
    val k = if (mode == 3) 1.33f else 1f // 2.39:1 film inside a 16:9 frame
    v.scaleX = k; v.scaleY = k
}

// ---------------------------------------------------------------- ExoPlayer

@OptIn(UnstableApi::class)
class ExoEngine(private val ctx: Context, private val safeAudio: Boolean = false) : Engine {
    override val label = if (safeAudio) "ExoPlayer (stereo)" else "ExoPlayer"
    /** Error code name + reason of the last failure, for diagnostics. */
    var lastError: PlaybackException? = null
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
        val renderers = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val b = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                // Safe mode: fold surround tracks down to stereo so devices that refuse 6/8-channel output still play.
                if (safeAudio) b.setAudioProcessors(arrayOf(stereoDownmix()))
                return b.build()
            }
        }
            .setExtensionRendererMode(if (safeAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableAudioFloatOutput(!safeAudio)
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
            .setBufferDurationsMs(20_000, 120_000, 1_000, 2_500)
            .setTargetBufferBytes(200 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
                    // Streams drop out for a moment now and then: keep retrying for a good while instead of failing.
                    .setLoadErrorHandlingPolicy(object : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(12) {
                        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                            val base = super.getRetryDelayMsFor(info)
                            return if (base == C.TIME_UNSET) 1500L else base.coerceAtMost(4000L)
                        }
                    }),
            )
            .build()
        player = p
        pv.player = p
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                lastError = error
                onError("${error.errorCodeName}: ${error.cause?.message ?: error.message}")
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) onEnded() }
            override fun onTracksChanged(tracks: Tracks) {
                // After loading an addon subtitle on demand, switch to it once it shows up.
                val want = pendingTextId ?: return
                val g = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && (0 until it.length).any { i -> it.getTrackFormat(i).id == want } } ?: return
                val i = (0 until g.length).first { g.getTrackFormat(it).id == want }
                pendingTextId = null
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            }
        })
        this.url = url; extSubs = subs; extApplied = true
        p.setMediaItem(buildItem(subs), startMs)
        p.prepare()
        p.playWhenReady = true
    }

    private var url = ""
    private var extSubs: List<Subtitle> = emptyList()
    private var extApplied = false
    private var pendingTextId: String? = null

    private fun buildItem(subs: List<Subtitle>): MediaItem {
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
                .setLabel("${langName(s.lang) ?: s.lang} (addon)").setId(s.url).build()
        })
        return item.build()
    }

    override fun addSubtitles(subs: List<Subtitle>, applyNow: Boolean) {
        extSubs = subs
        if (subs.isEmpty()) return
        if (applyNow) reload(null) else extApplied = false
    }

    /** Re-opens the stream at the current position with the addon subtitles attached. */
    private fun reload(selectUrl: String?) {
        val p = player ?: return
        val pos = p.currentPosition
        val playing = p.playWhenReady
        pendingTextId = selectUrl
        extApplied = true
        p.setMediaItem(buildItem(extSubs), pos)
        p.prepare()
        p.playWhenReady = playing
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
        val loaded = groups(C.TRACK_TYPE_TEXT).flatMapIndexed { gi, g ->
            (0 until g.length).map { i -> TrackOpt("$gi:$i", describe(g, i), !disabled && g.isTrackSelected(i)) }
        }
        // Addon subtitles not loaded yet are offered too; picking one loads it.
        val extra = if (extApplied) emptyList() else extSubs.map { s -> TrackOpt("ext:" + s.url, "${langName(s.lang) ?: s.lang} (addon)", false) }
        return loaded + extra
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
        when {
            id == null -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            id.startsWith("ext:") -> reload(id.removePrefix("ext:"))
            else -> select(C.TRACK_TYPE_TEXT, id)
        }
    }

    override fun setResize(mode: Int) {
        pv.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        cinemaScale(pv, mode)
    }

    override fun videoInfo(): String {
        val f = player?.videoFormat ?: return ""
        val codec = f.sampleMimeType?.substringAfter("/")?.uppercase() ?: ""
        return "${f.width}×${f.height} $codec".trim()
    }

    override fun release() {
        pv.player = null
        player?.stop()
        player?.release()
        player = null
    }

    /** After a fatal network error: reopen the stream where it stopped. Returns false if nothing to recover. */
    fun reconnect(): Boolean {
        val p = player ?: return false
        val pos = p.currentPosition
        p.seekTo(pos)
        p.prepare()
        p.playWhenReady = true
        return true
    }

    companion object {
        /** Network / source problems: reconnect rather than change player. */
        fun isNetworkError(e: PlaybackException?): Boolean {
            val c = e?.errorCode ?: return false
            return c in 2000..2999 || c == PlaybackException.ERROR_CODE_TIMEOUT || c == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        }

        /** Audio-output or decoder problems: worth one retry in safe mode before giving up on ExoPlayer. */
        fun isAudioOrDecoderError(e: PlaybackException?): Boolean = when (e?.errorCode) {
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> true
            else -> false
        }

        /** Mixes 3–8 channel PCM down to stereo with the usual centre/surround/LFE weights; 1–2 channels pass through. */
        fun stereoDownmix(): androidx.media3.common.audio.ChannelMixingAudioProcessor {
            val p = androidx.media3.common.audio.ChannelMixingAudioProcessor()
            p.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(1, 1))
            p.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(2, 2))
            val c = 0.7071f; val s = 0.5f; val lfe = 0.5f
            // Android channel order: L R C LFE Ls Rs (Lb Rb)
            fun m(inCount: Int, rows: List<FloatArray>): androidx.media3.common.audio.ChannelMixingMatrix {
                val coef = FloatArray(inCount * 2)
                for (i in 0 until inCount) { coef[i * 2] = rows[i][0]; coef[i * 2 + 1] = rows[i][1] }
                return androidx.media3.common.audio.ChannelMixingMatrix(inCount, 2, coef)
            }
            val L = floatArrayOf(1f, 0f); val R = floatArrayOf(0f, 1f); val C_ = floatArrayOf(c, c); val LFE = floatArrayOf(lfe, lfe)
            val Ls = floatArrayOf(s, 0f); val Rs = floatArrayOf(0f, s)
            p.putChannelMixingMatrix(m(3, listOf(L, R, C_)))
            p.putChannelMixingMatrix(m(4, listOf(L, R, Ls, Rs)))
            p.putChannelMixingMatrix(m(5, listOf(L, R, C_, Ls, Rs)))
            p.putChannelMixingMatrix(m(6, listOf(L, R, C_, LFE, Ls, Rs)))
            p.putChannelMixingMatrix(m(7, listOf(L, R, C_, LFE, Ls, Rs, floatArrayOf(s * c, s * c))))
            p.putChannelMixingMatrix(m(8, listOf(L, R, C_, LFE, Ls, Rs, Ls, Rs)))
            return p
        }
    }
}

// ---------------------------------------------------------------- VLC

class VlcEngine(ctx: Context) : Engine {
    override val label = "VLC"
    override var onError: (String) -> Unit = {}
    override var onEnded: () -> Unit = {}
    private val lib = LibVLC(ctx, arrayListOf("--network-caching=1500", "--http-reconnect", "--audio-time-stretch", "--sub-autodetect-file", "--no-stats", "--aout=audiotrack"))
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
    override fun addSubtitles(subs: List<Subtitle>, applyNow: Boolean) {
        subs.forEach { mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(it.url), false) }
    }

    override fun setResize(mode: Int) {
        mp.videoScale = when (mode) {
            1 -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            2 -> MediaPlayer.ScaleType.SURFACE_FILL
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        cinemaScale(layout, mode)
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
