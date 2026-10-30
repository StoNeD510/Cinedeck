package com.cinedeck.app.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.cinedeck.app.data.AppJson
import com.cinedeck.app.data.PlayRequest
import com.cinedeck.app.data.Store

object PlayerLauncher {
    const val EXTRA = "play_request"

    fun play(ctx: Context, req: PlayRequest) {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(EXTRA, AppJson.encodeToString(PlayRequest.serializer(), req)))
    }

    /** Hand the stream to another installed player (MX Player, Just Player, VLC app…). */
    fun external(ctx: Context, req: PlayRequest) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(req.url), "video/*")
            .putExtra("title", req.name + (req.season?.let { " S${it}E${req.episode}" } ?: ""))
            .putExtra("position", Store.data.value.progress[req.videoId]?.position?.toInt() ?: 0)
        try {
            ctx.startActivity(Intent.createChooser(i, "Play with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No external video player installed", Toast.LENGTH_LONG).show()
        }
    }

    fun openUrl(ctx: Context, url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }
}
