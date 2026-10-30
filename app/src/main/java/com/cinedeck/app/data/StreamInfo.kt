package com.cinedeck.app.data

/** Pulls quality labels out of the free-text names addons put on streams. */
data class StreamInfo(
    val resolution: String?, // 2160p, 1080p, 720p, 480p
    val tags: List<String>,  // HDR, DV, HEVC, Atmos, etc.
    val size: String?,
    val cached: Boolean,
    val rank: Int,
    val seeders: Int?,
    val source: String?,   // Remux, BluRay, WEB-DL, WEBRip, HDTV, DVD, CAM…
    val bytes: Long,
) {
    companion object {
        private val sizeRx = Regex("""(\d+(?:[.,]\d+)?)\s?(GB|MB|GiB|MiB)""", RegexOption.IGNORE_CASE)
        private val seedRx = Regex("""(?:👤|Seeders?:?|S:)\s*(\d+)""", RegexOption.IGNORE_CASE)

        fun of(s: Stream): StreamInfo {
            val t = listOfNotNull(s.name, s.title, s.description, s.behaviorHints?.filename).joinToString(" ")
            val u = t.uppercase()
            val res = when {
                Regex("""2160P|\b4K\b|UHD""").containsMatchIn(u) -> "4K"
                "1440P" in u -> "1440p"
                "1080P" in u -> "1080p"
                "720P" in u -> "720p"
                "576P" in u || "480P" in u -> "SD"
                else -> null
            }
            val tags = buildList {
                if (Regex("""\bDV\b|DOLBY ?VISION|DOVI""").containsMatchIn(u)) add("DV")
                if (Regex("""HDR10\+""").containsMatchIn(u)) add("HDR10+") else if ("HDR" in u) add("HDR")
                if (Regex("""HEVC|X265|H\.?265""").containsMatchIn(u)) add("HEVC")
                if ("AV1" in u) add("AV1")
                if ("ATMOS" in u) add("Atmos")
                if (Regex("""TRUEHD""").containsMatchIn(u)) add("TrueHD")
                if (Regex("""DTS-?HD|DTS:?X""").containsMatchIn(u)) add("DTS-HD") else if ("DTS" in u) add("DTS")
                if (Regex("""REMUX""").containsMatchIn(u)) add("Remux")
                if (Regex("""\bCAM\b|HDCAM|TELESYNC|\bTS\b""").containsMatchIn(u)) add("CAM")
            }
            val size = s.behaviorHints?.videoSize?.let { formatBytes(it) }
                ?: sizeRx.find(t)?.value
            val cached = Regex("""\[RD\+]|RD\+|⚡|\[RD download]|INSTANT""").containsMatchIn(u)
            val rank = when (res) { "4K" -> 4; "1440p" -> 3; "1080p" -> 3; "720p" -> 2; "SD" -> 1; else -> 0 }
            val seeders = seedRx.find(t)?.groupValues?.get(1)?.toIntOrNull()
            val source = when {
                "REMUX" in u -> "Remux"
                Regex("""BLU-?RAY|BDRIP|BRRIP|\bBD\b""").containsMatchIn(u) -> "BluRay"
                Regex("""WEB-?DL""").containsMatchIn(u) -> "WEB-DL"
                Regex("""WEB-?RIP""").containsMatchIn(u) -> "WEBRip"
                Regex("""\bWEB\b""").containsMatchIn(u) -> "WEB"
                "HDTV" in u -> "HDTV"
                Regex("""DVDRIP|\bDVD\b""").containsMatchIn(u) -> "DVD"
                Regex("""\bCAM\b|HDCAM|TELESYNC|\bHDTS\b|\bTS\b""").containsMatchIn(u) -> "CAM"
                else -> null
            }
            val bytes = s.behaviorHints?.videoSize ?: sizeRx.find(t)?.let { m ->
                val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                (if (m.groupValues[2].uppercase().startsWith("G")) n * (1L shl 30) else n * (1L shl 20)).toLong()
            } ?: 0L
            return StreamInfo(res, tags.filterNot { it == "Remux" || it == "CAM" }, size, cached, rank, seeders, source, bytes)
        }

        fun formatBytes(b: Long): String =
            if (b >= 1L shl 30) String.format("%.2f GB", b / (1L shl 30).toDouble())
            else String.format("%.0f MB", b / (1L shl 20).toDouble())
    }
}
