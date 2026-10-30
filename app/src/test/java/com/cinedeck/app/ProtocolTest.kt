package com.cinedeck.app

import com.cinedeck.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URL

class ProtocolTest {
    private fun get(u: String) = URL(u).readText()

    @Test fun cinemetaDecodes() {
        val m = AppJson.decodeFromString(Manifest.serializer(), get("https://v3-cinemeta.strem.io/manifest.json"))
        println("manifest ${m.name} catalogs=${m.catalogs.map { it.type + "/" + it.id + " req=" + it.requiresExtra() + " genres=" + it.genres().size }}")
        assertTrue(m.supports("meta", "series", "tt0944947"))
        val c = AppJson.decodeFromString(CatalogResponse.serializer(), get("https://v3-cinemeta.strem.io/catalog/series/top.json"))
        println("catalog ${c.metas.size} first=${c.metas.first().name} rating=${c.metas.first().imdbRating} year=${c.metas.first().yearText} bg=${c.metas.first().background != null}")
        assertTrue(c.metas.isNotEmpty())
        val p2 = AppJson.decodeFromString(CatalogResponse.serializer(), get("https://v3-cinemeta.strem.io/catalog/movie/top/skip=50.json"))
        println("page2 ${p2.metas.size}")
        val meta = AppJson.decodeFromString(MetaResponse.serializer(), get("https://v3-cinemeta.strem.io/meta/series/" + c.metas.first().id + ".json")).meta!!
        val eps = Tracker.sortedEpisodes(meta)
        println("meta ${meta.name} eps=${eps.size} first=${eps.firstOrNull()?.let { "${it.id} ${it.label} ${it.releasedDate}" }} genres=${meta.allGenres}")
        assertTrue(eps.isNotEmpty())
        val d = AppData(progress = mapOf(eps[0].id to Progress(eps[0].id, meta.id, "series", meta.name, watched = true)))
        println("next=${Tracker.nextEpisode(meta, d)?.id} cal=${Tracker.calendar(d, listOf(meta), 30, 365).size}")
        assertEquals(eps[1].id, Tracker.nextEpisode(meta, d)?.id)
        val subs = AppJson.decodeFromString(SubtitleResponse.serializer(), get("https://opensubtitles-v3.strem.io/subtitles/movie/tt0111161.json"))
        println("subs ${subs.subtitles.size} ${subs.subtitles.take(3)}")
    }

    @Test fun streamInfo() {
        val s = Stream(name = "Torrentio\n4k DV | HDR [RD+]", title = "Movie.2023.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265\n👤 120 💾 18.4 GB ⚙️ ThePirateBay")
        val i = StreamInfo.of(s)
        println(i)
        assertEquals("4K", i.resolution); assertTrue(i.cached); assertEquals("18.4 GB", i.size)
    }
}
