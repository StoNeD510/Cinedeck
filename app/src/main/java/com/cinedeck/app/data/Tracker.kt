package com.cinedeck.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Watch tracking logic: continue watching, up next, and the episode calendar. */
object Tracker {

    data class ContinueItem(
        val metaId: String,
        val type: String,
        val name: String,
        val image: String?,
        val poster: String?,
        val videoId: String,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val fraction: Float,
        val isUpNext: Boolean,
        val updatedAt: Long,
    )

    data class EpisodeEntry(val meta: Meta, val video: Video, val watched: Boolean)

    /** Series the user follows: anything in My List plus anything they've watched. */
    fun followedSeries(d: AppData): List<Pair<String, String>> {
        val ids = LinkedHashSet<String>()
        d.library.filter { it.type == "series" }.forEach { ids += it.id }
        d.progress.values.filter { it.type == "series" }.sortedByDescending { it.updatedAt }.forEach { ids += it.metaId }
        return ids.map { "series" to it }
    }

    fun sortedEpisodes(meta: Meta): List<Video> =
        (meta.videos ?: emptyList()).filter { it.season != null }
            .sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.ep ?: 0 }))

    /** Next episode to play for a series given what's been watched. */
    fun nextEpisode(meta: Meta, d: AppData): Video? {
        val eps = sortedEpisodes(meta).filter { it.season != 0 }
        if (eps.isEmpty()) return null
        // Resume a partly watched episode first.
        val partial = d.progress.values.filter { it.metaId == meta.id && !it.watched && it.position > 0 }
            .maxByOrNull { it.updatedAt }
        if (partial != null) eps.firstOrNull { it.id == partial.videoId }?.let { return it }
        val lastWatched = eps.indexOfLast { d.progress[it.id]?.watched == true }
        return eps.getOrNull(lastWatched + 1) ?: if (lastWatched == -1) eps.first() else null
    }

    /** Partly watched items plus the next aired episode of shows you're keeping up with. */
    fun continueWatching(d: AppData, metas: Map<String, Meta>): List<ContinueItem> {
        val out = mutableListOf<ContinueItem>()
        val seen = mutableSetOf<String>()
        d.progress.values.sortedByDescending { it.updatedAt }.forEach { p ->
            if (p.metaId in seen) return@forEach
            if (p.type == "series") {
                val meta = metas[p.metaId]
                val next = meta?.let { nextEpisode(it, d) }
                if (next != null && next.isAired()) {
                    val np = d.progress[next.id]
                    seen += p.metaId
                    out += ContinueItem(
                        p.metaId, "series", meta.name, next.thumbnail ?: meta.background ?: p.background,
                        meta.poster ?: p.poster, next.id, next.season, next.ep, next.label,
                        np?.fraction ?: 0f, isUpNext = np == null || np.position == 0L, updatedAt = p.updatedAt,
                    )
                } else if (meta == null && !p.watched && p.position > 0) {
                    seen += p.metaId
                    out += ContinueItem(p.metaId, p.type, p.name, p.background, p.poster, p.videoId, p.season, p.episode, p.episodeTitle, p.fraction, false, p.updatedAt)
                }
            } else if (!p.watched && p.position > 30_000) {
                seen += p.metaId
                out += ContinueItem(p.metaId, p.type, p.name, p.background, p.poster, p.videoId, null, null, null, p.fraction, false, p.updatedAt)
            }
        }
        return out
    }

    /** Episodes of followed series released between [past] days ago and [future] days ahead. */
    fun calendar(d: AppData, metas: List<Meta>, past: Long = 14, future: Long = 120): List<EpisodeEntry> {
        val now = Instant.now()
        val from = now.minus(past, ChronoUnit.DAYS)
        val to = now.plus(future, ChronoUnit.DAYS)
        return metas.flatMap { m ->
            (m.videos ?: emptyList()).mapNotNull { v ->
                val r = v.releasedInstant ?: return@mapNotNull null
                if (r.isBefore(from) || r.isAfter(to) || v.season == null) null
                else EpisodeEntry(m, v, d.progress[v.id]?.watched == true)
            }
        }.sortedBy { it.video.releasedInstant }
    }

    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}
