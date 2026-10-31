package com.cinedeck.app.player

import com.cinedeck.app.data.Addons
import com.cinedeck.app.data.PlayRequest
import com.cinedeck.app.data.StreamInfo
import com.cinedeck.app.data.Tracker
import com.cinedeck.app.data.Video

/** Finds the next episode and a stream for it that matches the one being watched. */
object NextEpisode {
    data class Next(val video: Video, val request: PlayRequest?)

    suspend fun episodeAfter(req: PlayRequest): Video? {
        if (req.type != "series") return null
        val meta = Addons.meta("series", req.metaId) ?: return null
        val eps = Tracker.sortedEpisodes(meta).filter { it.season != 0 }
        val i = eps.indexOfFirst { it.id == req.videoId }
        return eps.getOrNull(i + 1)?.takeIf { i >= 0 && it.isAired() }
    }

    /** Same release (bingeGroup) first, then same addon + resolution, then best cached, then anything. */
    suspend fun resolve(req: PlayRequest, next: Video): PlayRequest? {
        val results = Addons.streams("series", next.id)
        val all = results.flatMap { r -> r.streams.filter { it.playableUrl != null }.map { r.addon.url to it } }
        if (all.isEmpty()) return null
        val pick = all.firstOrNull { (_, s) -> req.bingeGroup != null && s.behaviorHints?.bingeGroup == req.bingeGroup }
            ?: all.firstOrNull { (u, s) -> u == req.addonUrl && StreamInfo.of(s).let { it.resolution == req.resolution && it.cached } }
            ?: all.firstOrNull { (u, s) -> u == req.addonUrl && StreamInfo.of(s).resolution == req.resolution }
            ?: all.firstOrNull { (_, s) -> StreamInfo.of(s).cached }
            ?: all.first()
        val (url, s) = pick
        return req.copy(
            url = s.playableUrl!!, headers = s.behaviorHints?.proxyHeaders?.request.orEmpty(),
            videoId = next.id, season = next.season, episode = next.ep, episodeTitle = next.label,
            filename = s.behaviorHints?.filename, addonUrl = url, bingeGroup = s.behaviorHints?.bingeGroup,
            resolution = StreamInfo.of(s).resolution,
        )
    }
}
