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
    var controls by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf<String?>(null) } // audio | text
    var resize by remember { mutableIntStateOf(0) }
    var seekDraft by remember { mutableFloatStateOf(-1f) }
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
            e.start(req.url, req.headers, subs!!, startAt, langs)
            e.setResize(resize)
            onDispose {
                if (e.duration > 0) Store.saveProgress(req, e.position, e.duration)
                e.release()
                if (engine === e) engine = null
            }
        }
        val e = engine
        if (e != null) AndroidView(factory = { e.view }, modifier = Modifier.fillMaxSize().background(Color.Black))
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
                if (seekDraft < 0) pos = e.position
                dur = e.duration
                playing = e.isPlaying
                buffering = e.isBuffering
            }
            if (++tick % 20 == 0) save()
            if (controls && panel == null && playing && System.currentTimeMillis() - lastInput > 4500) controls = false
        }
    }
    LaunchedEffect(controls) {
        runCatching { if (controls) playFocus.requestFocus() else rootFocus.requestFocus() }
    }

    fun toggle() { engine?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(d: Long) { engine?.let { it.seekTo((it.position + d).coerceIn(0, if (it.duration > 0) it.duration else Long.MAX_VALUE)); pos = it.position } }

    BackHandler {
        when {
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
                when (ev.key.nativeKeyCode) {
                    AKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AKeyEvent.KEYCODE_SPACE -> { toggle(); true }
                    AKeyEvent.KEYCODE_MEDIA_PLAY -> { engine?.play(); true }
                    AKeyEvent.KEYCODE_MEDIA_PAUSE -> { engine?.pause(); true }
                    AKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); true }
                    AKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
                    AKeyEvent.KEYCODE_DPAD_LEFT -> if (!controls) { seekBy(-10_000); controls = true; true } else false
                    AKeyEvent.KEYCODE_DPAD_RIGHT -> if (!controls) { seekBy(10_000); controls = true; true } else false
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_DPAD_UP, AKeyEvent.KEYCODE_DPAD_DOWN ->
                        if (!controls) { controls = true; true } else false
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                lastInput = System.currentTimeMillis(); controls = !controls; if (!controls) panel = null
            },
    ) {
        if (buffering && error == null) CircularProgressIndicator(color = UiC.Accent, modifier = Modifier.align(Alignment.Center).size(56.dp))

        AnimatedVisibility(controls && error == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
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
                        Text(fmt(if (seekDraft >= 0) (seekDraft * dur).toLong() else pos), color = Color.White, fontSize = 13.sp)
                        Slider(
                            value = if (seekDraft >= 0) seekDraft else if (dur > 0) pos.toFloat() / dur else 0f,
                            onValueChange = { seekDraft = it; lastInput = System.currentTimeMillis() },
                            onValueChangeFinished = { engine?.seekTo((seekDraft * dur).toLong()); pos = (seekDraft * dur).toLong(); seekDraft = -1f },
                            colors = SliderDefaults.colors(thumbColor = UiC.Accent, activeTrackColor = UiC.Accent, inactiveTrackColor = Color(0x55FFFFFF)),
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(fmt(dur), color = Color.White, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CtlChip(Icons.Default.Audiotrack, "Audio") { panel = "audio" }
                        CtlChip(Icons.Default.Subtitles, "Subtitles") { panel = "text" }
                        CtlChip(Icons.Default.AspectRatio, listOf("Fit", "Zoom", "Stretch")[resize]) { resize = (resize + 1) % 3; engine?.setResize(resize) }
                        CtlChip(Icons.Default.SwapHoriz, if (kind == "exo") "Switch to VLC" else "Switch to ExoPlayer") {
                            switchEngine(if (kind == "exo") "vlc" else "exo")
                        }
                    }
                }
            }
        }

        // Track picker
        panel?.let { p ->
            val e = engine
            val opts = if (e == null) emptyList() else if (p == "audio") e.audioTracks() else e.textTracks()
            Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable { panel = null }, contentAlignment = Alignment.CenterEnd) {
                Column(Modifier.fillMaxHeight().widthIn(max = 380.dp).background(UiC.Surface).padding(16.dp)) {
                    Text(if (p == "audio") "Audio" else "Subtitles", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LazyColumn {
                        if (p == "text") item { TrackRow("Off", opts.none { it.selected }) { e?.selectText(null); panel = null } }
                        items(opts) { o -> TrackRow(o.label, o.selected) { if (p == "audio") e?.selectAudio(o.id) else e?.selectText(o.id); panel = null } }
                        if (opts.isEmpty()) item { Text("No tracks found yet", color = UiC.Muted, fontSize = 13.sp) }
                    }
                }
            }
        }

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
                    CtlChip(Icons.Default.SwapHoriz, if (kind == "exo") "Try VLC" else "Try ExoPlayer") { switchEngine(if (kind == "exo") "vlc" else "exo") }
                    CtlChip(Icons.Default.PlayArrow, "External player") { PlayerLauncher.external(ctx, req) }
                    CtlChip(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onExit() }
                }
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
private fun CtlChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.08f) {
        Row(Modifier.background(Color(0x40FFFFFF)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), focusScale = 1.02f) {
        Row(Modifier.fillMaxWidth().background(if (selected) UiC.Surface2 else Color.Transparent).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, null, tint = UiC.Accent)
        }
    }
}
