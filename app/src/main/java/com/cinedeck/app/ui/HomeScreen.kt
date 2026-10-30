package com.cinedeck.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Meta
import com.cinedeck.app.data.Store
import com.cinedeck.app.data.Tracker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Rows are kept for the life of the app so going back to Home is instant. */
object HomeData {
    data class Row(val key: String, val title: String, val type: String, val items: List<Meta>)
    val rows = MutableStateFlow<List<Row>?>(null)
    val followed = MutableStateFlow<Map<String, Meta>>(emptyMap())
    private var loadedFor = ""

    suspend fun load(force: Boolean = false) = coroutineScope {
        val cats = Addons.homeCatalogs()
        val sig = cats.joinToString { it.key }
        if (!force && sig == loadedFor && rows.value != null) return@coroutineScope
        if (cats.isEmpty()) return@coroutineScope
        loadedFor = sig
        val result = cats.map { c ->
            async { runCatching { Row(c.key, c.title, c.def.type, Addons.catalog(c)) }.getOrNull() }
        }.awaitAll().filterNotNull().filter { it.items.isNotEmpty() }
        rows.value = result
    }

    suspend fun loadFollowed() {
        val d = Store.data.value
        val metas = Addons.metas(Tracker.followedSeries(d))
        followed.value = metas.associateBy { it.id }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpen: (String, String) -> Unit,
    onPlay: (String, String, String) -> Unit,
    onSeeAll: (String, String) -> Unit,
    onSearch: () -> Unit,
) {
    val data by Store.data.collectAsState()
    val rows by HomeData.rows.collectAsState()
    val followed by HomeData.followed.collectAsState()
    var filter by rememberSaveable { mutableStateOf("all") }
    val tv = LocalIsTv.current
    val wide = LocalWide.current

    val manifestsReady = data.addons.count { it.manifest != null }
    LaunchedEffect(manifestsReady) { HomeData.load() }
    LaunchedEffect(data.library.size, data.progress.size) { HomeData.loadFollowed() }

    val shown = rows?.filter { filter == "all" || it.type == filter }.orEmpty()
    val hero = remember(shown) { shown.flatMap { it.items.take(4) }.filter { it.background != null }.distinctBy { it.id }.take(8) }
    val continueItems = remember(data.progress, followed) {
        Tracker.continueWatching(data, followed).filter { filter == "all" || it.type == filter }
    }
    val newEpisodes = remember(data.progress, followed) {
        if (filter == "movie") emptyList()
        else {
            val weekAgo = Instant.now().minus(8, ChronoUnit.DAYS)
            Tracker.calendar(data, followed.values.toList(), past = 8, future = 0)
                .filter { !it.watched && it.video.releasedInstant?.isAfter(weekAgo) == true && it.video.isAired() }
                .sortedByDescending { it.video.releasedInstant }
        }
    }
    val watchedIds = remember(data.progress) { data.progress.values.filter { it.watched && it.type == "movie" }.map { it.metaId }.toSet() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!wide) Text("CineDeck", color = C.Accent, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(end = 12.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("All", filter == "all") { filter = "all" }
                    Pill("Movies", filter == "movie") { filter = "movie" }
                    Pill("TV Shows", filter == "series") { filter = "series" }
                }
                if (!wide) FocusBox(onClick = onSearch, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Default.Search, "Search", tint = C.Text, modifier = Modifier.padding(8.dp).size(24.dp))
                }
            }
        }

        if (rows == null) {
            item { Loading(Modifier.fillMaxWidth().height(320.dp)) }
        } else if (hero.isNotEmpty()) {
            item { Hero(hero, onOpen, onPlay, tv || wide) }
        }

        if (continueItems.isNotEmpty()) {
            item { SectionHeader("Continue Watching") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueItems, key = { it.videoId }) { c ->
                        WideCard(
                            image = c.image ?: c.poster, title = c.name,
                            subtitle = if (c.season != null) "${epCode(c.season, c.episode)} · ${c.episodeTitle ?: ""}" else null,
                            badge = if (c.isUpNext) "UP NEXT" else null, progress = c.fraction,
                            onClick = { onPlay(c.type, c.metaId, c.videoId) },
                            onLongClick = { onOpen(c.type, c.metaId) },
                        )
                    }
                }
            }
        }

        if (newEpisodes.isNotEmpty()) {
            item { SectionHeader("New Episodes") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(newEpisodes, key = { it.video.id }) { e ->
                        WideCard(
                            image = e.video.thumbnail ?: e.meta.background, title = e.meta.name,
                            subtitle = "${epCode(e.video.season, e.video.ep)} · ${e.video.label}",
                            badge = "NEW", badgeColor = C.New,
                            onClick = { onPlay("series", e.meta.id, e.video.id) },
                            onLongClick = { onOpen("series", e.meta.id) },
                        )
                    }
                }
            }
        }

        if (rows != null && shown.isEmpty()) item {
            Message("Nothing to show yet", "Check your connection, or add catalog addons in Settings.")
        }

        shown.forEach { r ->
            item(key = "h_" + r.key) { SectionHeader(r.title) { onSeeAll(r.key, r.type) } }
            item(key = "r_" + r.key) { PosterRow(r.items, { onOpen(it.type, it.id) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(items: List<Meta>, onOpen: (String, String) -> Unit, onPlay: (String, String, String) -> Unit, wide: Boolean) {
    val pager = rememberPagerState { items.size }
    val data by Store.data.collectAsState()
    LaunchedEffect(pager, items.size) {
        while (true) {
            delay(7000)
            if (!pager.isScrollInProgress && items.isNotEmpty()) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    Box(Modifier.fillMaxWidth().then(if (wide) Modifier.height(300.dp) else Modifier.aspectRatio(0.82f))) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val m = items[page]
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = if (wide) m.background else (m.poster ?: m.background), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    alignment = if (wide) Alignment.TopCenter else Alignment.Center,
                )
                if (wide) Box(Modifier.fillMaxHeight().fillMaxWidth(0.65f).background(LeftFade))
                Box(Modifier.fillMaxSize().background(BottomFade))
                Column(
                    Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .then(if (wide) Modifier.widthIn(max = 520.dp) else Modifier.fillMaxWidth()),
                    horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    if (m.logo != null) AsyncImage(m.logo, m.name, contentScale = ContentScale.Fit, modifier = Modifier.height(if (wide) 90.dp else 70.dp).widthIn(max = 300.dp))
                    else Text(m.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        m.imdbRating?.let {
                            Icon(Icons.Default.Star, null, tint = C.Gold, modifier = Modifier.size(14.dp))
                            Text(it, color = Color.White, fontSize = 13.sp)
                        }
                        m.yearText?.let { Text(it, color = C.Muted, fontSize = 13.sp) }
                        Text(m.allGenres.take(3).joinToString(" · "), color = C.Muted, fontSize = 13.sp, maxLines = 1)
                    }
                    if (wide) m.description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = C.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroButton("Play", Icons.Default.PlayArrow, primary = true) {
                            if (m.type == "movie") onPlay(m.type, m.id, m.id) else onOpen(m.type, m.id)
                        }
                        val inList = data.library.any { it.id == m.id }
                        HeroButton(if (inList) "In My List" else "My List", if (inList) Icons.Default.Check else Icons.Default.Add) { Store.toggleLibrary(m) }
                        HeroButton("Details", null) { onOpen(m.type, m.id) }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(items.size) { i ->
                Box(Modifier.size(if (i == pager.currentPage) 16.dp else 6.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i == pager.currentPage) C.Accent else Color(0x66FFFFFF)))
            }
        }
    }
}

@Composable
fun HeroButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(8.dp), focusScale = 1.08f, modifier = modifier) {
        Row(
            Modifier.background(if (primary) C.Accent else Color(0x33FFFFFF)).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, null, tint = if (primary) Color.Black else Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, color = if (primary) Color.Black else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
fun WideCard(
    image: String?, title: String, subtitle: String?, onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null, badge: String? = null, badgeColor: Color = C.Accent, progress: Float = 0f,
) {
    val w = if (LocalIsTv.current) 260.dp else 220.dp
    Column(Modifier.width(w)) {
        FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(image, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(C.Surface2))
            Box(Modifier.fillMaxSize().background(BottomFade))
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp).background(Color(0x88000000), RoundedCornerShape(50)).padding(4.dp))
            badge?.let { Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { Tag(it, badgeColor, if (badgeColor == C.Accent) Color.Black else Color.White) } }
            if (progress > 0f) LinearProgressIndicator(
                progress = { progress }, color = C.Accent, trackColor = Color(0x66000000),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Suppress("unused")
private val keepHeightIn = Modifier.heightIn(min = 0.dp)
