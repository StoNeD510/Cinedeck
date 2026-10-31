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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Subtitles
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
import kotlinx.coroutines.withTimeoutOrNull
import com.cinedeck.app.ui.C as UiC

class PlayerActivity : ComponentActivity() {
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
    var kind by remember { mutableStateOf(if (req.engine == "vlc") "vlc" else "exo") }
    var subs by remember { mutableStateOf<List<Subtitle>?>(null) }
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

    // Subtitles from addons (OpenSubtitles etc.), filtered to preferred languages.
    LaunchedEffect(req.videoId) {
        subs = if (settings.autoSubtitles && req.type != "other") {
            withTimeoutOrNull(6_000) { runCatching { Addons.subtitles(req.type, req.videoId) }.getOrDefault(emptyList()) }.orEmpty()
                .filter { s -> langs.isEmpty() || langs.any { l -> s.lang.equals(l, true) || s.lang.startsWith(l.take(2), true) } }
                .distinctBy { it.url }.take(12)
        } else emptyList()
    }

    fun save() {
        val e = engine ?: return
        if (e.duration > 0) Store.saveProgress(req, e.position, e.duration)
    }

    fun switchEngine(to: String, reason: String? = null) {
        val e = engine
        if (e != null && e.position > 0) startAt = e.position
        save()
        error = null
        buffering = true
        reason?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
        kind = to
    }

    if (subs == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = UiC.Accent) }
        return
    }

    key(kind) {
        DisposableEffect(Unit) {
            val e: Engine = if (kind == "vlc") VlcEngine(ctx) else ExoEngine(ctx)
            e.onError = { msg ->
                if (kind == "exo" && req.engine == "auto") switchEngine("vlc", "ExoPlayer couldn't play this, switching to VLC")
                else error = msg
            }
            e.onEnded = { ended = true; controls = true; save() }
            engine = e
            engineKind = kind
            e.start(req.url, req.headers, subs!!, startAt, langs)
            e.setResize(resize)
            onDispose {
                if (e.duration > 0) Store.saveProgress(req, e.position, e.duration)
                (e.view.parent as? android.view.ViewGroup)?.removeView(e.view)
                e.release()
                if (engine === e) engine = null
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
                if (!started && e.isPlaying && e.position > 300) started = true
            }
            if (++tick % 20 == 0) save()
            if (controls && panel == null && playing && draftMs < 0 && System.currentTimeMillis() - lastInput > 4500) controls = false
        }
    }
    LaunchedEffect(controls) {
        if (controls) kotlinx.coroutines.delay(50)
        runCatching {
            when {
                controls && focusScrub -> { scrubFocus.requestFocus(); focusScrub = false }
                controls -> playFocus.requestFocus()
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
    LaunchedEffect(prefetch || showNext, nextVideo) {
        if ((prefetch || showNext) && nextVideo != null && nextState == "idle") {
            nextState = "finding"
            nextReq = runCatching { NextEpisode.resolve(req, nextVideo!!) }.getOrNull()
            nextState = if (nextReq != null) "ready" else "none"
        }
    }
    fun playNext() {
        val n = nextReq ?: return
        engine?.let { e -> if (e.duration > 0) Store.saveProgress(req, e.duration, e.duration) }
        PlayerLauncher.play(ctx, n.copy(engine = Store.settings.engine))
        onExit()
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
        when {
            showNext && !ended -> nextDismissed = true
            panel != null -> panel = null
            controls && error == null -> controls = false
            else -> { save(); onExit() }
        }
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
                    AKeyEvent.KEYCODE_DPAD_LEFT, AKeyEvent.KEYCODE_DPAD_RIGHT -> if (started && !controls) {
                        val d = if (ev.key.nativeKeyCode == AKeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
                        draftMs = (pos + d).coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE)
                        focusScrub = true; controls = true; true
                    } else false
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_DPAD_DOWN ->
                        if (!started) true else if (!controls) { controls = true; true } else false
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                lastInput = System.currentTimeMillis(); controls = !controls; if (!controls) panel = null
            },
    ) {
        if (started && buffering && error == null) CircularProgressIndicator(color = UiC.Accent, modifier = Modifier.align(Alignment.Center).size(56.dp))
        if (!started) LoadingSplash(req, kind)

        AnimatedVisibility(started && controls && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CtlButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { save(); onExit() }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(req.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(
                            req.season?.let { epCode(it, req.episode) + (req.episodeTitle?.let { t -> " · $t" } ?: "") },
                            engine?.let { "${it.label}  ${it.videoInfo()}" },
                        ).joinToString("   ")
                        Text(sub, color = UiC.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Centre transport
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    CtlButton(Icons.Default.Replay10, "Back 10 seconds", big = true) { seekBy(-10_000) }
                    CtlButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", big = true, modifier = Modifier.focusRequester(playFocus)) {
                        if (ended) { ended = false; engine?.seekTo(0); engine?.play() } else toggle()
                    }
                    CtlButton(Icons.Default.Forward10, "Forward 10 seconds", big = true) { seekBy(10_000) }
                }
                // Bottom bar
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(if (draftMs >= 0) draftMs else pos), color = Color.White, fontSize = 13.sp)
                        ScrubBar(
                            pos = pos, dur = dur, draftMs = draftMs,
                            onDraft = { draftMs = it; lastInput = System.currentTimeMillis() },
                            onCommit = { commitScrub() },
                            focus = scrubFocus,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(fmt(dur), color = Color.White, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CtlChip(Icons.Default.Audiotrack, "Audio") { panel = "audio" }
                        CtlChip(Icons.Default.Subtitles, "Subtitles") { panel = "text" }
                        CtlChip(Icons.Default.AspectRatio, PictureModes[resize]) { panel = "picture" }
                        CtlChip(Icons.Default.SwapHoriz, if (kind == "exo") "Switch to VLC" else "Switch to ExoPlayer") {
                            switchEngine(if (kind == "exo") "vlc" else "exo")
                        }
                    }
                }
            }
        }

        // Audio / subtitle / picture pickers open in their own window so the remote moves into them.
        panel?.let { p ->
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
            fallbackImage = req.background, modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp),
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
                    CtlChip(Icons.Default.SwapHoriz, if (kind == "exo") "Try VLC" else "Try ExoPlayer", Modifier.focusRequester(errFocus)) { switchEngine(if (kind == "exo") "vlc" else "exo") }
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

@Composable
private fun CtlButton(icon: ImageVector, desc: String, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), modifier = modifier, focusScale = 1.15f) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.background(Color(0x55000000)).padding(if (big) 12.dp else 8.dp).size(if (big) 44.dp else 24.dp))
    }
}

@Composable
private fun CtlChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.08f, modifier = modifier) {
        Row(Modifier.background(Color(0x40FFFFFF)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 13.sp)
        }
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
        Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { onDismiss() }, contentAlignment = Alignment.CenterEnd) {
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
