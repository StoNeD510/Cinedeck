package com.cinedeck.app.data

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A one-field web page served from the TV so a long GitHub token can be pasted from a phone instead of typed
 * with the remote. It only runs while the Settings dialog is open, only on the home network, and only accepts
 * a token together with the 4-digit code shown on the TV.
 */
class TokenPortal(private val onToken: (String) -> Unit) {
    val pin = (1000..9999).random().toString()
    private var server: ServerSocket? = null
    @Volatile private var tries = 0

    /** Starts listening; returns the address to open on the phone, or null if the TV has no network address. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val s = runCatching { ServerSocket(8765) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return null
        server = s
        thread(isDaemon = true, name = "token-portal") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                runCatching {
                    c.soTimeout = 8000
                    c.use { sock ->
                        val input = sock.getInputStream().bufferedReader()
                        val first = input.readLine() ?: return@use
                        var len = 0
                        while (true) {
                            val h = input.readLine() ?: break
                            if (h.isEmpty()) break
                            if (h.startsWith("content-length:", true)) len = h.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        var note = ""
                        if (first.startsWith("POST") && len in 1..4096) {
                            val buf = CharArray(len); var n = 0
                            while (n < len) { val r = input.read(buf, n, len - n); if (r < 0) break; n += r }
                            val form = String(buf, 0, n).split("&").mapNotNull {
                                val k = it.substringBefore("="); val v = it.substringAfter("=", "")
                                runCatching { k to URLDecoder.decode(v, "UTF-8") }.getOrNull()
                            }.toMap()
                            val token = form["token"].orEmpty().trim()
                            note = when {
                                tries >= 8 -> "Too many wrong codes. Close and reopen this screen on the TV."
                                form["pin"]?.trim() != pin -> { tries++; "That code doesn't match the one on the TV." }
                                token.length < 20 -> "That doesn't look like a GitHub token."
                                else -> { onToken(token); "Saved. You can close this page." }
                            }
                        }
                        val html = page(note)
                        val bytes = html.toByteArray()
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
        return "http://$ip:${s.localPort}"
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun page(note: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>ReelDJ sync</title><style>body{font-family:sans-serif;background:#08080b;color:#f5f5f7;max-width:520px;margin:32px auto;padding:0 16px}
input,button{width:100%;box-sizing:border-box;font-size:16px;padding:12px;margin:8px 0;border-radius:8px;border:1px solid #33333f;background:#121218;color:#f5f5f7}
button{background:#ff3d9a;border:0;color:#08080b;font-weight:700}p{color:#a0a0ae}b{color:#3de0ff}</style></head><body>
<h2>ReelDJ sync</h2><p>Paste your GitHub token and enter the 4-digit code shown on the TV.</p>
${if (note.isNotEmpty()) "<p><b>$note</b></p>" else ""}
<form method="post"><input name="token" placeholder="github_pat_…" autocomplete="off" autocapitalize="off" spellcheck="false">
<input name="pin" placeholder="Code on the TV" inputmode="numeric" autocomplete="off"><button>Send to TV</button></form></body></html>"""

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
