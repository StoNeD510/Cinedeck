package com.cinedeck.app.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Keeps the HDMI / ARC audio link alive while a video is paused.
 *
 * When playback pauses, the player stops feeding audio and the TV drops the audio stream; a soundbar on ARC
 * then sees "no signal" and powers itself down after its idle timer, and often doesn't come back when the
 * movie resumes. Streaming digital silence through a normal audio track keeps the stream (and the soundbar) up.
 */
object AudioKeepAlive {
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        val rate = 48_000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
        val t = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(min * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        track = t
        val silence = ByteArray(min)
        thread = Thread({
            runCatching {
                t.play()
                while (!Thread.currentThread().isInterrupted) {
                    if (t.write(silence, 0, silence.size) < 0) break // blocking write paces itself to real time
                }
            }
            runCatching { t.stop() }
            runCatching { t.release() }
        }, "audio-keepalive").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        PlayerLog.add("Audio keep-alive on (paused)")
    }

    @Synchronized
    fun stop() {
        val th = thread ?: return
        thread = null
        th.interrupt()
        track?.let { runCatching { it.pause(); it.flush() } } // unblocks a pending write so the thread can exit
        track = null
        PlayerLog.add("Audio keep-alive off")
    }
}
