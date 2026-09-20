package com.alexvas.rtsp.parser

abstract class RtpParser {

    abstract fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int, marker: Boolean): ByteArray?

    // TODO Use already allocated buffer with RtpPacket.MAX_SIZE = 65507
    // Used only for fragmented packets
    protected val fragmentedBuffer = arrayOfNulls<ByteArray>(1024)
    protected var fragmentedBufferLength = 0
    protected var fragmentedPackets = 0

    /**
     * Discards any access unit reassembly in progress. Call this when RTP packet loss is
     * detected (e.g. a sequence-number gap over an unreliable transport such as UDP), so a NAL
     * unit missing a fragment is dropped instead of being silently corrupted and handed to the
     * decoder. Subclasses with their own accumulated state (e.g. a per-access-unit output
     * stream) should override this and also clear it, calling super.reset().
     */
    open fun reset() {
        clearFragmentedBuffer()
    }

    protected fun clearFragmentedBuffer() {
        for (i in 0..minOf(fragmentedPackets, fragmentedBuffer.lastIndex)) {
            fragmentedBuffer[i] = null
        }
        fragmentedPackets = 0
        fragmentedBufferLength = 0
    }

    protected fun writeNalPrefix0001(buffer: ByteArray) {
        buffer[0] = 0x00
        buffer[1] = 0x00
        buffer[2] = 0x00
        buffer[3] = 0x01
    }

    protected fun processSingleFramePacket(data: ByteArray, length: Int): ByteArray {
        return ByteArray(4 + length).apply {
            writeNalPrefix0001(this)
            System.arraycopy(data, 0, this, 4, length)
        }
    }

}
