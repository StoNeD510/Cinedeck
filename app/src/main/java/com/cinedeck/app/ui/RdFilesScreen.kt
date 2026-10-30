package com.cinedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinedeck.app.data.PlayRequest
import com.cinedeck.app.data.RealDebrid
import com.cinedeck.app.data.Store
import com.cinedeck.app.data.StreamInfo
import com.cinedeck.app.player.PlayerLauncher

private val videoExt = Regex("""\.(mkv|mp4|avi|m4v|mov|wmv|ts|m2ts|webm|flv|mpg|mpeg)$""", RegexOption.IGNORE_CASE)

/** Plays files already sitting in your Real-Debrid downloads list. */
@Composable
fun RdFilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<RealDebrid.Download>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { RealDebrid.downloads(Store.settings.rdToken) }
            .onSuccess { list -> files = list.filter { it.streamable == 1 || videoExt.containsMatchIn(it.filename) } }
            .onFailure { error = it.message }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar(onBack, "My Real-Debrid files") }
        when {
            error != null -> item { Message("Couldn't reach Real-Debrid", error) }
            files == null -> item { Loading() }
            files!!.isEmpty() -> item { Message("No video files in your downloads") }
            else -> items(files!!, key = { it.id }) { f ->
                FocusBox(
                    onClick = {
                        PlayerLauncher.play(
                            ctx,
                            PlayRequest(
                                url = f.download, videoId = "rd:" + f.id, metaId = "rd:" + f.id, type = "other",
                                name = f.filename, engine = Store.settings.engine, filename = f.filename,
                            ),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(), focusScale = 1.02f,
                ) {
                    Row(Modifier.fillMaxWidth().background(C.Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.filename, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(StreamInfo.formatBytes(f.filesize) + (f.generated?.let { " · " + it.take(10) } ?: ""), color = C.Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
