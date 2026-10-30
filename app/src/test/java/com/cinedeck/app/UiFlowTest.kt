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
        waitFor("streams screen") { texts().any { it == "Play with" } }
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
        waitFor("streams via remote") { texts().any { it == "Play with" } }
        println("REMOTE STREAMS OK")
    }

    @Test fun seriesFlow() {
        waitFor("home rows") { !HomeData.rows.value.isNullOrEmpty() }
        tapPoster("series")
    }
}
