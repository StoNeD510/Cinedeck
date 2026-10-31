package com.cinedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Meta
import com.cinedeck.app.data.Store
import com.cinedeck.app.data.Tracker
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
fun DetailsScreen(type: String, id: String, onPlay: (String, String, String) -> Unit, onBack: () -> Unit) {
    var meta by remember { mutableStateOf(Addons.cachedMeta(type, id)) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(type, id) {
        if (meta == null) {
            meta = runCatching { Addons.meta(type, id) }.getOrNull()
            failed = meta == null
        }
    }
    val m = meta
    when {
        m != null -> DetailsContent(m, onPlay, onBack)
        failed -> Column(Modifier.fillMaxSize()) { BackBar(onBack); Message("Couldn't load details", "No installed addon returned info for this title.") }
        else -> Loading(Modifier.fillMaxSize())
    }
}

@Composable
fun BackBar(onBack: () -> Unit, title: String? = null) {
    val tv = LocalIsTv.current
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // TV remotes have a Back button; an on-screen one just steals the first OK press.
        if (!tv) FocusBox(onClick = onBack, shape = RoundedCornerShape(50)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.background(Color(0x66000000)).padding(8.dp).size(24.dp))
        }
        title?.let { Text(it, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun DetailsContent(m: Meta, onPlay: (String, String, String) -> Unit, onBack: () -> Unit) {
    val data by Store.data.collectAsState()
    val wide = LocalWide.current
    val episodes = remember(m) { Tracker.sortedEpisodes(m) }
    val seasons = remember(episodes) { episodes.mapNotNull { it.season }.distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it }) }
    val next = remember(m, data.progress) { if (m.type == "series") Tracker.nextEpisode(m, data) else null }
    var season by rememberSaveable(m.id) { mutableStateOf(next?.season ?: seasons.firstOrNull() ?: 1) }
    val inList = data.library.any { it.id == m.id }
    val movieProg = data.progress[m.id]

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth().then(if (wide) Modifier.height(380.dp) else Modifier.aspectRatio(16f / 11f))) {
                AsyncImage(m.background ?: m.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(BottomFade))
                BackBar(onBack)
                Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    if (wide) {
                        AsyncImage(m.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(150.dp).aspectRatio(2f / 3f).background(C.Surface2, RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(20.dp))
                    }
                    Column {
                        if (m.logo != null) AsyncImage(m.logo, m.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, modifier = Modifier.height(70.dp).width(300.dp))
                        else Text(m.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            m.imdbRating?.let { Icon(Icons.Default.Star, null, tint = C.Gold, modifier = Modifier.size(14.dp)); Text(it, color = Color.White, fontSize = 13.sp) }
                            m.yearText?.let { Text(it, color = C.Muted, fontSize = 13.sp) }
                            m.runtime?.let { Text(it, color = C.Muted, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                // Moving the remote back up to Play also brings the show's artwork back into view.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.onFocusChanged { f ->
                        if (f.hasFocus && listState.firstVisibleItemIndex > 0 || f.hasFocus && listState.firstVisibleItemScrollOffset > 0) {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    },
                ) {
                    if (m.type == "series") {
                        if (next != null) {
                            val partial = (data.progress[next.id]?.position ?: 0) > 0
                            HeroButton("${if (partial) "Resume" else "Play"} ${epCode(next.season, next.ep)}", Icons.Default.PlayArrow, primary = true, modifier = Modifier.tvInitialFocus(m.id)) { onPlay(m.type, m.id, next.id) }
                        }
                    } else {
                        HeroButton(if ((movieProg?.position ?: 0) > 0) "Resume" else "Play", Icons.Default.PlayArrow, primary = true, modifier = Modifier.tvInitialFocus(m.id)) { onPlay(m.type, m.id, m.id) }
                        val w = movieProg?.watched == true
                        HeroButton(if (w) "Watched" else "Mark watched", if (w) Icons.Default.CheckCircle else Icons.Default.Check) { Store.setWatched(m, emptyList(), !w) }
                    }
                    HeroButton(if (inList) "In My List" else "My List", if (inList) Icons.Default.Check else Icons.Default.Add) { Store.toggleLibrary(m) }
                }
                if (m.type == "movie" && movieProg != null && movieProg.fraction > 0f && !movieProg.watched) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { movieProg.fraction }, color = C.Cyan, trackColor = C.Surface2, modifier = Modifier.fillMaxWidth(0.5f).height(3.dp))
                }
                if (m.allGenres.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(m.allGenres.joinToString(" · "), color = C.Accent, fontSize = 13.sp)
                }
                m.description?.let { Spacer(Modifier.height(8.dp)); Text(it, color = C.Text, fontSize = 14.sp, lineHeight = 20.sp) }
                m.cast?.takeIf { it.isNotEmpty() }?.let { Spacer(Modifier.height(8.dp)); Text("Cast: " + it.take(6).joinToString(), color = C.Muted, fontSize = 12.sp) }
                m.director?.takeIf { it.isNotEmpty() }?.let { Text("Director: " + it.joinToString(), color = C.Muted, fontSize = 12.sp) }
            }
        }

        if (m.type == "series" && seasons.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { s -> Pill(if (s == 0) "Specials" else "Season $s", s == season) { season = s } }
                }
            }
            val inSeason = episodes.filter { it.season == season }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val count = inSeason.count { data.progress[it.id]?.watched == true }
                    Text("$count of ${inSeason.size} watched", color = C.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val allWatched = count == inSeason.size && inSeason.isNotEmpty()
                    FocusBox(onClick = { Store.setWatched(m, inSeason.filter { it.isAired() }, !allWatched) }, shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DoneAll, null, tint = C.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (allWatched) "Unmark season" else "Mark season watched", color = C.Text, fontSize = 13.sp)
                        }
                    }
                }
            }
            items(inSeason, key = { it.id }) { v ->
                val p = data.progress[v.id]
                val aired = v.isAired()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FocusBox(
                        onClick = { if (aired) onPlay(m.type, m.id, v.id) },
                        onLongClick = { Store.setWatched(m, listOf(v), p?.watched != true) },
                        modifier = Modifier.width(if (wide) 200.dp else 140.dp).aspectRatio(16f / 9f),
                        focusScale = 1.04f,
                    ) {
                        AsyncImage(v.thumbnail ?: m.background, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(C.Surface2))
                        if (aired) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(30.dp).background(Color(0x88000000), RoundedCornerShape(50)).padding(3.dp))
                        if (p != null && !p.watched && p.fraction > 0f) LinearProgressIndicator(progress = { p.fraction }, color = C.Cyan, trackColor = Color(0x66000000), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${v.ep ?: ""}. ${v.label}", color = if (aired) C.Text else C.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val date = v.releasedDate?.format(dateFmt)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!aired) Tag("UPCOMING", C.Surface2, C.Accent)
                            date?.let { Text(it, color = C.Muted, fontSize = 12.sp) }
                        }
                        v.summary?.let { Text(it, color = C.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    FocusBox(onClick = { Store.setWatched(m, listOf(v), p?.watched != true) }, shape = RoundedCornerShape(50)) {
                        Icon(
                            if (p?.watched == true) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            if (p?.watched == true) "Mark unwatched" else "Mark watched",
                            tint = if (p?.watched == true) C.Accent else C.Muted, modifier = Modifier.padding(8.dp).size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
