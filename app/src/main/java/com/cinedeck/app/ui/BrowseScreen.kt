package com.cinedeck.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Meta
import com.cinedeck.app.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** One catalog + genre, loaded page by page. Kept per filter so switching back is instant. */
@Stable
class CatalogPager(val ref: Addons.CatalogRef, val genre: String?) {
    val items = mutableStateListOf<Meta>()
    var loading by mutableStateOf(false)
    var done by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    suspend fun loadMore() {
        if (loading || done) return
        loading = true
        try {
            val extra = buildMap {
                genre?.let { put("genre", it) }
                if (items.isNotEmpty()) put("skip", items.size.toString())
            }
            val page = Addons.catalog(ref, extra)
            val fresh = page.filter { m -> items.none { it.id == m.id } }
            if (fresh.isEmpty() || !ref.def.supportsExtra("skip")) done = true
            items.addAll(fresh)
            error = null
        } catch (e: CancellationException) {
            throw e // switching filters: leave this page unloaded so it retries next time
        } catch (e: Exception) {
            error = e.message ?: "Failed to load"
            if (items.isEmpty()) done = true
        } finally {
            loading = false
        }
    }

    fun retry() { done = false; error = null }
}

private object BrowseCache {
    private val map = LinkedHashMap<String, CatalogPager>()
    fun get(ref: Addons.CatalogRef, genre: String?): CatalogPager {
        val k = ref.key + "|" + (genre ?: "")
        val existing = map[k]
        if (existing != null && existing.error == null) return existing
        return CatalogPager(ref, genre).also {
            map[k] = it
            if (map.size > 30) map.remove(map.keys.first())
        }
    }
}

private fun typeLabel(t: String) = when (t) { "movie" -> "Movies"; "series" -> "TV Shows"; else -> t.replaceFirstChar { it.uppercase() } }

/** One long scrollable page of everything in a catalog, loading more as you scroll. Filters stay tucked away. */
@Composable
fun BrowseScreen(initialKey: String, initialType: String, onOpen: (String, String) -> Unit) {
    val data by Store.data.collectAsState()
    val all = remember(data.addons) { Addons.catalogs().filter { !it.def.requires("search") } }
    var type by rememberSaveable { mutableStateOf(initialType.ifBlank { "movie" }) }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Meta?>(null) }

    val types = all.map { it.def.type }.distinct().sortedBy { when (it) { "movie" -> 0; "series" -> 1; else -> 2 } }
    // Catalogs whose only required filter is genre can be browsed here.
    val forType = all.filter { c ->
        c.def.type == type &&
            c.def.extra.orEmpty().none { e -> e.isRequired && e.name != "genre" } &&
            c.def.extraRequired.orEmpty().all { it == "genre" }
    }
    val cat = forType.firstOrNull { it.key == key } ?: forType.firstOrNull { !it.def.requiresExtra() } ?: forType.firstOrNull()
    val genres = cat?.def?.genres().orEmpty()
    // Keep the chosen genre only if this catalog offers it; required-genre catalogs fall back to their first option.
    val effectiveGenre = when {
        genre != null && genre in genres -> genre
        cat?.def?.requires("genre") == true -> genres.firstOrNull()
        else -> null
    }
    val pager = remember(cat?.key, effectiveGenre) { cat?.let { BrowseCache.get(it, effectiveGenre) } }

    val grid = rememberLazyGridState()
    LaunchedEffect(pager) {
        grid.scrollToItem(0)
        if (pager?.items?.isEmpty() == true) pager.loadMore()
    }
    // Load the next page whenever the end of the grid comes into view.
    LaunchedEffect(pager) {
        val p = pager ?: return@LaunchedEffect
        snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 12 && !p.loading && !p.done && p.error == null
        }.distinctUntilChanged().filter { it }.collect { p.loadMore() }
    }

    menuFor?.let { m -> TitleActions(m, onOpen = { onOpen(m.type, m.id) }, onDismiss = { menuFor = null }) }
    val watched = watchedTitleIds()

    LazyVerticalGrid(
        columns = GridCells.Fixed(postersAcross()), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Browse", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text(
                            listOfNotNull(typeLabel(type), cat?.def?.name, effectiveGenre ?: "All genres").joinToString("  ·  "),
                            color = C.Muted, fontSize = 13.sp,
                        )
                    }
                    FocusBox(onClick = { filtersOpen = !filtersOpen }, shape = RoundedCornerShape(50), focusScale = 1.05f) { f ->
                        Row(
                            Modifier.background(if (filtersOpen || f) C.Surface2 else C.Surface).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (filtersOpen) Icons.Default.ExpandLess else Icons.Default.Tune, null, tint = C.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (filtersOpen) "Done" else "Filters", color = C.Text, fontSize = 14.sp)
                        }
                    }
                }
                AnimatedVisibility(filtersOpen) {
                    Column(
                        Modifier.fillMaxWidth().background(C.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterLine("Type") {
                            items(types) { t -> Pill(typeLabel(t), type == t) { if (type != t) { type = t; key = ""; genre = null } } }
                        }
                        if (forType.size > 1) FilterLine("Catalog") {
                            items(forType, key = { it.key }) { c -> Pill(c.def.name ?: c.def.id, c.key == cat?.key) { key = c.key } }
                        }
                        if (genres.isNotEmpty()) FilterLine("Genre") {
                            if (cat?.def?.requires("genre") != true) item { Pill("All", effectiveGenre == null) { genre = null; filtersOpen = false } }
                            items(genres) { g -> Pill(g, g == effectiveGenre) { genre = g; filtersOpen = false } }
                        }
                    }
                }
                if (!filtersOpen && LocalIsTv.current) Text("Tip: hold OK on a poster to mark it watched or add it to My List.", color = C.Muted, fontSize = 11.sp)
            }
        }
        val list = pager?.items.orEmpty()
        items(list, key = { it.type + it.id }) { m ->
            PosterCard(
                m, { onOpen(m.type, m.id) }, width = null, watched = m.id in watched,
                modifier = Modifier.fillMaxWidth(), focusScale = 1.05f, onLongClick = { menuFor = m },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                cat == null -> Message("No catalogs", "Add a catalog addon in Settings.")
                pager?.loading == true -> Loading(Modifier.fillMaxWidth().padding(16.dp))
                pager?.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Message("Couldn't load this catalog", pager.error)
                    Pill("Try again", false) { pager.retry() }
                }
                pager?.done == true && list.isEmpty() -> Message("Nothing here")
                else -> Spacer(Modifier.size(4.dp))
            }
        }
    }
}

@Composable
private fun FilterLine(label: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Suppress("unused")
private val keepColor = Color.Transparent
