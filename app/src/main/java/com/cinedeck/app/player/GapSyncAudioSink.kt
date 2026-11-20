package com.cinedeck.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Keeps audio lined up with video across small holes in a stream's audio track.
 *
 * Files spliced at scene or ad-break cuts often have a few dozen milliseconds of audio missing (or doubled) at the
 * cut. The stock sink only re-aligns when a jump exceeds 200 ms; anything smaller is played back-to-back, so the
 * sound ends up early or late by that amount and several cuts add up over an episode. This wrapper compares each
 * buffer's timestamp with where the audio written so far says it should be, and once they disagree by more than
 * [THRESHOLD_US] it tells the sink to re-anchor on the buffer's own timestamp. Every correction is logged.
 */
class GapSyncAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var frameSize = 0
    private var sampleRate = 0
    private var anchorUs = C.TIME_UNSET
    private var framesSinceAnchor = 0L
    private var lastPtsUs = C.TIME_UNSET
    private var retrying = false
    private var corrections = 0
    private var totalUs = 0L

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // Only decoded PCM has a fixed frame size we can count; passthrough bitstreams are left alone.
        if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && inputFormat.channelCount > 0 && inputFormat.sampleRate > 0) {
            frameSize = Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount)
            sampleRate = inputFormat.sampleRate
        } else { frameSize = 0; sampleRate = 0 }
        anchorUs = C.TIME_UNSET
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // The sink may be handed the same buffer several times until it has room; count it once.
        val first = !(retrying && presentationTimeUs == lastPtsUs)
        if (first && frameSize > 0) {
            if (anchorUs == C.TIME_UNSET) { anchorUs = presentationTimeUs; framesSinceAnchor = 0 }
            val expectedUs = anchorUs + framesSinceAnchor * 1_000_000L / sampleRate
            val gapUs = presentationTimeUs - expectedUs
            if (abs(gapUs) >= THRESHOLD_US) {
                if (abs(gapUs) <= SINK_LIMIT_US) {
                    super.handleDiscontinuity() // re-anchor on this buffer's timestamp
                    corrections++; totalUs += abs(gapUs)
                    if (corrections <= 40 || corrections % 25 == 0) PlayerLog.add(
                        "Audio ${if (gapUs > 0) "gap" else "overlap"} of ${abs(gapUs) / 1000} ms in the stream at ${clock(presentationTimeUs)} — re-synced (#$corrections, ${totalUs / 1000} ms total)"
                    )
                } // larger jumps: the sink notices and corrects those by itself
                anchorUs = presentationTimeUs; framesSinceAnchor = 0
            }
            framesSinceAnchor += buffer.remaining() / frameSize
        }
        lastPtsUs = presentationTimeUs
        val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        retrying = !done
        return done
    }

    override fun flush() { anchorUs = C.TIME_UNSET; retrying = false; super.flush() }
    override fun reset() { anchorUs = C.TIME_UNSET; retrying = false; super.reset() }

    private fun clock(ptsUs: Long): String {
        val s = (ptsUs % 1_000_000_000_000L) / 1_000_000 // renderer timestamps carry a large fixed offset
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    private companion object {
        const val THRESHOLD_US = 25_000L
        const val SINK_LIMIT_US = 200_000L
    }
}
