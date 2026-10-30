package com.cinedeck.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Meta
import com.cinedeck.app.data.Store

/** One long scrollable page of everything in a catalog, loading more as you scroll. */
@Composable
fun BrowseScreen(initialKey: String, initialType: String, onOpen: (String, String) -> Unit) {
    val data by Store.data.collectAsState()
    val all = remember(data.addons) { Addons.catalogs().filter { !it.def.requires("search") } }
    var type by rememberSaveable { mutableStateOf(initialType.ifBlank { "movie" }) }
    val forType = all.filter { it.def.type == type && it.def.extra.orEmpty().none { e -> e.isRequired && e.name != "genre" } && it.def.extraRequired.orEmpty().all { r -> r == "genre" } }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    val cat = forType.firstOrNull { it.key == key } ?: forType.firstOrNull { !it.def.requiresExtra() } ?: forType.firstOrNull()
    val genres = cat?.def?.genres().orEmpty()
    var genre by rememberSaveable(cat?.key) { mutableStateOf(if (cat?.def?.requires("genre") == true) genres.firstOrNull() else null) }

    val items = remember(cat?.key, genre) { mutableStateListOf<Meta>() }
    var loading by remember(cat?.key, genre) { mutableStateOf(false) }
    var done by remember(cat?.key, genre) { mutableStateOf(false) }
    var error by remember(cat?.key, genre) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 12 } }

    LaunchedEffect(cat?.key, genre, nearEnd, items.size) {
        if (cat == null || loading || done || !nearEnd) return@LaunchedEffect
        loading = true
        val extra = buildMap {
            genre?.let { put("genre", it) }
            if (items.isNotEmpty()) put("skip", items.size.toString())
        }
        runCatching { Addons.catalog(cat, extra) }
            .onSuccess { page ->
                val fresh = page.filter { m -> items.none { it.id == m.id } }
                if (fresh.isEmpty() || !cat.def.supportsExtra("skip")) done = true
                items.addAll(fresh)
            }
            .onFailure { error = it.message; done = true }
        loading = false
    }

    val minSize = if (LocalIsTv.current) 140.dp else if (LocalWide.current) 130.dp else 105.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Browse", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Pill("Movies", type == "movie") { type = "movie"; key = "" } }
                    item { Pill("TV Shows", type == "series") { type = "series"; key = "" } }
                    val others = all.map { it.def.type }.distinct().filter { it !in listOf("movie", "series") }
                    items(others) { t -> Pill(t.replaceFirstChar { it.uppercase() }, type == t) { type = t; key = "" } }
                }
                if (forType.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(forType, key = { it.key }) { c -> Pill(c.def.name ?: c.def.id, c.key == cat?.key) { key = c.key } }
                }
                if (genres.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cat?.def?.requires("genre") != true) item { Pill("All genres", genre == null) { genre = null } }
                    items(genres) { g -> Pill(g, g == genre) { genre = g } }
                }
            }
        }
        items(items, key = { it.type + it.id }) { m ->
            PosterCard(m, { onOpen(m.type, m.id) }, width = null, modifier = Modifier.fillMaxWidth())
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                loading -> Loading(Modifier.fillMaxWidth().padding(16.dp))
                error != null && items.isEmpty() -> Message("Couldn't load this catalog", error)
                cat == null -> Message("No catalogs", "Add a catalog addon in Settings.")
                done && items.isEmpty() -> Message("Nothing here")
                else -> Text("", modifier = Modifier.padding(4.dp))
            }
        }
    }
}
