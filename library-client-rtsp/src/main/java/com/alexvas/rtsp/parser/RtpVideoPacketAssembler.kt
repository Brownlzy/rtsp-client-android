package com.alexvas.rtsp.parser

import android.util.Log

/** Keeps packet loss from turning a partial access unit into a decoder input sample. */
class RtpVideoPacketAssembler(private val parser: RtpParser) {
    private var sequence = -1
    private var timestamp = -1L
    private var ssrc = -1L
    private var damaged = false

    fun process(header: RtpHeaderParser.RtpHeader, payload: ByteArray, length: Int): ByteArray? {
        if (ssrc != header.ssrc) {
            parser.reset()
            sequence = -1
            timestamp = -1L
            damaged = false
            ssrc = header.ssrc
        }
        val delta = (header.sequenceNumber - sequence) and 0xffff
        // A duplicate or late packet must not rewind the sequence or reset a newer frame.
        if (sequence != -1 && (delta == 0 || delta >= 0x8000)) return null

        if (timestamp != header.timeStamp) {
            // In particular, discard an unfinished frame whose marker packet was lost.
            parser.reset()
            timestamp = header.timeStamp
            damaged = false
        }
        if (sequence != -1 && delta != 1) {
            Log.w("RtpVideoPacketAssembler", "Video RTP gap: expected ${(sequence + 1) and 0xffff}, " +
                "got ${header.sequenceNumber}; dropping access unit ${header.timeStamp}")
            parser.reset()
            // The missing packet could contain another slice of this same frame. A fresh
            // FU start is not enough to make the whole access unit safe to decode.
            damaged = true
        }
        sequence = header.sequenceNumber
        if (damaged) return null
        return parser.processRtpPacketAndGetNalUnit(payload, length, header.marker == 1)
    }
}
