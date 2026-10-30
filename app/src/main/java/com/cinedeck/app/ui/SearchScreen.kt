package com.cinedeck.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.Meta
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(onOpen: (String, String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    var results by androidx.compose.runtime.remember { mutableStateOf<List<Pair<String, List<Meta>>>?>(null) }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }

    LaunchedEffect(q) {
        if (q.trim().length < 2) { results = null; return@LaunchedEffect }
        delay(450)
        busy = true
        results = coroutineScope {
            Addons.searchCatalogs().map { c ->
                async { runCatching { c.title.replace(c.def.name ?: "", "").trim().ifBlank { c.title } to Addons.catalog(c, mapOf("search" to q.trim())) }.getOrNull() }
            }.awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
        }
        busy = false
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Search", style = Heading, fontSize = 24.sp)
                RemoteTextField(q, { q = it }, "Search", placeholder = "Movies, TV shows…", modifier = Modifier.padding(top = 10.dp))
            }
        }
        if (busy) item { Loading() }
        results?.let { r ->
            if (r.isEmpty() && !busy) item { Message("No results for “$q”") }
            r.forEach { (title, metas) ->
                item { SectionHeader(title) }
                item { PosterRow(metas, { onOpen(it.type, it.id) }) }
            }
        }
    }
}
