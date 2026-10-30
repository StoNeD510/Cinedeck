package com.cinedeck.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinedeck.app.data.AddonEntry
import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.RealDebrid
import com.cinedeck.app.data.Store
import kotlinx.coroutines.launch

@Composable
private fun SettingsHeader(t: String) =
    Text(t, color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))

@Composable
private fun field() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Surface2, focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
)

@Composable
fun SettingsScreen(onRdFiles: () -> Unit) {
    val ctx = LocalContext.current
    val data by Store.data.collectAsState()
    val scope = rememberCoroutineScope()
    var newAddon by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf(data.settings.rdToken) }
    var rdStatus by remember { mutableStateOf<String?>(null) }
    var langs by remember { mutableStateOf(data.settings.subtitleLangs) }

    LazyColumn(Modifier.fillMaxSize().widthIn(max = 900.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Settings", color = C.Text, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(16.dp)) }

        // ---------- Addons ----------
        item { SettingsHeader("ADDONS") }
        item {
            Text(
                "Paste any Stremio addon URL (the same link Stremio's “Share addon” gives you). Your Real-Debrid addon goes here too. " +
                    "You can paste several, one per line.",
                color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    newAddon, { newAddon = it }, placeholder = { Text("https://…/manifest.json") }, colors = field(),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(), maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroButton(if (adding) "Installing…" else "Install", null, primary = true) {
                        val urls = newAddon.lines().map { it.trim() }.filter { it.isNotBlank() }
                        if (urls.isEmpty() || adding) return@HeroButton
                        adding = true
                        scope.launch {
                            var ok = 0
                            val failed = mutableListOf<String>()
                            for (u in urls) {
                                val norm = Addons.normalize(u)
                                runCatching { Addons.manifest(norm) }
                                    .onSuccess { m ->
                                        val cur = Store.data.value.addons.filterNot { Addons.normalize(it.url) == norm }
                                        Store.setAddons(cur + AddonEntry(norm, m)); ok++
                                    }
                                    .onFailure { failed += u.take(40) }
                            }
                            adding = false
                            if (failed.isEmpty()) newAddon = ""
                            HomeData.load(force = true)
                            Toast.makeText(ctx, "Installed $ok" + if (failed.isNotEmpty()) ", failed: ${failed.joinToString()}" else "", Toast.LENGTH_LONG).show()
                        }
                    }
                    HeroButton("Refresh all", null) {
                        scope.launch { Addons.refreshManifests(force = true); HomeData.load(force = true); Toast.makeText(ctx, "Addons refreshed", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        }
        itemsIndexed(data.addons, key = { _, a -> a.url }) { i, a ->
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(C.Surface, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (a.manifest?.logo != null) AsyncImage(a.manifest.logo, null, modifier = Modifier.size(36.dp))
                else if (a.manifest == null) Icon(Icons.Default.Warning, "Not loaded", tint = C.New, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.manifest?.name ?: "Couldn't load", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    val res = a.manifest?.resources?.joinToString { r -> r.toString().substringAfter("\"name\":\"").substringBefore("\"").trim('"') }
                    Text(listOfNotNull(a.manifest?.version?.let { "v$it" }, res).joinToString(" · "), color = C.Muted, fontSize = 11.sp, maxLines = 1)
                    Text(a.url.replace(Regex("(realdebrid|debrid|apikey|token)=[^|/&]+", RegexOption.IGNORE_CASE), "$1=•••"), color = C.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(Icons.Default.KeyboardArrowUp, "Move up") {
                    if (i > 0) Store.setAddons(data.addons.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) })
                }
                IconBtn(Icons.Default.KeyboardArrowDown, "Move down") {
                    if (i < data.addons.lastIndex) Store.setAddons(data.addons.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) })
                }
                IconBtn(Icons.Default.Delete, "Remove") { Store.setAddons(data.addons.filterNot { it.url == a.url }) }
            }
        }

        // ---------- Real-Debrid ----------
        item { SettingsHeader("REAL-DEBRID") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Optional. Your API token (from real-debrid.com, under “API token”) lets CineDeck show your account status and play files already in your Real-Debrid downloads.",
                    color = C.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    token, { token = it }, placeholder = { Text("API token") }, singleLine = true, colors = field(),
                    visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HeroButton("Save & check", null, primary = true) {
                        val clean = RealDebrid.clean(token)
                        token = clean
                        Store.updateSettings { it.copy(rdToken = clean) }
                        rdStatus = "Checking…"
                        scope.launch {
                            rdStatus = runCatching { RealDebrid.user(clean) }.fold(
                                { u -> "Signed in as ${u.username} · ${u.type}" + (u.expiration?.let { " until ${it.take(10)}" } ?: "") },
                                { e ->
                                    if (e.message?.contains("401") == true)
                                        "Real-Debrid rejected this token (${clean.length} characters; a valid one is usually 52). Copy it again from the API token page, and if you pressed Reset there, use the new one."
                                    else "Couldn't reach Real-Debrid: ${e.message}"
                                },
                            )
                        }
                    }
                    if (data.settings.rdToken.isNotBlank()) HeroButton("My RD files", null) { onRdFiles() }
                }
                rdStatus?.let { Text(it, color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }

        // ---------- Player ----------
        item { SettingsHeader("PLAYER") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Default player", color = C.Text, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "exo" to "ExoPlayer", "vlc" to "VLC", "external" to "External").forEach { (k, l) ->
                        Pill(l, data.settings.engine == k) { Store.updateSettings { it.copy(engine = k) } }
                    }
                }
                Text("Auto starts with ExoPlayer and switches to VLC by itself if a file won't play.", color = C.Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Load subtitles from addons", color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        data.settings.autoSubtitles, { v -> Store.updateSettings { it.copy(autoSubtitles = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.Black),
                    )
                }
                OutlinedTextField(
                    langs, { langs = it; Store.updateSettings { s -> s.copy(subtitleLangs = it) } },
                    label = { Text("Subtitle languages (e.g. eng, spa, tgl)") }, singleLine = true, colors = field(),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---------- Data ----------
        item { SettingsHeader("DATA") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var confirm by remember { mutableStateOf(false) }
                HeroButton(if (confirm) "Tap again to clear" else "Clear watch history", null) {
                    if (confirm) { Store.clearHistory(); confirm = false; Toast.makeText(ctx, "History cleared", Toast.LENGTH_SHORT).show() } else confirm = true
                }
            }
        }
        item { Text("CineDeck 0.1.2", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50)) {
        Icon(icon, desc, tint = C.Muted, modifier = Modifier.padding(6.dp).size(22.dp))
    }
}
