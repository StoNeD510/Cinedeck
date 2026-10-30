package com.cinedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ArrowDropDown
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

/** Which filter's picker is open. */
private enum class Picker { TYPE, CATALOG, GENRE }

/**
 * Browse: a fixed filter bar (type, catalog, genre/year, hide watched) above a grid that stays
 * 6 across on TV. Changing a filter never leaves the grid; it just reloads under the bar.
 */
@Composable
fun BrowseScreen(initialKey: String, initialType: String, onOpen: (String, String) -> Unit) {
    val data by Store.data.collectAsState()
    val all = remember(data.addons) { Addons.catalogs().filter { !it.def.requires("search") } }
    var type by rememberSaveable { mutableStateOf(initialType.ifBlank { "movie" }) }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var hideWatched by rememberSaveable { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var menuFor by remember { mutableStateOf<Meta?>(null) }
    val dense = data.settings.browseDense
    val wideScreen = LocalWide.current

    val types = all.map { it.def.type }.distinct().sortedBy { when (it) { "movie" -> 0; "series" -> 1; else -> 2 } }
    val forType = all.filter { c ->
        c.def.type == type &&
            c.def.extra.orEmpty().none { e -> e.isRequired && e.name != "genre" } &&
            c.def.extraRequired.orEmpty().all { it == "genre" }
    }
    val cat = forType.firstOrNull { it.key == key } ?: forType.firstOrNull { !it.def.requiresExtra() } ?: forType.firstOrNull()
    val genres = cat?.def?.genres().orEmpty()
    val isYear = genres.isNotEmpty() && genres.take(5).all { g -> g.all { it.isDigit() } }
    val genreLabel = if (isYear) "Year" else "Genre"
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
    LaunchedEffect(pager) {
        val p = pager ?: return@LaunchedEffect
        snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 18 && !p.loading && !p.done && p.error == null
        }.distinctUntilChanged().filter { it }.collect { p.loadMore() }
    }

    menuFor?.let { m -> TitleActions(m, onOpen = { onOpen(m.type, m.id) }, onDismiss = { menuFor = null }) }
    val watched = watchedTitleIds()
    val list = pager?.items.orEmpty().let { l -> if (hideWatched) l.filter { it.id !in watched } else l }
    // When hiding watched titles empties the screen, keep fetching.
    LaunchedEffect(pager, hideWatched, list.size) {
        if (hideWatched && list.size < 24 && pager != null && !pager.done && !pager.loading && pager.error == null) pager.loadMore()
    }

    when (picker) {
        Picker.TYPE -> OptionPicker("Show", types.map { it to typeLabel(it) }, type) { t -> if (t != null && t != type) { type = t; key = ""; genre = null }; picker = null }
        Picker.CATALOG -> OptionPicker("Catalog", forType.map { it.key to (it.def.name ?: it.def.id) }, cat?.key) { k -> if (k != null) { key = k; genre = null }; picker = null }
        Picker.GENRE -> OptionPicker(
            genreLabel,
            (if (cat?.def?.requires("genre") == true) emptyList() else listOf("" to "All")) + genres.map { it to it },
            effectiveGenre ?: "",
            columns = if (isYear) 8 else 5,
        ) { g -> if (g != null) genre = g.ifEmpty { null }; picker = null }
        null -> {}
    }

    Column(Modifier.fillMaxSize()) {
        // ---- Fixed filter bar ----
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Browse", style = Heading, fontSize = 24.sp, modifier = Modifier.padding(end = 8.dp))
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { DropChip("Show", typeLabel(type)) { picker = Picker.TYPE } }
                if (forType.size > 1) item { DropChip("Catalog", cat?.def?.name ?: "—") { picker = Picker.CATALOG } }
                if (genres.isNotEmpty()) item { DropChip(genreLabel, effectiveGenre ?: "All") { picker = Picker.GENRE } }
                item { Pill(if (hideWatched) "Hiding watched" else "Hide watched", hideWatched) { hideWatched = !hideWatched } }
                if (wideScreen) item { Pill(if (dense) "Compact grid" else "Large grid", false) { Store.updateSettings { it.copy(browseDense = !it.browseDense) } } }
                val changed = genre != null || hideWatched || (cat != null && cat.def.requiresExtra()) || key.isNotEmpty()
                if (changed) item {
                    FocusBox(onClick = { key = ""; genre = null; hideWatched = false }, shape = RoundedCornerShape(50)) {
                        Text("Reset", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
            }
        }
        // ---- Grid ----
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (LocalWide.current) (if (dense) 8 else 6) else 3), state = grid, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (dense) 12.dp else 16.dp),
        ) {
            items(list, key = { it.type + it.id }) { m ->
                PosterCard(
                    m, { onOpen(m.type, m.id) }, width = null, watched = m.id in watched,
                    modifier = Modifier.fillMaxWidth(), focusScale = 1.05f, onLongClick = { menuFor = m }, compact = dense,
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
                    pager?.done == true && list.isEmpty() -> Message("Nothing here", if (hideWatched) "You've watched everything in this list." else null)
                    LocalIsTv.current && list.isNotEmpty() -> Text(
                        "Tip: hold OK on a poster to mark it watched or add it to My List.",
                        color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                    else -> Spacer(Modifier.size(4.dp))
                }
            }
        }
    }
}

/** A filter button showing its current value, e.g. "Genre: Comedy ▾". */
@Composable
private fun DropChip(label: String, value: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.05f) { f ->
        Row(
            Modifier.background(if (f) C.Surface2 else C.Surface).border(1.dp, if (f) C.Accent else C.Line, RoundedCornerShape(50))
                .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$label  ", color = C.Muted, fontSize = 12.sp)
            Text(value, color = C.Text, fontSize = 13.sp, fontFamily = Display, fontWeight = FontWeight.Bold, maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null, tint = C.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** Grid of choices in a dialog; the current choice gets the remote's focus. */
@Composable
private fun OptionPicker(title: String, options: List<Pair<String, String>>, selected: String?, columns: Int = 4, onPick: (String?) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onPick(null) },
        containerColor = C.Surface,
        title = { Text(title, style = Heading, fontSize = 20.sp) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (LocalWide.current) columns else 2),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(options, key = { it.first }) { (k, label) ->
                    val sel = k == (selected ?: "")
                    FocusBox(
                        onClick = { onPick(k) }, shape = RoundedCornerShape(10.dp), focusScale = 1.04f,
                        modifier = Modifier.fillMaxWidth().then(if (sel) Modifier.tvInitialFocus() else Modifier),
                    ) { f ->
                        Text(
                            label, color = if (sel) Color.White else C.Text, fontSize = 14.sp, maxLines = 1,
                            fontFamily = Display, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.fillMaxWidth().background(if (sel) C.Accent else if (f) C.Surface2 else C.Bg).padding(horizontal = 12.dp, vertical = 11.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}
