package com.cinedeck.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cinedeck.app.data.Meta

/** A clickable surface that grows and gets an accent outline when focused with a remote. */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    onLongClick: (() -> Unit)? = null,
    focusScale: Float = 1.06f,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val s by animateFloatAsState(if (focused) focusScale else 1f, label = "focus")
    Box(
        modifier
            .scale(s)
            .then(if (focused) Modifier.shadow(14.dp, shape, ambientColor = C.Accent, spotColor = C.Accent) else Modifier)
            .clip(shape)
            .then(if (focused) Modifier.border(2.5.dp, C.Accent, shape) else Modifier)
            .combinedClick(src, onClick, onLongClick),
    ) { content(focused) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClick(src: MutableInteractionSource, onClick: () -> Unit, onLong: (() -> Unit)?): Modifier =
    if (onLong == null) this.clickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onClick)
    else this.combinedClickable(interactionSource = src, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLong)

@Composable
fun posterWidth(): Dp = if (LocalIsTv.current) 140.dp else if (LocalWide.current) 130.dp else 112.dp

@Composable
fun PosterCard(meta: Meta, onClick: () -> Unit, width: Dp? = posterWidth(), progress: Float = 0f, watched: Boolean = false, modifier: Modifier = Modifier, focusScale: Float = 1.06f, onLongClick: (() -> Unit)? = null, compact: Boolean = false) {
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier)) {
        FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusScale = focusScale) {
            AsyncImage(
                model = meta.poster, contentDescription = meta.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(C.Surface2),
            )
            meta.imdbRating?.takeIf { it.isNotBlank() && it != "0" }?.let { r ->
                Row(
                    Modifier.align(Alignment.TopStart).padding(5.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Star, null, tint = C.Gold, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(r, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (watched) Icon(
                Icons.Default.CheckCircle, "Watched", tint = C.Accent,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).background(Color.Black, RoundedCornerShape(50)),
            )
            if (progress > 0f) LinearProgressIndicator(
                progress = { progress }, color = C.Cyan, trackColor = Color(0x66000000),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(meta.name, color = C.Text, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!compact) meta.yearText?.let { Text(it, color = C.Muted, fontSize = 11.sp, maxLines = 1) }
    }
}

@Composable
fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Heading, fontSize = 19.sp, modifier = Modifier.weight(1f))
        if (onMore != null) FocusBox(onClick = onMore, shape = RoundedCornerShape(50)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("See all", color = C.Muted, fontSize = 13.sp)
                Icon(Icons.Default.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Posters across the visible width: 6 on TV/tablet, 3 on a phone. */
@Composable
fun postersAcross(): Int = if (LocalWide.current) 6 else 3

@Composable
fun PosterRow(items: List<Meta>, onOpen: (Meta) -> Unit, watchedIds: Set<String> = watchedTitleIds()) {
    var menuFor by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Meta?>(null) }
    menuFor?.let { m -> TitleActions(m, onOpen = { onOpen(m) }, onDismiss = { menuFor = null }) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = postersAcross()
        val gap = 10.dp
        val peek = 18.dp // a sliver of the next poster shows the row scrolls
        val w = (maxWidth - 16.dp - gap * n - peek) / n
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            items(items, key = { it.type + it.id }) { m -> PosterCard(m, { onOpen(m) }, width = w, watched = m.id in watchedIds, focusScale = 1.05f, onLongClick = { menuFor = m }) }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.04f) {
        Text(
            text,
            color = if (selected) Color.White else C.Text,
            fontSize = 13.sp,
            fontFamily = Display,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.background(if (selected) C.Accent else C.Surface2).padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

@Composable
fun Tag(text: String, color: Color = C.Surface2, textColor: Color = C.Text) {
    Text(
        text, color = textColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxWidth().height(200.dp)) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Accent) }
}

@Composable
fun Message(title: String, body: String? = null, modifier: Modifier = Modifier.fillMaxWidth().padding(32.dp)) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        body?.let { Spacer(Modifier.height(6.dp)); Text(it, color = C.Muted, fontSize = 13.sp) }
    }
}

val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), C.Bg))
val LeftFade = Brush.horizontalGradient(listOf(C.Bg, Color(0xCC0B0D10), Color.Transparent))

fun epCode(s: Int?, e: Int?): String = if (s == null) "" else "S%02dE%02d".format(s, e ?: 0)

/** On TV, put the remote's focus on this element when it first appears. */
@Composable
fun Modifier.tvInitialFocus(key: Any? = Unit): Modifier {
    if (!LocalIsTv.current) return this
    val req = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(key) {
        repeat(10) {
            kotlinx.coroutines.delay(120)
            if (runCatching { req.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    return this.focusRequester(req)
}

/**
 * Text box that behaves on a TV remote: moving over it only highlights it, and the keyboard
 * opens after you press OK. On phones and tablets it's a normal text box.
 */
@Composable
fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    singleLine: Boolean = true,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
        focusedBorderColor = C.Accent, unfocusedBorderColor = C.Surface2,
        focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
    )
    val visual = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
    else androidx.compose.ui.text.input.VisualTransformation.None
    val ime = androidx.compose.foundation.text.KeyboardOptions(
        imeAction = if (singleLine) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
    )
    if (!LocalIsTv.current) {
        androidx.compose.material3.OutlinedTextField(
            value, onValueChange, label = { Text(label) }, placeholder = { Text(placeholder) },
            singleLine = singleLine, maxLines = if (singleLine) 1 else 4, colors = colors, visualTransformation = visual,
            keyboardOptions = ime,
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSubmit?.invoke() }),
            shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth(),
        )
        return
    }
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    FocusBox(onClick = { editing = true }, modifier = modifier.fillMaxWidth(), focusScale = 1.02f) { focused ->
        Column(Modifier.fillMaxWidth().background(C.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(label, color = if (focused) C.Accent else C.Muted, fontSize = 11.sp)
            val shown = when {
                value.isEmpty() -> placeholder.ifEmpty { "Press OK to type" }
                password -> "•".repeat(value.length.coerceAtMost(24))
                else -> value
            }
            Text(shown, color = if (value.isEmpty()) C.Muted else C.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    if (editing) {
        var draft by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
        val fr = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { fr.requestFocus() } }
        fun commit() { onValueChange(draft); editing = false; onSubmit?.invoke() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            containerColor = C.Surface,
            title = { Text(label, color = C.Text) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    draft, { draft = it }, placeholder = { Text(placeholder) }, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
                    colors = colors, visualTransformation = visual,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fr),
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { commit() }) { Text("OK", color = C.Accent) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { editing = false }) { Text("Cancel", color = C.Muted) } },
        )
    }
}

/** Titles with anything marked watched (movies watched, or shows with watched episodes). */
@Composable
fun watchedTitleIds(): Set<String> {
    val d by com.cinedeck.app.data.Store.data.collectAsState()
    return androidx.compose.runtime.remember(d.progress) { d.progress.values.filter { it.watched }.map { it.metaId }.toSet() }
}

/** Hold-OK / long-press menu for a poster: mark watched, My List, open. */
@Composable
fun TitleActions(meta: Meta, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val d by com.cinedeck.app.data.Store.data.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val watched = d.progress.values.any { it.metaId == meta.id && it.watched }
    val inList = d.library.any { it.id == meta.id }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(meta.name, color = C.Text, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuRow(if (watched) "Mark as unwatched" else if (meta.type == "series") "Mark all aired episodes watched" else "Mark as watched", Modifier.tvInitialFocus()) {
                    scope.launch {
                        if (meta.type == "series") {
                            val full = com.cinedeck.app.data.Addons.meta("series", meta.id)
                            if (full == null) {
                                android.widget.Toast.makeText(ctx, "Couldn't load episodes", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (watched) {
                                com.cinedeck.app.data.Store.removeProgressForMeta(meta.id)
                            } else {
                                com.cinedeck.app.data.Store.setWatched(full, com.cinedeck.app.data.Tracker.sortedEpisodes(full).filter { it.isAired() && it.season != 0 }, true)
                            }
                        } else {
                            com.cinedeck.app.data.Store.setWatched(meta, emptyList(), !watched)
                        }
                        onDismiss()
                    }
                }
                MenuRow(if (inList) "Remove from My List" else "Add to My List") { com.cinedeck.app.data.Store.toggleLibrary(meta); onDismiss() }
                MenuRow("Open details") { onDismiss(); onOpen() }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun MenuRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.fillMaxWidth(), focusScale = 1.02f) { f ->
        Text(label, color = if (f) Color.Black else C.Text, fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().background(if (f) C.Accent else C.Surface2).padding(14.dp))
    }
}

/** Little equalizer mark used in the logo. */
@Composable
fun EqMark(height: Dp = 18.dp) {
    val bars = listOf(0.55f, 0.9f, 0.0f, 1f, 0.65f)
    Row(horizontalArrangement = Arrangement.spacedBy(height * 0.12f), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(height)) {
        bars.forEachIndexed { i, h ->
            if (i == 2) {
                androidx.compose.foundation.Canvas(Modifier.size(height * 0.5f, height * 0.6f)) {
                    val p = androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }
                    drawPath(p, Color.White)
                }
            } else Box(
                Modifier.size(height * 0.22f, height * h).clip(RoundedCornerShape(50))
                    .background(if (i % 2 == 0) C.Accent else C.Cyan),
            )
        }
    }
}

/** ReelDJ wordmark: "Reel" + magenta "DJ" pill. */
@Composable
fun Logo(size: androidx.compose.ui.unit.TextUnit = 20.sp, mark: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (mark) { EqMark(androidx.compose.ui.unit.Dp(size.value * 1.05f)); Spacer(Modifier.width(8.dp)) }
        Text("Reel", color = C.Text, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = size)
        Spacer(Modifier.width(3.dp))
        Text(
            "DJ", color = C.Bg, fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = size,
            modifier = Modifier.background(C.Accent, RoundedCornerShape(50)).padding(horizontal = 7.dp),
        )
    }
}

/**
 * Stops lists from nudging when the remote moves along a row: a list only scrolls when the
 * newly focused item is actually off screen, and then just far enough to show it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StableScroll(content: @Composable () -> Unit) {
    val spec = androidx.compose.runtime.remember {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val end = offset + size
                return when {
                    offset >= 0f && end <= containerSize -> 0f
                    offset < 0f -> offset - 24f
                    else -> (end - containerSize + 24f).coerceAtMost(offset)
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) { content() }
}
