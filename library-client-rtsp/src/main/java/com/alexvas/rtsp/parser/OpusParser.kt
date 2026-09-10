package com.alexvas.rtsp.parser

// https://tools.ietf.org/html/rfc7587
// Unlike AAC's AU header section, the Opus RTP payload has no extra
// framing at all: each RTP packet carries exactly one Opus packet
// (frame), fed to the decoder as-is.
class OpusParser : AudioParser() {
    override fun processRtpPacketAndGetSamples(
        data: ByteArray,
        length: Int
    ): List<ByteArray> {
        return listOf(data.copyOfRange(0, length))
    }
}
