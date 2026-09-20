package com.alexvas.rtsp.parser

import org.junit.Assert.*
import org.junit.Test

class RtpVideoPacketAssemblerTest {
    private fun packet(seq: Int, ts: Long = 100, marker: Boolean = false, ssrc: Long = 1) =
        RtpHeaderParser.RtpHeader().apply {
            sequenceNumber = seq
            timeStamp = ts
            this.marker = if (marker) 1 else 0
            this.ssrc = ssrc
        }

    private fun send(a: RtpVideoPacketAssembler, seq: Int, ts: Long, marker: Boolean, vararg bytes: Int) =
        a.process(packet(seq, ts, marker), bytes.map { it.toByte() }.toByteArray(), bytes.size)

    @Test fun intactFragmentedH264() {
        val a = RtpVideoPacketAssembler(RtpH264Parser())
        assertNull(send(a, 10, 100, false, 0x7c, 0x85, 11))
        assertNull(send(a, 11, 100, false, 0x7c, 0x05, 22))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 11, 22, 33),
            send(a, 12, 100, true, 0x7c, 0x45, 33))
    }

    @Test fun lossDropsRemainingSlicesOfFrame() {
        val a = RtpVideoPacketAssembler(RtpH264Parser())
        send(a, 10, 100, false, 0x61, 11)
        // Packet 11 lost. Packet 12 is a complete slice, but the frame is incomplete.
        assertNull(send(a, 12, 100, true, 0x61, 33))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 44),
            send(a, 13, 200, true, 0x65, 44))
    }

    @Test fun duplicateAndLatePacketsDoNotBreakAssembly() {
        val a = RtpVideoPacketAssembler(RtpH264Parser())
        send(a, 10, 100, false, 0x7c, 0x85, 11)
        assertNull(send(a, 10, 100, false, 0x7c, 0x85, 11))
        assertNull(send(a, 9, 90, true, 0x61, 99))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 11, 33),
            send(a, 11, 100, true, 0x7c, 0x45, 33))
    }

    @Test fun missingMarkerDoesNotJoinFrames() {
        val a = RtpVideoPacketAssembler(RtpH264Parser())
        send(a, 10, 100, false, 0x61, 11)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 22),
            send(a, 11, 200, true, 0x65, 22))
    }

    @Test fun sequenceWrapIsContinuous() {
        val a = RtpVideoPacketAssembler(RtpH264Parser())
        send(a, 65535, 100, false, 0x7c, 0x85, 11)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 11, 33),
            send(a, 0, 100, true, 0x7c, 0x45, 33))
    }

    @Test fun newSsrcStartsFreshAssembly() {
        val a = RtpVideoPacketAssembler(RtpH264Parser())
        send(a, 10, 100, false, 0x61, 11)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 22),
            a.process(packet(1, 100, true, 2), byteArrayOf(0x65, 22), 2))
    }

    @Test fun h265HasNoTrailingBytes() {
        val a = RtpVideoPacketAssembler(RtpH265Parser())
        send(a, 10, 100, false, 0x62, 1, 0x93, 11)
        send(a, 11, 100, false, 0x62, 1, 0x13, 22)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x26, 1, 11, 22, 33),
            send(a, 12, 100, true, 0x62, 1, 0x53, 33))
    }

    @Test fun fragmentOverflowCanBeReset() {
        val p = RtpH264Parser()
        p.processRtpPacketAndGetNalUnit(byteArrayOf(0x7c, 0x85.toByte(), 11), 3, false)
        repeat(1100) { p.processRtpPacketAndGetNalUnit(byteArrayOf(0x7c, 5, 22), 3, false) }
        p.reset()
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 33),
            p.processRtpPacketAndGetNalUnit(byteArrayOf(0x65, 33), 2, true))
    }
}
