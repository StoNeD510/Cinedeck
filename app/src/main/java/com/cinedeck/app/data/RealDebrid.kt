package com.cinedeck.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Minimal Real-Debrid API client: account status and your own downloads list. */
object RealDebrid {
    private const val API = "https://api.real-debrid.com/rest/1.0"

    @Serializable
    data class User(val username: String = "", val type: String = "", val expiration: String? = null, val points: Int = 0)

    @Serializable
    data class Download(
        val id: String,
        val filename: String = "",
        val mimeType: String? = null,
        val filesize: Long = 0,
        val link: String? = null,
        val host: String? = null,
        val download: String,
        val streamable: Int = 0,
        val generated: String? = null,
    )

    private fun auth(token: String) = mapOf("Authorization" to "Bearer ${token.trim()}")

    suspend fun user(token: String): User =
        AppJson.decodeFromString(User.serializer(), Http.get("$API/user", auth(token)))

    suspend fun downloads(token: String): List<Download> =
        AppJson.decodeFromString(ListSerializer(Download.serializer()), Http.get("$API/downloads?limit=100", auth(token)))
}
