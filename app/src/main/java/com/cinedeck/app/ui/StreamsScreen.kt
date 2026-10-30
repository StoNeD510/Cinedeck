package com.cinedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Meta
import com.cinedeck.app.data.PlayRequest
import com.cinedeck.app.data.Store
import com.cinedeck.app.data.Stream
import com.cinedeck.app.data.StreamInfo
import com.cinedeck.app.player.PlayerLauncher

private data class Row_(val addonName: String, val stream: Stream, val info: StreamInfo)

@Composable
fun StreamsScreen(type: String, metaId: String, videoId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data by Store.data.collectAsState()
    var meta by remember { mutableStateOf<Meta?>(Addons.cachedMeta(type, metaId)) }
    var results by remember { mutableStateOf<List<Addons.AddonStreams>?>(null) }
    var quality by rememberSaveable { mutableStateOf("All") }
    var engine by rememberSaveable { mutableStateOf(data.settings.engine) }

    LaunchedEffect(type, metaId, videoId) {
        if (meta == null) meta = runCatching { Addons.meta(type, metaId) }.getOrNull()
        results = Addons.streams(type, videoId)
    }

    val video = meta?.videos?.firstOrNull { it.id == videoId }
    val rows = remember(results) {
        results.orEmpty().flatMap { a ->
            val name = a.addon.manifest?.name ?: "Addon"
            a.streams.filter { it.playableUrl != null || it.externalUrl != null }.map { Row_(name, it, StreamInfo.of(it)) }
        }
    }
    val p2pOnly = remember(results) { results.orEmpty().sumOf { a -> a.streams.count { it.url == null && it.infoHash != null } } }
    val errors = results.orEmpty().filter { it.error != null }
    val qualities = listOf("All", "4K", "1080p", "720p", "SD").filter { q -> q == "All" || rows.any { it.info.resolution == q } }
    val shown = rows.filter { quality == "All" || it.info.resolution == quality }

    fun play(r: Row_) {
        val s = r.stream
        val m = meta
        if (s.playableUrl == null) {
            s.externalUrl?.let { PlayerLauncher.openUrl(ctx, it) }
            return
        }
        val req = PlayRequest(
            url = s.playableUrl!!, headers = s.behaviorHints?.proxyHeaders?.request.orEmpty(),
            videoId = videoId, metaId = metaId, type = type, name = m?.name ?: metaId,
            poster = m?.poster, background = m?.background, season = video?.season, episode = video?.ep,
            episodeTitle = video?.label, engine = engine, filename = s.behaviorHints?.filename,
        )
        if (engine == "external") PlayerLauncher.external(ctx, req) else PlayerLauncher.play(ctx, req)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                AsyncImage(video?.thumbnail ?: meta?.background, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(BottomFade))
                BackBar(onBack)
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(meta?.name ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    if (video != null) Text("${epCode(video.season, video.ep)} · ${video.label}", color = C.Text, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Play with", color = C.Muted, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill("Auto", engine == "auto") { engine = "auto" } }
                    item { Pill("ExoPlayer", engine == "exo") { engine = "exo" } }
                    item { Pill("VLC", engine == "vlc") { engine = "vlc" } }
                    item { Pill("External app", engine == "external") { engine = "external" } }
                }
                if (qualities.size > 2) {
                    Text("Quality", color = C.Muted, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(qualities) { q -> Pill(q, q == quality) { quality = q } }
                    }
                }
            }
        }
        when {
            results == null -> item { Loading() ; Text("Asking your addons for streams…", color = C.Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            rows.isEmpty() -> item {
                val hasStreamAddon = data.addons.any { it.manifest?.supports("stream", type, videoId) == true }
                Message(
                    "No playable streams found",
                    when {
                        !hasStreamAddon -> "You don't have a stream addon installed. Add your Real-Debrid addon URL in Settings → Addons."
                        p2pOnly > 0 -> "$p2pOnly torrent-only results were hidden (P2P isn't supported yet). Use a debrid-configured addon."
                        else -> "Try again later or check your addons."
                    },
                )
            }
            else -> itemsIndexed(shown, key = { _, r -> r.addonName + r.stream.playableUrl + r.stream.title }) { i, r -> StreamRow(r, ::play, if (i == 0) Modifier.tvInitialFocus(shown.size) else Modifier) }
        }
        if (errors.isNotEmpty()) item {
            Text(
                errors.joinToString("\n") { "${it.addon.manifest?.name}: ${it.error}" },
                color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun StreamRow(r: Row_, onPlay: (Row_) -> Unit, focus: Modifier = Modifier) {
    FocusBox(onClick = { onPlay(r) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().then(focus), focusScale = 1.02f) {
        Row(Modifier.fillMaxWidth().background(C.Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(78.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(r.info.resolution ?: "—", color = if (r.info.rank >= 3) C.Accent else C.Text, fontSize = 17.sp, fontWeight = FontWeight.Black)
                if (r.info.cached) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Bolt, null, tint = C.Gold, modifier = Modifier.size(14.dp))
                    Text("Instant", color = C.Gold, fontSize = 10.sp)
                }
                r.info.size?.let { Text(it, color = C.Muted, fontSize = 11.sp) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.addonName + (r.stream.name?.let { " · " + it.replace("\n", " ") } ?: ""), color = C.Accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.stream.text, color = C.Text, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                if (r.info.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.info.tags.forEach { Tag(it) } }
                }
            }
            if (r.stream.playableUrl == null) Icon(Icons.Default.OpenInNew, "Opens outside the app", tint = C.Muted)
        }
    }
}

@Suppress("unused")
private val keep = RoundedCornerShape(0.dp)
