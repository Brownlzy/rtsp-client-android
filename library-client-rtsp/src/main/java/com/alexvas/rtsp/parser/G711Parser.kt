package com.alexvas.rtsp.parser

class G711Parser() : AudioParser() {
    override fun processRtpPacketAndGetSamples(
        data: ByteArray,
        length: Int
    ): List<ByteArray> {
        return listOf(data.copyOfRange(0, length))
    }
}
