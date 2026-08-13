package com.alexvas.rtsp.parser

abstract class AudioParser {
    /**
     * One RTP packet's audio payload can contain zero, one, or several
     * complete access units (e.g. AAC-hbr's "multiple AU per packet" mode,
     * RFC 3640 §3.3.6) — returns each one ready to decode/play, in order.
     * Renamed from the original processRtpPacketAndGetSample (singular,
     * ByteArray?) — that shape couldn't express "this packet held 3 AAC
     * frames back to back", which is the common case for a low-bitrate
     * stream (small AAC frames packed several-to-a-packet), and silently
     * returning an empty array for that case was the actual root cause of
     * audio-only playback going permanently silent.
     */
    abstract fun processRtpPacketAndGetSamples(
        data: ByteArray,
        length: Int
    ): List<ByteArray>
}
