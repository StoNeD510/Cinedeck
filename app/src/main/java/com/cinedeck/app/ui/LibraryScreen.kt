package com.cinedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinedeck.app.data.Meta
import com.cinedeck.app.data.Store
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(onOpen: (String, String) -> Unit, onPlay: (String, String, String) -> Unit) {
    val data by Store.data.collectAsState()
    var tab by rememberSaveable { mutableStateOf("list") }
    var type by rememberSaveable { mutableStateOf("all") }
    val minSize = if (LocalIsTv.current) 140.dp else if (LocalWide.current) 130.dp else 105.dp

    val list = data.library.filter { type == "all" || it.type == type }
    // History: one entry per title, most recent first.
    val history = data.progress.values.filter { type == "all" || it.type == type }
        .groupBy { it.metaId }.map { (_, v) -> v.maxBy { it.updatedAt } }.sortedByDescending { it.updatedAt }

    LazyVerticalGrid(
        columns = if (tab == "list") GridCells.Adaptive(minSize) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(if (tab == "list") 16.dp else 8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("My Stuff", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("My List (${data.library.size})", tab == "list") { tab = "list" }
                    Pill("Watch History", tab == "history") { tab = "history" }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("All", type == "all") { type = "all" }
                    Pill("Movies", type == "movie") { type = "movie" }
                    Pill("TV Shows", type == "series") { type = "series" }
                }
            }
        }
        if (tab == "list") {
            if (list.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Message("Your list is empty", "Use “My List” on any title to save it here. Shows you save also feed the Upcoming calendar.") }
            items(list, key = { it.id }) { li ->
                val watched = data.progress[li.id]?.watched == true
                PosterCard(Meta(li.id, li.type, li.name, li.poster, li.background), { onOpen(li.type, li.id) }, width = null, watched = watched, modifier = Modifier.fillMaxWidth())
            }
        } else {
            if (history.isEmpty()) item { Message("Nothing watched yet") }
            items(history, key = { it.metaId }) { p ->
                val watchedCount = data.progress.values.count { it.metaId == p.metaId && it.watched }
                FocusBox(onClick = { onOpen(p.type, p.metaId) }, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) {
                    Row(Modifier.fillMaxWidth().background(C.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(p.background ?: p.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).background(C.Surface2, RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.season != null) Text("Last: ${epCode(p.season, p.episode)} ${p.episodeTitle ?: ""}", color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (p.type == "series") "$watchedCount episodes watched · " else if (p.watched) "Watched · " else "") +
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt)),
                                color = C.Muted, fontSize = 12.sp,
                            )
                            if (!p.watched && p.fraction > 0f) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(progress = { p.fraction }, color = C.Accent, trackColor = C.Surface2, modifier = Modifier.fillMaxWidth(0.7f).height(3.dp))
                            }
                        }
                        if (p.watched && p.type == "movie") Icon(Icons.Default.CheckCircle, null, tint = C.Accent)
                        FocusBox(onClick = { Store.removeProgressForMeta(p.metaId) }, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Default.Close, "Remove from history", tint = C.Muted, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun unusedPlay(onPlay: (String, String, String) -> Unit) = onPlay
