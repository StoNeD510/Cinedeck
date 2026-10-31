package com.cinedeck.app.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last few playback problems so they can be read in Settings and reported. */
object PlayerLog {
    private var file: File? = null
    fun init(ctx: Context) { file = File(ctx.filesDir, "player_log.txt") }

    fun add(line: String) {
        val f = file ?: return
        runCatching {
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "$stamp  $line"
            f.writeText(lines.takeLast(12).joinToString("\n"))
        }
    }

    fun read(): List<String> = runCatching { file?.takeIf { it.exists() }?.readLines() }.getOrNull().orEmpty()
    fun clear() { runCatching { file?.delete() } }
}
