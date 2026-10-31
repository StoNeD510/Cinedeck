package com.cinedeck.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
        item { Text("Settings", style = Heading, fontSize = 24.sp, modifier = Modifier.padding(16.dp)) }

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
                RemoteTextField(newAddon, { newAddon = it }, "Addon link", placeholder = "https://…/manifest.json", singleLine = false)
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
                    "Optional. Your API token (from real-debrid.com, under “API token”) lets ReelDJ show your account status and play files already in your Real-Debrid downloads.",
                    color = C.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.height(8.dp))
                RemoteTextField(token, { token = it }, "Real-Debrid API token", password = true)
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

        // ---------- Appearance ----------
        item { SettingsHeader("APPEARANCE") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Theme", color = C.Text, fontSize = 14.sp)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    items(Palettes.size) { i ->
                        val p = Palettes[i]
                        ThemeSwatch(p, selected = data.settings.theme == p.id) {
                            Store.updateSettings { it.copy(theme = p.id) }
                            if (data.settings.themeIcon) com.cinedeck.app.IconSwitcher.apply(ctx, p.id)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Match the app icon to the theme", color = C.Text, fontSize = 14.sp)
                        Text("The launcher icon and TV banner change too. It can take a few seconds to update, and the TV may move ReelDJ in your apps row.", color = C.Muted, fontSize = 12.sp)
                    }
                    Switch(
                        data.settings.themeIcon,
                        { v ->
                            Store.updateSettings { it.copy(themeIcon = v) }
                            com.cinedeck.app.IconSwitcher.apply(ctx, if (v) data.settings.theme else "neon")
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.White),
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Navigation bar (TV and tablets)", color = C.Text, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Side", data.settings.navPosition != "top") { Store.updateSettings { it.copy(navPosition = "side") } }
                    Pill("Top", data.settings.navPosition == "top") { Store.updateSettings { it.copy(navPosition = "top") } }
                }
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
                    Column(Modifier.weight(1f)) {
                        Text("Auto-play next episode", color = C.Text, fontSize = 14.sp)
                        Text("TV shows start the next episode after a short countdown. Off: you get a Next episode button instead.", color = C.Muted, fontSize = 12.sp)
                    }
                    Switch(
                        data.settings.autoplayNext, { v -> Store.updateSettings { it.copy(autoplayNext = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.White),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Load subtitles from addons", color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        data.settings.autoSubtitles, { v -> Store.updateSettings { it.copy(autoSubtitles = v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = Color.White),
                    )
                }
                RemoteTextField(langs, { langs = it; Store.updateSettings { s -> s.copy(subtitleLangs = it) } }, "Subtitle languages (e.g. eng, spa, tgl)")
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
        item { Text("ReelDJ ${com.cinedeck.app.BuildConfig.VERSION_NAME}", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(16.dp)) }
    }
}

/** A theme choice: mini icon (EQ bars in the theme colors) + name. */
@Composable
private fun ThemeSwatch(p: Palette, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(14.dp), focusScale = 1.06f) { f ->
        Column(
            Modifier.width(132.dp).background(if (selected) C.Surface2 else C.Surface)
                .border(2.dp, if (selected) p.accent else Color.Transparent, RoundedCornerShape(14.dp)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.size(64.dp).background(p.bg, RoundedCornerShape(16.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(0.4f to p.accent, 0.75f to p.second, 0f to Color.White, 0.85f to p.second, 0.5f to p.accent).forEach { (h, c) ->
                    if (h == 0f) androidx.compose.foundation.Canvas(Modifier.size(10.dp, 12.dp)) {
                        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }, c)
                    } else Box(Modifier.size(5.dp, 40.dp * h).background(c, RoundedCornerShape(50)))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(p.name, color = if (selected || f) C.Text else C.Muted, fontSize = 12.sp, fontFamily = Display, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(p.accent, p.second, p.surface2).forEach { Box(Modifier.size(12.dp).background(it, RoundedCornerShape(50))) }
            }
        }
    }
}

@Composable
fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50)) {
        Icon(icon, desc, tint = C.Muted, modifier = Modifier.padding(6.dp).size(22.dp))
    }
}
