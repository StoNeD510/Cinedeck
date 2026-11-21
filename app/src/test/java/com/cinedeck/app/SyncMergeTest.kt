package com.cinedeck.app

import com.cinedeck.app.data.LibraryItem
import com.cinedeck.app.data.Progress
import com.cinedeck.app.data.Sync
import com.cinedeck.app.data.SyncDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun p(id: String, pos: Long, at: Long, watched: Boolean = false) =
        Progress(videoId = id, metaId = "m", type = "series", name = "Show", position = pos, duration = 1000, watched = watched, updatedAt = at)
    private val now = 1_000_000L

    @Test fun newestProgressWinsBothWays() {
        val a = SyncDoc(progress = mapOf("e1" to p("e1", 100, 10), "e2" to p("e2", 0, 50, watched = true)))
        val b = SyncDoc(progress = mapOf("e1" to p("e1", 400, 20), "e2" to p("e2", 300, 40), "e3" to p("e3", 5, 5)))
        val m = Sync.merge(a, b, now)
        assertEquals(400, m.progress["e1"]!!.position)
        assertTrue(m.progress["e2"]!!.watched)
        assertEquals(setOf("e1", "e2", "e3"), m.progress.keys)
        assertEquals(m, Sync.merge(b, a, now).copy(addons = m.addons)) // order of sides doesn't matter
        assertEquals(m, Sync.merge(m, b, now)) // merging again changes nothing
    }

    @Test fun removalBeatsOlderCopyButNotNewerOne() {
        val a = SyncDoc(deleted = mapOf("p:e1" to 30L, "p:e2" to 30L, "l:tt1" to 30L))
        val b = SyncDoc(
            progress = mapOf("e1" to p("e1", 100, 20), "e2" to p("e2", 100, 40)),
            library = listOf(LibraryItem("tt1", "series", "Old", addedAt = 10), LibraryItem("tt2", "movie", "Keep", addedAt = 10)),
        )
        val m = Sync.merge(a, b, now)
        assertFalse("removed after its last change", m.progress.containsKey("e1"))
        assertTrue("watched again after the removal", m.progress.containsKey("e2"))
        assertEquals(listOf("tt2"), m.library.map { it.id })
        assertFalse("tombstone dropped once the item is back", m.deleted.containsKey("p:e2"))
        assertTrue(m.deleted.containsKey("p:e1"))
    }

    @Test fun addonsUnionRemovalAndReAdd() {
        val a = SyncDoc(addons = listOf("cine", "x"), deleted = mapOf("a:old" to 30L, "a:back" to 30L), addonTimes = mapOf("back" to 50L))
        val b = SyncDoc(addons = listOf("cine", "old", "back", "new"), addonTimes = mapOf("new" to 5L))
        val m = Sync.merge(a + "back", b, now)
        assertEquals(listOf("cine", "back", "new", "x"), m.addons)
    }

    private operator fun SyncDoc.plus(url: String) = copy(addons = addons + url)

    @Test fun oldTombstonesExpire() {
        val m = Sync.merge(SyncDoc(deleted = mapOf("p:e9" to 1L)), SyncDoc(), 200L * 24 * 3600 * 1000)
        assertTrue(m.deleted.isEmpty())
    }
}
