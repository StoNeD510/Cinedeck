package com.cinedeck.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

// ---------- Stremio addon protocol ----------

@Serializable
data class ExtraDef(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String>? = null,
)

@Serializable
data class CatalogDef(
    val type: String,
    val id: String,
    val name: String? = null,
    val extra: List<ExtraDef>? = null,
    val extraSupported: List<String>? = null,
    val extraRequired: List<String>? = null,
) {
    fun supportsExtra(n: String) = extra?.any { it.name == n } == true || extraSupported?.contains(n) == true
    fun requiresExtra(): Boolean =
        extra?.any { it.isRequired } == true || !extraRequired.isNullOrEmpty()
    fun requires(n: String) = extra?.any { it.name == n && it.isRequired } == true || extraRequired?.contains(n) == true
    fun genres(): List<String> = extra?.firstOrNull { it.name == "genre" }?.options ?: emptyList()
}

@Serializable
data class Manifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String? = null,
    val logo: String? = null,
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<CatalogDef> = emptyList(),
    val idPrefixes: List<String>? = null,
) {
    fun supports(resource: String, type: String, id: String? = null): Boolean {
        for (r in resources) {
            if (r is JsonPrimitive && r.content == resource) {
                return types.contains(type) && prefixOk(idPrefixes, id)
            }
            if (r is JsonObject && r["name"]?.jsonPrimitive?.content == resource) {
                val t = r["types"]?.let { e -> e.jsonArray.map { it.jsonPrimitive.content } } ?: types
                val p = r["idPrefixes"]?.let { e -> e.jsonArray.map { it.jsonPrimitive.content } } ?: idPrefixes
                return t.contains(type) && prefixOk(p, id)
            }
        }
        return false
    }

    private fun prefixOk(prefixes: List<String>?, id: String?): Boolean =
        id == null || prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) }
}

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val description: String? = null,
) {
    val ep: Int? get() = episode ?: number
    val label: String get() = title ?: name ?: "Episode ${ep ?: ""}"
    val summary: String? get() = overview ?: description
    val releasedInstant: Instant? get() = released?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val releasedDate: LocalDate? get() = releasedInstant?.atZone(ZoneId.systemDefault())?.toLocalDate()
    fun isAired(now: Instant = Instant.now()) = releasedInstant?.let { !it.isAfter(now) } ?: true
}

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String = "",
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val runtime: String? = null,
    val cast: List<String>? = null,
    val director: List<String>? = null,
    val videos: List<Video>? = null,
) {
    val allGenres: List<String> get() = (genres ?: genre ?: emptyList()).distinct()
    val yearText: String? get() = releaseInfo ?: year
}

@Serializable
data class ProxyHeaders(val request: Map<String, String>? = null)

@Serializable
data class StreamHints(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val proxyHeaders: ProxyHeaders? = null,
)

@Serializable
data class Stream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val behaviorHints: StreamHints? = null,
) {
    val text: String get() = listOfNotNull(title, description).joinToString("\n").ifBlank { name ?: "" }
    val playableUrl: String? get() = url ?: ytId?.let { "https://www.youtube.com/watch?v=$it" }
}

@Serializable
data class Subtitle(val id: String? = null, val url: String, val lang: String = "und")

@Serializable data class CatalogResponse(val metas: List<Meta> = emptyList())
@Serializable data class MetaResponse(val meta: Meta? = null)
@Serializable data class StreamResponse(val streams: List<Stream> = emptyList())
@Serializable data class SubtitleResponse(val subtitles: List<Subtitle> = emptyList())

// ---------- Local app state ----------

@Serializable
data class AddonEntry(val url: String, val manifest: Manifest? = null)

@Serializable
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Progress(
    val videoId: String,
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val position: Long = 0,
    val duration: Long = 0,
    val watched: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

@Serializable
data class Settings(
    val engine: String = "auto", // auto | exo | vlc | external
    val subtitleLangs: String = "eng",
    val autoSubtitles: Boolean = true,
    val rdToken: String = "",
    val navPosition: String = "side", // side | top (TV and tablets)
    val streamSort: String = "quality", // quality | seeders | size
)

@Serializable
data class AppData(
    val addons: List<AddonEntry> = DefaultAddons,
    val library: List<LibraryItem> = emptyList(),
    val progress: Map<String, Progress> = emptyMap(),
    val settings: Settings = Settings(),
)

val DefaultAddons = listOf(
    AddonEntry("https://v3-cinemeta.strem.io/manifest.json"),
    AddonEntry("https://opensubtitles-v3.strem.io/manifest.json"),
)

/** What the player needs to start and to record progress. */
@Serializable
data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val videoId: String,
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val engine: String = "auto",
    val filename: String? = null,
)
