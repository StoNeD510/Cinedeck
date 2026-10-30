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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
fun PosterCard(meta: Meta, onClick: () -> Unit, width: Dp? = posterWidth(), progress: Float = 0f, watched: Boolean = false, modifier: Modifier = Modifier, focusScale: Float = 1.06f) {
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier)) {
        FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), focusScale = focusScale) {
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
                progress = { progress }, color = C.Accent, trackColor = Color(0x66000000),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(meta.name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        meta.yearText?.let { Text(it, color = C.Muted, fontSize = 11.sp, maxLines = 1) }
    }
}

@Composable
fun SectionHeader(title: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
fun PosterRow(items: List<Meta>, onOpen: (Meta) -> Unit, watchedIds: Set<String> = emptySet()) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val n = postersAcross()
        val gap = 10.dp
        val peek = 18.dp // a sliver of the next poster shows the row scrolls
        val w = (maxWidth - 16.dp - gap * n - peek) / n
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
            items(items, key = { it.type + it.id }) { m -> PosterCard(m, { onOpen(m) }, width = w, watched = m.id in watchedIds, focusScale = 1.05f) }
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.04f) {
        Text(
            text,
            color = if (selected) Color.Black else C.Text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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
