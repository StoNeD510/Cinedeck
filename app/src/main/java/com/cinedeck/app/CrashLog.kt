package com.cinedeck.app

import android.content.Context
import android.os.Build
import java.io.File

/** Saves the last crash so the app can show it on the next launch (makes bug reports possible on TV). */
object CrashLog {
    private lateinit var file: File

    fun install(ctx: Context) {
        file = File(ctx.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = e.stackTraceToString().lines().take(60).joinToString("\n")
                file.writeText(
                    "ReelDJ ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${t.name}\n\n$trace",
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear() { runCatching { file.delete() } }
}
