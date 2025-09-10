package com.alexvas.rtsp.parser

class G711Parser(
    g711Mode: String
) : AudioParser() {

    companion object {
        const val MODE_ALAW = 0
        const val MODE_MLAW = 1
    }

    private val _g711Mode = when (g711Mode.lowercase()) {
        "pcmu", "ulaw", "mulaw" -> MODE_MLAW
        "pcma", "alaw" -> MODE_ALAW
        else -> MODE_ALAW
    }

    override fun processRtpPacketAndGetSample(
        data: ByteArray,
        length: Int
    ): ByteArray? {
        if (length <= 12) return null // 太短，不是合法 RTP 包

        // RTP 基本头部长度
        val rtpHeaderLen = 0

        // 提取 G711 Payload
        val g711Payload = data.copyOfRange(rtpHeaderLen, length)

        return g711Payload
//        if (length <= 12) return null // 太短，不是合法 RTP 包
//
//        // RTP 基本头部长度
//        val rtpHeaderLen = 12
//
//        // 提取 G711 Payload
//        val g711Payload = data.copyOfRange(rtpHeaderLen, length)
//
//        return g711Payload
    }
}
