package com.cinedeck.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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
    val followedKey = remember(data.library, data.progress) { (data.library.map { it.id } + data.progress.values.filter { it.type == "series" }.map { it.metaId }).toSet() }
    LaunchedEffect(followedKey) { HomeData.loadFollowed() }

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

    // Hero pages: what you're in the middle of first (Resume), then the featured titles.
    val heroPages = remember(hero, continueItems, newEpisodes, followed) {
        val resume = continueItems.take(2).map { c ->
            val m = followed[c.metaId]
            HeroPage(
                kicker = if (c.isUpNext) "UP NEXT" else "CONTINUE WATCHING", title = c.name, image = m?.background ?: c.image ?: c.poster, logo = m?.logo,
                line = listOfNotNull(
                    c.season?.let { epCode(it, c.episode) + (c.episodeTitle?.let { t -> "  ·  $t" } ?: "") },
                    if (c.fraction > 0.02f) "${(c.fraction * 100).toInt()}% watched" else null,
                ).joinToString("   ·   "),
                description = null, progress = c.fraction, type = c.type, metaId = c.metaId, videoId = c.videoId, primary = "Resume", meta = m,
            )
        }
        val fresh = newEpisodes.take(3).map { e ->
            HeroPage(
                kicker = "NEW EPISODE", title = e.meta.name, image = e.meta.background ?: e.video.thumbnail, logo = e.meta.logo,
                line = epCode(e.video.season, e.video.ep) + "  ·  " + e.video.label, description = null, progress = 0f,
                type = "series", metaId = e.meta.id, videoId = e.video.id, primary = "Play", meta = e.meta,
            )
        }
        val featured = hero.map { m ->
            HeroPage(
                kicker = if (m.type == "series") "FEATURED SERIES" else "FEATURED", title = m.name, image = m.background, logo = m.logo,
                line = listOfNotNull(m.imdbRating?.let { "★ $it" }, m.yearText, m.allGenres.take(3).joinToString(" · ").ifBlank { null }).joinToString("   ·   "),
                description = m.description, progress = 0f, type = m.type, metaId = m.id, videoId = if (m.type == "movie") m.id else null, primary = "Play", meta = m,
            )
        }
        (resume + fresh + featured).distinctBy { it.metaId }.take(8)
    }

    Screen("home") {
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 56.dp else 16.dp, vertical = if (wide) 2.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!wide) Box(Modifier.padding(end = 12.dp)) { Logo(20.sp, mark = false) }
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
        } else if (heroPages.isNotEmpty()) {
            item { Hero(heroPages, onOpen, onPlay, tv || wide) }
        }

        if (continueItems.isNotEmpty()) {
            item { SectionHeader("Continue Watching") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continueItems, key = { it.videoId }) { c ->
                        WideCard(
                            image = c.image ?: c.poster, title = c.name,
                            subtitle = if (c.season != null) "${epCode(c.season, c.episode)} · ${c.episodeTitle ?: ""}" else null,
                            badge = if (c.isUpNext) "UP NEXT" else null, badgeColor = C.Cyan, progress = c.fraction,
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
}
}

/** One page of the hero carousel: either something to resume or a featured title. */
data class HeroPage(
    val kicker: String, val title: String, val image: String?, val logo: String?, val line: String, val description: String?,
    val progress: Float, val type: String, val metaId: String, val videoId: String?, val primary: String, val meta: Meta?,
)


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(items: List<HeroPage>, onOpen: (String, String) -> Unit, onPlay: (String, String, String) -> Unit, wide: Boolean) {
    val pager = rememberPagerState { items.size }
    LaunchedEffect(pager, items.size) {
        while (true) {
            delay(8000)
            if (!pager.isScrollInProgress && items.isNotEmpty()) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
    }
    val shape = RoundedCornerShape(if (wide) 14.dp else 0.dp)
    // A wide banner across the top, about a third of the screen tall. The whole tile opens the title's details.
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val heroH = (conf.screenHeightDp.dp * 0.36f).coerceIn(170.dp, 260.dp)
    Box(Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 0.dp, vertical = if (wide) 8.dp else 0.dp)) {
        Box((if (wide) Modifier.fillMaxWidth().height(heroH) else Modifier.fillMaxWidth().aspectRatio(0.72f)).clip(shape)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                val m = items[page]
                FocusBox(onClick = { onOpen(m.type, m.metaId) }, shape = shape, focusScale = 1.0f, modifier = Modifier.fillMaxSize(), memoryKey = "hero:" + m.metaId) {
                    Box(Modifier.fillMaxSize().background(C.Surface)) {
                        AsyncImage(
                            model = if (wide) m.image else (m.meta?.poster ?: m.image), contentDescription = m.title,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            alignment = if (wide) Alignment.TopCenter else Alignment.Center,
                        )
                        Box(Modifier.fillMaxSize().background(if (wide) LeftFade else BottomFade))
                        Column(
                            Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
                                .padding(horizontal = if (wide) 26.dp else 16.dp, vertical = if (wide) 20.dp else 16.dp)
                                .then(if (wide) Modifier.widthIn(max = 560.dp) else Modifier.fillMaxWidth()),
                            horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally,
                        ) {
                            Text(
                                m.kicker, color = if (m.kicker.startsWith("NEW")) C.Bg else Color.White, fontSize = 11.sp, fontFamily = Display, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                                modifier = Modifier.background(if (m.kicker.startsWith("NEW")) C.Cyan else Color(0x66000000), RoundedCornerShape(50))
                                    .border(1.dp, if (m.kicker.startsWith("NEW")) C.Cyan else Color(0x55FFFFFF), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            if (m.logo != null) AsyncImage(m.logo, m.title, contentScale = ContentScale.Fit, modifier = Modifier.height(if (wide) 56.dp else 56.dp).widthIn(max = 300.dp))
                            else Text(m.title, color = Color.White, fontFamily = Display, fontSize = if (wide) 30.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, lineHeight = if (wide) 34.sp else 30.sp, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(m.line, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (m.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(m.progress).height(3.dp).background(C.Accent))
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(items.size) { i ->
                    Box(Modifier.size(if (i == pager.currentPage) 16.dp else 6.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i == pager.currentPage) C.Accent else Color(0x66FFFFFF)))
                }
            }
        }
    }
}

@Composable
fun HeroButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(10.dp), focusScale = 1.08f, modifier = modifier) {
        Row(
            Modifier.heightIn(min = 44.dp).background(if (primary) C.Accent else Color.Transparent)
                .then(if (primary) Modifier else Modifier.border(1.dp, C.Line, RoundedCornerShape(10.dp)))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(label, color = Color.White, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, softWrap = false)
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
        FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), memoryKey = "w:" + title + subtitle) {
            AsyncImage(image, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(C.Surface2))
            Box(Modifier.fillMaxSize().background(BottomFade))
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp).background(Color(0x88000000), RoundedCornerShape(50)).padding(4.dp))
            badge?.let { Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { Tag(it, badgeColor, if (badgeColor == C.Cyan) C.Bg else Color.White) } }
            if (progress > 0f) LinearProgressIndicator(
                progress = { progress }, color = C.Cyan, trackColor = Color(0x66000000),
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
