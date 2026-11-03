package com.cinedeck.app.player

import android.os.Bundle
import android.view.KeyEvent as AKeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cinedeck.app.data.AppJson
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.PlayRequest
import com.cinedeck.app.data.Store
import com.cinedeck.app.data.Subtitle
import com.cinedeck.app.ui.CineTheme
import com.cinedeck.app.ui.FocusBox
import com.cinedeck.app.ui.epCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.cinedeck.app.ui.C as UiC

class PlayerActivity : ComponentActivity() {
    /** Set by the screen: releases the active engine immediately (frees the video surface so the TV leaves HDR mode). */
    var teardown: (() -> Unit)? = null

    override fun onPause() {
        super.onPause()
        if (isFinishing) { teardown?.invoke(); teardown = null }
    }

    override fun onDestroy() {
        teardown?.invoke(); teardown = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val req = intent.getStringExtra(PlayerLauncher.EXTRA)?.let { AppJson.decodeFromString(PlayRequest.serializer(), it) }
        if (req == null) { finish(); return }
        setContent { CineTheme { PlayerScreen(req, onExit = { finish() }) } }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun PlayerScreen(req: PlayRequest, onExit: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = Store.settings
    val langs = settings.subtitleLangs.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
    val audioLangs = settings.audioLangs.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
    var kind by remember { mutableStateOf(if (req.engine == "vlc") "vlc" else "exo") }
    var subs by remember { mutableStateOf<List<Subtitle>>(emptyList()) }
    var engine by remember { mutableStateOf<Engine?>(null) }
    var startAt by remember {
        val p = Store.data.value.progress[req.videoId]
        mutableLongStateOf(if (p != null && !p.watched && p.position > 10_000) p.position - 3_000 else 0L)
    }
    var error by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) } // first frame playing: hides the loading splash
    var engineKind by remember { mutableStateOf("") }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<String?>(null) } // audio | text
    var resize by remember { mutableIntStateOf(0) }
    var draftMs by remember { mutableLongStateOf(-1L) } // scrub preview position, -1 = not scrubbing
    val scrubFocus = remember { FocusRequester() }
    var focusScrub by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    // Subtitles from addons load in the background while the stream connects; playback never waits for them.
    LaunchedEffect(req.videoId) {
        if (!settings.autoSubtitles || req.type == "other") return@LaunchedEffect
        // Fingerprint the file first so the addon can return subtitles timed for this exact release.
        val fp = withTimeoutOrNull(4_000) { Addons.videoHash(req.url, req.headers) }
        val extra = buildMap {
            fp?.let { (hash, size) -> put("videoHash", hash); put("videoSize", size.toString()) }
            req.filename?.let { put("filename", it) }
        }
        val found = withTimeoutOrNull(8_000) { runCatching { Addons.subtitles(req.type, req.videoId, extra) }.getOrDefault(emptyList()) }.orEmpty()
            .filter { s -> langs.isEmpty() || langs.any { l -> s.lang.equals(l, true) || s.lang.startsWith(l.take(2), true) } }
            .distinctBy { it.url }.take(12)
        subs = found
        // If the picture is already up, don't interrupt it: the subtitles are offered in the Subtitles menu instead.
        engine?.addSubtitles(found, applyNow = !started)
    }

    var finished by remember { mutableStateOf(false) } // marked watched (Up next pressed): later saves must not undo it
    fun save() {
        if (finished) return
        val e = engine ?: return
        if (e.duration > 0) Store.saveProgress(req, e.position, e.duration)
    }

    val switchScope = androidx.compose.runtime.rememberCoroutineScope()
    var reconnects by remember { mutableIntStateOf(0) }
    var stallSince by remember { mutableLongStateOf(0L) }
    var audioDelay by remember { mutableLongStateOf(0L) } // ms, this playback only
    var subDelay by remember { mutableLongStateOf(0L) }
    fun switchEngine(to: String, reason: String? = null) {
        val e = engine
        if (e != null && e.position > 0) startAt = e.position
        save()
        error = null
        buffering = true
        reason?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        // Tear the old engine down fully, then give the audio output a moment to free up before the next one opens it.
        kind = "none"
        switchScope.launch { kotlinx.coroutines.delay(450); kind = to }
    }

    key(kind) {
        if (kind != "none") DisposableEffect(Unit) {
            val e: Engine = when (kind) { "vlc" -> VlcEngine(ctx); "exosafe" -> ExoEngine(ctx, safeAudio = true); else -> ExoEngine(ctx) }
            e.onError = { msg ->
                PlayerLog.add("${e.label}: $msg  [${req.name} ${req.filename ?: ""}]")
                val exoErr = (e as? ExoEngine)?.lastError
                when {
                    // Connection dropped mid-stream: pick up where it stopped, don't change player.
                    e is ExoEngine && ExoEngine.isNetworkError(exoErr) && reconnects < 6 -> {
                        reconnects++
                        PlayerLog.add("${e.label}: reopening stream at ${fmt(e.position)} (try $reconnects) after ${exoErr?.errorCodeName}: ${(exoErr?.cause ?: exoErr)?.javaClass?.simpleName} ${exoErr?.cause?.message?.take(80) ?: ""}")
                        buffering = true
                        switchScope.launch { kotlinx.coroutines.delay(400L * reconnects); if (engine === e) e.reconnect() }
                    }
                    // Audio output / decoder trouble: try ExoPlayer once more with stereo + platform decoders.
                    kind == "exo" && req.engine != "vlc" && ExoEngine.isAudioOrDecoderError(exoErr) ->
                        switchEngine("exosafe", "Audio problem, retrying in stereo…")
                    kind != "vlc" && req.engine == "auto" -> switchEngine("vlc", "ExoPlayer: ${exoErr?.errorCodeName ?: msg.take(40)} — switching to VLC")
                    else -> error = msg
                }
            }
            e.onEnded = { ended = true; controls = true; save() }
            engine = e
            engineKind = kind
            (ctx as? PlayerActivity)?.teardown = {
                if (engine === e) {
                    if (!finished && e.duration > 0) Store.saveProgress(req, e.position, e.duration)
                    runCatching { e.release() }
                    runCatching { hideSurfaces(e.view); (e.view.parent as? android.view.ViewGroup)?.removeView(e.view) }
                    engine = null
                }
            }
            e.start(req.url, req.headers, subs, startAt, langs, audioLangs)
            e.setResize(resize)
            if (audioDelay != 0L) e.setAudioDelay(audioDelay)
            if (subDelay != 0L) e.setSubtitleDelay(subDelay)
            onDispose {
                if (engine === e) {
                    if (!finished && e.duration > 0) Store.saveProgress(req, e.position, e.duration)
                    (e.view.parent as? android.view.ViewGroup)?.removeView(e.view)
                    e.release()
                    engine = null
                }
            }
        }
        val e = engine
        // The video surface lives in a container we own; a surface is moved, never added twice.
        if (e != null && engineKind == kind) AndroidView(
            factory = { c -> android.widget.FrameLayout(c) },
            update = { box ->
                val v = e.view
                if (v.parent !== box) {
                    (v.parent as? android.view.ViewGroup)?.removeView(v)
                    box.removeAllViews()
                    box.addView(v, android.widget.FrameLayout.LayoutParams(-1, -1))
                }
            },
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_STOP) { engine?.pause(); save() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // If nothing has played after 45 s, stop spinning and offer a way out.
    LaunchedEffect(kind, started) {
        if (started || kind == "none") return@LaunchedEffect
        kotlinx.coroutines.delay(45_000)
        if (!started && error == null) {
            PlayerLog.add("${engine?.label ?: kind}: stuck loading for 45 s  [${req.name} ${req.filename ?: ""}]")
            error = "The stream never started. The link may be rate-limited or expired; try another stream, or the other player."
        }
    }

    // Poll player state, save progress every 10s, auto-hide controls.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(500)
            engine?.let { e ->
                if (draftMs < 0) pos = e.position
                dur = e.duration
                playing = e.isPlaying
                buffering = e.isBuffering
                if (!started && e.isPlaying && e.position > 300) { started = true; Store.startWatching(req) }
            }
            if (++tick % 20 == 0) save()
            if (tick % 240 == 0 && playing && !buffering) reconnects = 0 // two minutes of smooth playback forgives earlier blips
            // Rebuffering mid-stream: note how long each one lasts; after 40 s with no data, reopen the stream,
            // and if that keeps happening hand over to VLC.
            if (started && buffering && error == null) {
                if (stallSince == 0L) stallSince = System.currentTimeMillis()
                else if (System.currentTimeMillis() - stallSince > 40_000) {
                    stallSince = 0L
                    val e = engine
                    if (e is ExoEngine && reconnects < 6) {
                        reconnects++
                        PlayerLog.add("${e.label}: no data for 40 s at ${fmt(e.position)} — reopening (try $reconnects)")
                        e.reconnect()
                    } else if (kind != "vlc" && req.engine == "auto") switchEngine("vlc", "Stream keeps stalling — switching to VLC")
                }
            } else {
                if (stallSince != 0L) {
                    val secs = (System.currentTimeMillis() - stallSince) / 1000
                    if (secs >= 2) PlayerLog.add("${engine?.label ?: kind}: rebuffered ${secs}s at ${fmt(pos)}" + ((engine as? ExoEngine)?.let { " (buffer now ${it.bufferedAheadMs / 1000}s ahead)" } ?: ""))
                }
                stallSince = 0L
            }
            if (controls && panel == null && playing && draftMs < 0 && System.currentTimeMillis() - lastInput > 4500) controls = false
        }
    }
    LaunchedEffect(controls) {
        if (controls) kotlinx.coroutines.delay(50)
        runCatching {
            when {
                controls && focusScrub -> { scrubFocus.requestFocus(); focusScrub = false }
                controls -> playFocus.requestFocus()
                panel == "sync" -> {}
                else -> rootFocus.requestFocus()
            }
        }
    }
    fun commitScrub() {
        if (draftMs >= 0) { engine?.seekTo(draftMs); pos = draftMs; draftMs = -1L }
    }
    // Commit a scrub shortly after the last move, so the picture catches up while you hold the arrow.
    LaunchedEffect(draftMs) {
        if (draftMs >= 0) { kotlinx.coroutines.delay(1200); commitScrub() }
    }

    fun toggle() { engine?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(d: Long) { engine?.let { it.seekTo((it.position + d).coerceIn(0, if (it.duration > 0) it.duration else Long.MAX_VALUE)); pos = it.position } }

    // ---- Next episode ----
    val autoplay = Store.settings.autoplayNext
    var nextVideo by remember { mutableStateOf<com.cinedeck.app.data.Video?>(null) }
    var nextReq by remember { mutableStateOf<PlayRequest?>(null) }
    var nextState by remember { mutableStateOf("idle") } // idle | finding | ready | none
    var nextDismissed by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(15) }
    LaunchedEffect(req.videoId) { nextVideo = runCatching { NextEpisode.episodeAfter(req) }.getOrNull() }
    // Card from 93% of the episode (where credits usually start); stream lookup starts earlier, at 80%,
    // so the button is ready the moment the card appears.
    val nearEnd = dur > 60_000 && pos >= dur * 0.93
    val prefetch = dur > 60_000 && pos >= dur * 0.80
    val showNext = nextVideo != null && !nextDismissed && (nearEnd || ended) && error == null
    var wantNext by remember { mutableStateOf(false) } // Next-episode button pressed before the stream was found
    LaunchedEffect(prefetch || showNext || wantNext, nextVideo) {
        if ((prefetch || showNext || wantNext) && nextVideo != null && nextState == "idle") {
            nextState = "finding"
            nextReq = runCatching { NextEpisode.resolve(req, nextVideo!!) }.getOrNull()
            nextState = if (nextReq != null) "ready" else "none"
        }
    }
    fun playNext() {
        val n = nextReq ?: return
        finished = true
        Store.markWatched(req)
        Store.startWatching(n) // so Continue Watching points at the new episode right away
        PlayerLauncher.play(ctx, n.copy(engine = Store.settings.engine))
        onExit()
    }
    LaunchedEffect(wantNext, nextState) {
        if (wantNext && nextState == "ready") playNext()
        else if (wantNext && nextState == "none") { wantNext = false; Toast.makeText(ctx, "No stream found for the next episode", Toast.LENGTH_SHORT).show() }
    }
    // Auto-play: count down once the next stream is found; at the very end, go right away.
    LaunchedEffect(showNext, nextState, autoplay) {
        if (!showNext || nextState != "ready" || !autoplay) return@LaunchedEffect
        countdown = if (ended) 3 else 15
        while (countdown > 0) { kotlinx.coroutines.delay(1000); countdown-- }
        playNext()
    }
    LaunchedEffect(ended) { if (ended && autoplay && nextState == "ready") { countdown = 0; playNext() } }

    BackHandler {
        if (panel != null) panel = null else { save(); onExit() }
    }

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { ev ->
                lastInput = System.currentTimeMillis()
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (showNext && !controls && ev.key.nativeKeyCode in listOf(
                        AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_DPAD_DOWN,
                        AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT,
                    )) return@onPreviewKeyEvent false
                when (ev.key.nativeKeyCode) {
                    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_SPACE -> { toggle(); true }
                    AKeyEvent.KEYCODE_MEDIA_PLAY -> { engine?.play(); true }
                    AKeyEvent.KEYCODE_MEDIA_PAUSE -> { engine?.pause(); true }
                    AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); true }
                    AKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
                    // With the controls hidden, left/right jump straight onto the timeline and start scrubbing.
                    AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT -> if (started && !controls && panel == null) {
                        val d = if (ev.key.nativeKeyCode == AKeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                        draftMs = (pos + d).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                        focusScrub = true; controls = true; true
                    } else false
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_DPAD_DOWN ->
                        if (!started) true else if (!controls && panel == null) { controls = true; true } else false
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                lastInput = System.currentTimeMillis()
                if (panel == "sync") { panel = null; controls = true } else { controls = !controls; if (!controls) panel = null }
            },
    ) {
        if (started && buffering && error == null) CircularProgressIndicator(color = UiC.Accent, modifier = Modifier.align(Alignment.Center).size(56.dp))
        if (!started) LoadingSplash(req, kind)

        // "Cinema Bar": title top-left, clock top-right, one slim bar along the bottom. The picture is never dimmed.
        AnimatedVisibility(started && controls && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(190.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
                // Title block
                Column(Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 22.dp, end = 200.dp)) {
                    Text(
                        req.name, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp, fontFamily = com.cinedeck.app.ui.Display,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    req.season?.let {
                        Text(
                            epCode(it, req.episode) + (req.episodeTitle?.let { t -> "   ·   $t" } ?: ""), color = UiC.Muted, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val info = engine?.videoInfo().orEmpty()
                        val tags = buildList {
                            req.resolution?.takeIf { it.isNotBlank() }?.let { add(it) }
                                ?: info.substringBefore(" ").substringAfter("×").toIntOrNull()?.let { h -> add(when { h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; else -> "${h}p" }) }
                            info.substringAfter(" ", "").takeIf { it.isNotBlank() }?.let { add(it) }
                            engine?.label?.let { add(it) }
                        }
                        tags.forEach { Tag(it) }
                    }
                }
                // Clock + wordmark
                Row(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(), color = UiC.Muted, fontSize = 13.sp)
                    Spacer(Modifier.width(16.dp))
                    com.cinedeck.app.ui.Logo(14.sp)
                }
                // Bottom bar
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp)) {
                    if (nextVideo != null && nearEnd && !ended) Text(
                        (if (autoplay && nextState == "ready") "Up next in $countdown s  ·  " else "Up next  ·  ") + epCode(nextVideo!!.season, nextVideo!!.ep) + " — " + nextVideo!!.label,
                        color = UiC.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 10.dp).background(Color(0x80000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(if (draftMs >= 0) draftMs else pos), color = Color.White, fontSize = 14.sp)
                        ScrubBar(
                            pos = pos, dur = dur, draftMs = draftMs,
                            onDraft = { draftMs = it; lastInput = System.currentTimeMillis() },
                            onCommit = { commitScrub() },
                            focus = scrubFocus,
                            modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        )
                        Text("−" + fmt((dur - pos).coerceAtLeast(0)), color = UiC.Muted, fontSize = 14.sp)
                        if (dur > 0) Text("Ends " + clock(dur - pos), color = UiC.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CtlButton(Icons.Default.Replay10, "Back 10 seconds") { seekBy(-10_000) }
                            CtlButton(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play",
                                accent = true, modifier = Modifier.focusRequester(playFocus),
                            ) { if (ended) { ended = false; engine?.seekTo(0); engine?.play() } else toggle() }
                            CtlButton(Icons.Default.Forward10, "Forward 10 seconds") { seekBy(10_000) }
                            if (nextVideo != null) CtlButton(Icons.Default.SkipNext, "Next episode") {
                                if (nextState == "ready") playNext()
                                else if (nextState == "none") Toast.makeText(ctx, "No stream found for the next episode", Toast.LENGTH_SHORT).show()
                                else { wantNext = true; Toast.makeText(ctx, "Finding the next episode…", Toast.LENGTH_SHORT).show() }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val e = engine
                            val audioName = e?.audioTracks()?.firstOrNull { it.selected }?.label?.let(::shortTrack)
                            val textName = e?.textTracks()?.firstOrNull { it.selected && it.id != "off" }?.label?.let(::shortTrack) ?: "Off"
                            CtlChip(Icons.Default.Audiotrack, "Audio: ${audioName ?: "Default"}") { panel = "audio" }
                            CtlChip(Icons.Default.Subtitles, "Subtitles: $textName") { panel = "text" }
                            CtlChip(Icons.Default.Tune, if (audioDelay == 0L && subDelay == 0L) "Sync" else "Sync ${fmtDelay(audioDelay)}/${fmtDelay(subDelay)}") { panel = "sync"; controls = false }
                            CtlChip(Icons.Default.AspectRatio, "Picture: ${PictureModes[resize]}") { panel = "picture" }
                            CtlChip(Icons.Default.SwapHoriz, if (kind != "vlc") "VLC ⇄" else "ExoPlayer ⇄") { switchEngine(if (kind != "vlc") "vlc" else "exo") }
                        }
                    }
                }
            }
        }

        // Audio / subtitle / picture pickers open in their own window so the remote moves into them.
        if (panel == "sync") SyncStrip(
            audio = audioDelay, sub = subDelay,
            onAudio = { v -> audioDelay = v; engine?.setAudioDelay(v) },
            onSub = { v ->
                subDelay = v
                if (engine?.setSubtitleDelay(v) == false) Toast.makeText(ctx, "This subtitle track is built into the video; ExoPlayer can't shift it. Use an addon subtitle or switch to VLC.", Toast.LENGTH_LONG).show()
            },
            onDismiss = { panel = null; controls = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        )
        panel?.takeIf { it != "sync" }?.let { p ->
            val e = engine
            val opts: List<TrackOpt> = when (p) {
                "audio" -> e?.audioTracks().orEmpty()
                "text" -> listOf(TrackOpt("off", "Off", e?.textTracks().orEmpty().none { it.selected })) + e?.textTracks().orEmpty()
                else -> PictureModes.mapIndexed { i, n -> TrackOpt(i.toString(), n, i == resize) }
            }
            OptionsDialog(
                title = when (p) { "audio" -> "Audio"; "text" -> "Subtitles"; else -> "Picture size" },
                options = opts,
                empty = if (p == "picture") "" else "No tracks found yet. They appear once the video starts.",
                onPick = { o ->
                    when (p) {
                        "audio" -> e?.selectAudio(o.id)
                        "text" -> e?.selectText(if (o.id == "off") null else o.id)
                        else -> { resize = o.id.toInt(); e?.setResize(resize) }
                    }
                    panel = null
                },
                onDismiss = { panel = null },
            )
        }

        if (showNext) NextCard(
            video = nextVideo!!, state = nextState, countdown = if (autoplay && nextState == "ready") countdown else null,
            fallbackImage = req.background, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = if (controls) 150.dp else 28.dp),
            onPlay = { playNext() }, onHide = { nextDismissed = true },
        )

        // Errors
        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 520.dp).background(UiC.Surface, RoundedCornerShape(14.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Playback failed", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(msg, color = UiC.Muted, fontSize = 13.sp, maxLines = 4)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val errFocus = remember { FocusRequester() }
                    LaunchedEffect(msg) { kotlinx.coroutines.delay(100); runCatching { errFocus.requestFocus() } }
                    CtlChip(Icons.Default.SwapHoriz, if (kind != "vlc") "Try VLC" else "Try ExoPlayer", Modifier.focusRequester(errFocus)) { switchEngine(if (kind != "vlc") "vlc" else "exo") }
                    CtlChip(Icons.Default.PlayArrow, "External player") { PlayerLauncher.external(ctx, req) }
                    CtlChip(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onExit() }
                }
            }
        }
    }
}

/**
 * Timeline you can scrub. Remote: focus it, then hold left/right (it speeds up the longer you hold),
 * press OK to jump right away or just let go. Touch: tap or drag.
 */
@Composable
private fun ScrubBar(
    pos: Long, dur: Long, draftMs: Long,
    onDraft: (Long) -> Unit, onCommit: () -> Unit,
    focus: FocusRequester, modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    val shown = if (draftMs >= 0) draftMs else pos
    val frac = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val playedFrac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    fun fromX(x: Float) = if (dur > 0) (x / widthPx).coerceIn(0f, 1f).let { (it * dur).toLong() } else 0L
    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) onCommit() }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown || dur <= 0) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                // 10s taps; holding speeds up to 30s, 1 min, then 2% of the runtime per step.
                val step = when {
                    repeat < 4 -> 10_000L
                    repeat < 12 -> 30_000L
                    repeat < 24 -> 60_000L
                    else -> maxOf(120_000L, dur / 50)
                }
                val base = if (draftMs >= 0) draftMs else pos
                when (ev.key.nativeKeyCode) {
                    AKeyEvent.KEYCODE_DPAD_LEFT -> { onDraft((base - step).coerceIn(0, dur)); true }
                    AKeyEvent.KEYCODE_DPAD_RIGHT -> { onDraft((base + step).coerceIn(0, dur)); true }
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER -> { onCommit(); true }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(dur, widthPx) {
                detectTapGestures { o -> onDraft(fromX(o.x)); onCommit() }
            }
            .pointerInput(dur, widthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDraft(fromX(o.x)) },
                    onDragEnd = { onCommit() },
                    onHorizontalDrag = { ch, _ -> onDraft(fromX(ch.position.x)) },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val track = if (focused) 8.dp else 5.dp
        Box(Modifier.fillMaxWidth().height(track).clip(RoundedCornerShape(50)).background(Color(0x55FFFFFF)))
        Box(Modifier.fillMaxWidth(playedFrac).height(track).clip(RoundedCornerShape(50)).background(UiC.Accent.copy(alpha = if (draftMs >= 0) 0.45f else 1f)))
        if (draftMs >= 0) Box(Modifier.fillMaxWidth(frac).height(track).clip(RoundedCornerShape(50)).background(UiC.Accent))
        // Thumb + time bubble while scrubbing
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val x = maxWidth * frac
            val thumb = if (focused) 22.dp else 14.dp
            Box(
                Modifier.offset(x = x - thumb / 2).size(thumb).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (focused) Color.White else UiC.Accent)
                    .then(if (focused) Modifier.border(4.dp, UiC.Accent, androidx.compose.foundation.shape.CircleShape) else Modifier),
            )
            if (draftMs >= 0) Text(
                fmt(draftMs), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(x = (x - 34.dp).coerceIn(0.dp, maxWidth - 68.dp), y = (-34).dp)
                    .background(UiC.Accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** "Up next" card shown near the end of an episode. */
@Composable
private fun NextCard(
    video: com.cinedeck.app.data.Video, state: String, countdown: Int?, fallbackImage: String?,
    modifier: Modifier, onPlay: () -> Unit, onHide: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(state) { repeat(8) { kotlinx.coroutines.delay(100); if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect } }
    Row(
        modifier.widthIn(max = 560.dp).background(Color(0xEE08080B), RoundedCornerShape(14.dp))
            .border(1.dp, UiC.Line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        coil.compose.AsyncImage(
            video.thumbnail ?: fallbackImage, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.width(170.dp).height(96.dp).clip(RoundedCornerShape(8.dp)).background(UiC.Surface2),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("UP NEXT", color = UiC.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text(
                epCode(video.season, video.ep) + "  ·  " + video.label, color = Color.White, fontSize = 15.sp,
                fontFamily = com.cinedeck.app.ui.Display, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    "ready" -> CtlChip(Icons.Default.PlayArrow, if (countdown != null) "Play now ($countdown)" else "Play next episode", Modifier.focusRequester(focus)) { onPlay() }
                    "none" -> Text("No matching stream found. Pick one from the episode list.", color = UiC.Muted, fontSize = 12.sp)
                    else -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = UiC.Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp)); Text("Finding stream…", color = UiC.Muted, fontSize = 12.sp)
                    }
                }
                CtlChip(Icons.AutoMirrored.Filled.ArrowBack, "Hide", if (state != "ready") Modifier.focusRequester(focus) else Modifier) { onHide() }
            }
        }
    }
}

/** Hides every video surface under a view, so the display drops HDR mode even before the window goes away. */
private fun hideSurfaces(v: android.view.View) {
    if (v is android.view.SurfaceView || v is android.view.TextureView) { v.visibility = android.view.View.GONE; return }
    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) hideSurfaces(v.getChildAt(i))
}

/** Full-screen artwork shown while the stream connects, instead of player buttons. */
@Composable
private fun LoadingSplash(req: PlayRequest, kind: String) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        coil.compose.AsyncImage(
            req.background ?: req.poster, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF208080B), Color(0xB308080B), Color(0x4008080B)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC08080B)))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 64.dp, end = 64.dp).widthIn(max = 620.dp)) {
            com.cinedeck.app.ui.Logo(18.sp)
            Spacer(Modifier.height(28.dp))
            Text(
                req.name, color = Color.White, fontSize = 40.sp, lineHeight = 44.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontFamily = com.cinedeck.app.ui.Display, fontWeight = FontWeight.Bold,
            )
            req.season?.let {
                Spacer(Modifier.height(6.dp))
                Text(epCode(it, req.episode) + (req.episodeTitle?.let { t -> "  ·  $t" } ?: ""), color = UiC.Text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = UiC.Accent, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Text(if (kind == "vlc") "Starting stream with VLC…" else "Starting stream…", color = UiC.Muted, fontSize = 15.sp)
            }
        }
    }
}

/** Round glass transport button; the accent one is the play/pause control. */
@Composable
private fun CtlButton(icon: ImageVector, desc: String, accent: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val circle = androidx.compose.foundation.shape.CircleShape
    FocusBox(onClick = onClick, shape = circle, modifier = modifier, focusScale = 1.12f) {
        Box(
            Modifier.size(46.dp)
                .background(if (accent) UiC.Accent else Color(0x1AFFFFFF), circle)
                .border(1.dp, if (accent) UiC.Accent else Color(0x2EFFFFFF), circle),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, desc, tint = if (accent) Color(0xFF08080B) else Color.White, modifier = Modifier.size(24.dp)) }
    }
}

/** Pill with an icon and a short "Label: value" text. */
@Composable
private fun CtlChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.06f, modifier = modifier) {
        Row(
            Modifier.background(Color(0x1AFFFFFF), RoundedCornerShape(50)).border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Small outlined tag under the title: "4K", "HEVC", "ExoPlayer". */
@Composable
private fun Tag(text: String) {
    Text(
        text, color = UiC.Muted, fontSize = 11.sp, lineHeight = 14.sp,
        modifier = Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Wall-clock time, optionally some milliseconds from now ("Ends 11:07 PM"). */
private fun clock(fromNowMs: Long = 0L): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + fromNowMs))

/** "English · AAC · 5.1" → "English 5.1"; keeps the chip short. */
private fun shortTrack(label: String): String {
    val parts = label.split(" · ")
    val ch = parts.drop(1).firstOrNull { it in listOf("5.1", "7.1", "Stereo", "Mono") || it.endsWith("ch") }
    return listOfNotNull(parts.firstOrNull()?.removeSuffix(" (addon)"), ch).joinToString(" ")
}

private fun fmtDelay(ms: Long) = (if (ms > 0) "+" else "") + (if (ms % 1000 == 0L) "${ms / 1000}s" else "${ms}ms")

/** Audio / subtitle delay for this playback: a slim strip over the bottom of the picture, nothing dimmed. */
@Composable
private fun SyncStrip(audio: Long, sub: Long, onAudio: (Long) -> Unit, onSub: (Long) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { kotlinx.coroutines.delay(80); if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect } }
    Column(
        modifier
            .onPreviewKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key.nativeKeyCode == AKeyEvent.KEYCODE_BACK) { onDismiss(); true } else false }
            .widthIn(max = 560.dp)
            .background(Color(0x99000000), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DelayRow("Audio", audio, 250L, Modifier.focusRequester(first), onAudio)
        DelayRow("Subtitles", sub, 250L, Modifier, onSub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("← later / earlier →  ·  OK resets  ·  Back closes", color = UiC.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            CtlChip(Icons.AutoMirrored.Filled.ArrowBack, "Done") { onDismiss() }
        }
    }
}

@Composable
private fun DelayRow(title: String, value: Long, step: Long, modifier: Modifier, onChange: (Long) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val limit = 30_000L
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key.nativeKeyCode) {
                    AKeyEvent.KEYCODE_DPAD_LEFT -> { onChange((value - step).coerceIn(-limit, limit)); true }
                    AKeyEvent.KEYCODE_DPAD_RIGHT -> { onChange((value + step).coerceIn(-limit, limit)); true }
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER -> { onChange(0L); true }
                    else -> false
                }
            }
            .focusable()
            .background(if (focused) Color(0x33FFFFFF) else Color.Transparent, RoundedCornerShape(10.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) UiC.Accent else Color(0x55FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
        CtlChip(Icons.Default.Remove, "") { onChange((value - step).coerceIn(-limit, limit)) }
        Text(
            fmtDelay(value), color = if (value == 0L) UiC.Muted else UiC.Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            fontFamily = com.cinedeck.app.ui.Display, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        CtlChip(Icons.Default.Add, "") { onChange((value + step).coerceIn(-limit, limit)) }
    }
}

@Composable
private fun OptionsDialog(title: String, options: List<TrackOpt>, empty: String, onPick: (TrackOpt) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            repeat(10) { kotlinx.coroutines.delay(80); if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect }
        }
        Box(
            Modifier.fillMaxSize()
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && (ev.key.nativeKeyCode == AKeyEvent.KEYCODE_BACK || ev.key.nativeKeyCode == AKeyEvent.KEYCODE_ESCAPE)) { onDismiss(); true } else false
                }
                .clickable(remember { MutableInteractionSource() }, indication = null) { onDismiss() },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier.fillMaxHeight().width(400.dp).background(UiC.Surface)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(20.dp),
            ) {
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = com.cinedeck.app.ui.Display)
                Spacer(Modifier.height(12.dp))
                if (options.isEmpty()) Text(empty, color = UiC.Muted, fontSize = 13.sp)
                val focusIndex = options.indexOfFirst { it.selected }.coerceAtLeast(0)
                LazyColumn {
                    items(options.size) { i ->
                        val o = options[i]
                        TrackRow(o.label, o.selected, if (i == focusIndex) Modifier.focusRequester(first) else Modifier) { onPick(o) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.02f) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) UiC.Accent.copy(alpha = 0.25f) else if (selected) UiC.Surface2 else Color.Transparent).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, null, tint = UiC.Accent)
        }
    }
}
