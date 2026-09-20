package com.alexvas.rtsp.parser

import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Depacketizes AV1 RTP payload per the AV1 RTP Payload Format spec
 * (https://aomediacodec.github.io/av1-rtp-spec/).
 *
 * Each RTP packet starts with a 1-byte aggregation header (Z|Y|W|N|rsvd) followed by
 * one or more OBU elements. OBU elements carry no obu_size field on the wire (it is
 * implied by the aggregation header/length prefixes instead), and an OBU element can be
 * fragmented across several RTP packets (Z: first element continues a previous fragment,
 * Y: last element continues into the next packet).
 *
 * Reassembled OBUs are re-serialized with an explicit obu_size field (the "low overhead
 * bitstream format") and concatenated into a single temporal unit, flushed once the RTP
 * marker bit closes it - the same shape H.264/H.265 decoders get from the other parsers,
 * just without NAL start codes since AV1 has no Annex B equivalent.
 */
class RtpAv1Parser : RtpParser() {

    private var temporalUnit = ByteArrayOutputStream()
    // OBU element started in an earlier packet (Y=1) still waiting for its Z=1 continuation.
    private var fragment: ByteArrayOutputStream? = null

    override fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int, marker: Boolean): ByteArray? {
        if (DEBUG) Log.v(TAG, "processRtpPacketAndGetNalUnit(length=$length, marker=$marker)")
        if (length < 1) return null

        val aggregationHeader = data[0].toInt() and 0xFF
        val firstElementIsFragmentContinuation = (aggregationHeader and 0x80) != 0 // Z
        val lastElementContinuesInNextPacket = (aggregationHeader and 0x40) != 0   // Y
        val obuCount = (aggregationHeader shr 4) and 0x03                          // W: 0 = unspecified count

        var offset = 1
        var elementIndex = 0
        while (offset < length) {
            elementIndex++
            // Per spec, when W != 0 only the last of the W elements omits the length field.
            val isLastOfExpectedCount = obuCount != 0 && elementIndex == obuCount
            val elementLength: Int
            if (obuCount == 0 || !isLastOfExpectedCount) {
                val leb128 = readLeb128(data, offset, length)
                if (leb128 == null) {
                    Log.e(TAG, "Malformed AV1 OBU element length")
                    break
                }
                elementLength = leb128.first.toInt()
                offset = leb128.second
            } else {
                elementLength = length - offset
            }
            if (elementLength < 0 || offset + elementLength > length) {
                Log.e(TAG, "AV1 OBU element length $elementLength out of packet bounds")
                break
            }

            val isFirstElement = elementIndex == 1
            val isLastElementInPacket = offset + elementLength >= length
            val isFragmentContinuation = isFirstElement && firstElementIsFragmentContinuation
            val isFragmentIncomplete = isLastElementInPacket && lastElementContinuesInNextPacket

            if (isFragmentContinuation) {
                val pending = fragment
                if (pending == null) {
                    Log.w(TAG, "AV1 OBU fragment continuation received without a start. Dropped.")
                } else {
                    pending.write(data, offset, elementLength)
                    if (!isFragmentIncomplete) {
                        temporalUnit.write(reconstructObu(pending.toByteArray()))
                        fragment = null
                    }
                }
            } else if (isFragmentIncomplete) {
                fragment = ByteArrayOutputStream().apply { write(data, offset, elementLength) }
            } else {
                temporalUnit.write(reconstructObu(data.copyOfRange(offset, offset + elementLength)))
            }

            offset += elementLength
        }

        if (marker) {
            val result = temporalUnit.toByteArray()
            temporalUnit = ByteArrayOutputStream()
            return if (result.isNotEmpty()) result else null
        }
        return null
    }

    /**
     * RTP OBU elements arrive with obu_has_size_field cleared (the RTP framing conveys the
     * size instead). Reinserts the size field so the OBU is self-delimiting again, as
     * required by decoders consuming the concatenated elementary stream.
     */
    private fun reconstructObu(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return raw
        val hasExtension = (raw[0].toInt() and 0x04) != 0
        val headerLength = if (hasExtension) 2 else 1
        if (raw.size < headerLength) return raw

        val payloadLength = raw.size - headerLength
        val sizeField = writeLeb128(payloadLength)
        val result = ByteArray(headerLength + sizeField.size + payloadLength)
        System.arraycopy(raw, 0, result, 0, headerLength)
        result[0] = (result[0].toInt() or 0x02).toByte() // obu_has_size_field = 1
        System.arraycopy(sizeField, 0, result, headerLength, sizeField.size)
        System.arraycopy(raw, headerLength, result, headerLength + sizeField.size, payloadLength)
        return result
    }

    override fun reset() {
        if (DEBUG) Log.v(TAG, "reset()")
        super.reset()
        temporalUnit = ByteArrayOutputStream()
        fragment = null
    }

    /** Reads a leb128-encoded value. Returns (value, offset right after it) or null if malformed. */
    private fun readLeb128(data: ByteArray, offset: Int, limit: Int): Pair<Long, Int>? {
        var value = 0L
        var pos = offset
        for (i in 0 until 8) {
            if (pos >= limit) return null
            val b = data[pos].toInt() and 0xFF
            pos++
            value = value or ((b and 0x7F).toLong() shl (i * 7))
            if (b and 0x80 == 0) return Pair(value, pos)
        }
        return null
    }

    private fun writeLeb128(valueIn: Int): ByteArray {
        var value = valueIn
        val out = ByteArrayOutputStream()
        do {
            var b = value and 0x7F
            value = value ushr 7
            if (value != 0) b = b or 0x80
            out.write(b)
        } while (value != 0)
        return out.toByteArray()
    }

    companion object {
        private val TAG: String = RtpAv1Parser::class.java.simpleName
        private const val DEBUG = false
    }

}
