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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinedeck.app.data.Store
import com.cinedeck.app.data.Tracker
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")

private fun dayLabel(d: LocalDate): String {
    val today = Tracker.today()
    return when (ChronoUnit.DAYS.between(today, d)) {
        0L -> "Today"
        1L -> "Tomorrow"
        -1L -> "Yesterday"
        else -> d.format(dayFmt)
    }
}

/** Calendar of new and upcoming episodes for the shows you follow, by air date. */
@Composable
fun UpcomingScreen(onOpen: (String, String) -> Unit, onPlay: (String, String, String) -> Unit) {
    val data by Store.data.collectAsState()
    val followed by HomeData.followed.collectAsState()
    var loading by remember { mutableStateOf(followed.isEmpty()) }
    var mode by rememberSaveable { mutableStateOf("upcoming") }
    LaunchedEffect(data.library.size) {
        HomeData.loadFollowed()
        loading = false
    }

    val all = remember(followed, data.progress) { Tracker.calendar(data, followed.values.toList(), past = 30, future = 365) }
    val now = java.time.Instant.now()
    val entries = if (mode == "upcoming") all.filter { !it.video.isAired(now) }
    else all.filter { it.video.isAired(now) }.reversed()
    val byDay = entries.groupBy { it.video.releasedDate!! }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Episodes", style = Heading, fontSize = 24.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Coming up", mode == "upcoming") { mode = "upcoming" }
                    Pill("Recently aired", mode == "recent") { mode = "recent" }
                }
                Text("Shows in My List and shows you've watched appear here.", color = C.Muted, fontSize = 12.sp)
                val unwatched = entries.filter { !it.watched }
                if (mode == "recent" && unwatched.isNotEmpty()) {
                    HeroButton("Mark all ${unwatched.size} as watched", Icons.Default.DoneAll) {
                        unwatched.groupBy { it.meta.id }.values.forEach { list -> Store.setWatched(list.first().meta, list.map { it.video }, true) }
                    }
                }
            }
        }
        if (loading && followed.isEmpty()) item { Loading() }
        else if (followed.isEmpty()) item { Message("No shows followed yet", "Add a TV show to My List or start watching one to see its episode schedule here.") }
        else if (entries.isEmpty()) item {
            Message(if (mode == "upcoming") "No announced episodes" else "Nothing aired in the last 30 days")
        }
        byDay.forEach { (day, list) ->
            item(key = "d$day") {
                Text(dayLabel(day), color = C.Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
            }
            items(list, key = { it.video.id }) { e ->
                val aired = e.video.isAired(now)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FocusBox(
                    onClick = { if (aired) onPlay("series", e.meta.id, e.video.id) else onOpen("series", e.meta.id) },
                    onLongClick = { onOpen("series", e.meta.id) },
                    modifier = Modifier.weight(1f), focusScale = 1.02f,
                ) {
                    Row(Modifier.fillMaxWidth().background(C.Surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(e.meta.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.width(54.dp).aspectRatio(2f / 3f).background(C.Surface2))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.meta.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${epCode(e.video.season, e.video.ep)} · ${e.video.label}", color = C.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.size(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                when {
                                    e.watched -> Tag("WATCHED", C.Surface2, C.Accent)
                                    aired -> Tag("NEW", C.New)
                                    else -> Tag("UPCOMING", C.Surface2, C.Text)
                                }
                                if (e.video.ep == 1) Tag(if (e.video.season == 1) "SERIES PREMIERE" else "SEASON PREMIERE", C.AccentDim)
                            }
                        }
                    }
                }
                if (aired) {
                    Spacer(Modifier.width(8.dp))
                    FocusBox(onClick = { Store.setWatched(e.meta, listOf(e.video), !e.watched) }, shape = RoundedCornerShape(50), focusScale = 1.12f) { f ->
                        Icon(
                            if (e.watched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            if (e.watched) "Mark unwatched" else "Mark watched",
                            tint = if (e.watched || f) C.Accent else C.Muted,
                            modifier = Modifier.background(if (f) C.Surface2 else Color.Transparent).padding(10.dp).size(28.dp),
                        )
                    }
                }
                }
            }
        }
    }
}
