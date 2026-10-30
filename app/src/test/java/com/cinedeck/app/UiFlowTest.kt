package com.cinedeck.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cinedeck.app.ui.HomeData
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiFlowTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(desc: String, ms: Long = 40_000, f: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            rule.waitForIdle(); Thread.sleep(300)
            if (runCatching(f).getOrDefault(false)) return
        }
        rule.onRoot().printToString().lines().take(60).forEach(::println)
        error("timed out waiting for $desc")
    }

    private fun texts(): List<String> = rule.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        .mapNotNull { n -> n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() }

    private fun onDetails() = texts().none { it == "Home" } && texts().any { it.contains("My List") }

    private fun tapPoster(type: String) {
        val row = HomeData.rows.value!!.first { it.type == type }
        val m = row.items[1]
        println("ROW ${row.title} -> tapping ${m.name} (${m.type}/${m.id})")
        rule.onAllNodes(hasScrollToKeyAction()).onFirst().performScrollToKey("r_" + row.key)
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription(m.name, useUnmergedTree = true).onLast().performClick()
        Thread.sleep(500); rule.waitForIdle()
        println("AFTER TAP: " + texts().take(14))
        waitFor("details of ${m.name}") { onDetails() }
        Thread.sleep(3000); rule.waitForIdle()
        println("DETAILS OK: " + texts().take(40))
    }

    @Test fun movieFlow() {
        waitFor("home rows") { !HomeData.rows.value.isNullOrEmpty() }
        tapPoster("movie")
        rule.onAllNodesWithText("Play").onFirst().performClick()
        waitFor("streams screen") { texts().any { it.startsWith("No playable") || it.startsWith("Resolution") || it.startsWith("Sort by") } }
        println("STREAMS OK: " + texts().take(25))
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun remoteFlow() {
        waitFor("home rows") { !HomeData.rows.value.isNullOrEmpty() }
        val row = HomeData.rows.value!!.first { it.type == "movie" }
        val m = row.items[2]
        println("REMOTE -> ${m.name}")
        val target = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasContentDescription(m.name)), useUnmergedTree = true).onLast()
        target.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        println("FOCUSED? " + runCatching { target.assertIsFocused(); true }.getOrDefault(false) + " inputMode=" + rule.activity.window.decorView.isInTouchMode)
        target.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        Thread.sleep(500); rule.waitForIdle()
        println("REMOTE AFTER: " + texts().take(10))
        waitFor("details via remote") { onDetails() }
        println("REMOTE DETAILS OK")
        rule.onAllNodesWithText("Play").onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.onAllNodesWithText("Play").onFirst().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        waitFor("streams via remote") { texts().any { it.startsWith("No playable") || it.startsWith("Resolution") || it.startsWith("Sort by") } }
        println("REMOTE STREAMS OK")
    }

    private fun key(code: Int) {
        val a = rule.activity
        rule.runOnUiThread {
            val root = a.window.decorView.rootView
            root.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            root.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
        rule.waitForIdle(); Thread.sleep(150)
    }

    private fun focusedText(): String {
        val n = rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull() ?: return "<none>"
        fun collect(x: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            listOfNotNull(
                x.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString(),
                x.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.joinToString(),
            ) + x.children.flatMap { collect(it) }
        return collect(n).joinToString(" | ").take(80)
    }

    @Test fun dpadFlow() {
        waitFor("home rows") { !HomeData.rows.value.isNullOrEmpty() }
        Thread.sleep(2000); rule.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        rule.runOnUiThread { rule.activity.window.decorView.requestFocus() }
        rule.waitForIdle()
        println("touchMode=" + rule.activity.window.decorView.isInTouchMode)
        val names = HomeData.rows.value!!.flatMap { r -> r.items.map { it.name } }.toSet()
        for (i in 1..14) {
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            val f = focusedText()
            println("DPAD step $i focus: $f")
            if (names.any { f.startsWith(it) }) break
        }
        println("PRESS OK on: " + focusedText())
        key(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        for (t in 1..20) { Thread.sleep(300); rule.waitForIdle() }
        println("DPAD AFTER OK: " + texts().take(14))
        println("DPAD ON DETAILS? " + onDetails() + " focus=" + focusedText())
    }

    private fun posters() = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasContentDescription("", substring = true)), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun shot(name: String) {
        Thread.sleep(2500); rule.waitForIdle()
        var bmp: android.graphics.Bitmap? = null
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp!!))
        }
        val out = bmp!!
        java.io.File(System.getProperty("shotDir") ?: "/tmp").mkdirs()
        java.io.FileOutputStream(java.io.File(System.getProperty("shotDir") ?: "/tmp", "$name.png")).use { out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT $name")
    }

    private fun pick(chip: String, option: String) {
        rule.onAllNodesWithText(chip, substring = true).onFirst().performClick(); rule.waitForIdle(); Thread.sleep(300)
        waitFor("picker for $chip", 8_000) { rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty() }
        val opt = rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText(option)) and hasAnyAncestor(isDialog()), useUnmergedTree = true).onFirst()
        println("OPTION bounds=" + opt.fetchSemanticsNode().boundsInRoot)
        opt.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick); rule.waitForIdle()
    }

    @Test fun browseFilters() {
        waitFor("home rows") { !HomeData.rows.value.isNullOrEmpty() }
        shot("home")
        rule.onAllNodesWithText("Browse").onFirst().performClick()
        waitFor("movies grid") { posters() >= 6 }
        shot("browse")
        val steps = listOf("Show" to "TV Shows", "Genre" to "Comedy", "Show" to "Movies", "Genre" to "Drama", "Catalog" to "New", "Year" to "2024")
        for ((chip, opt) in steps) {
            pick(chip, opt)
            waitFor("after $opt") { posters() >= 6 && texts().any { it.contains(opt) } }
            println("BROWSE $chip=$opt -> posters=" + posters())
            if (opt == "Comedy") shot("browse_comedy")
        }
        rule.onAllNodesWithText("Year", substring = true).onFirst().performClick(); rule.waitForIdle()
        shot("year_picker")
    }

    @Test fun seriesFlow() {
        waitFor("home rows") { !HomeData.rows.value.isNullOrEmpty() }
        tapPoster("series")
    }
}
