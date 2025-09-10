package com.alexvas.rtsp.codec

import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.view.Surface
import com.alexvas.rtsp.codec.ext.G711Code
import java.io.IOException
import java.nio.ByteBuffer

class MediaCodecExt(
    private val mediaCodec: MediaCodec?,
    private val mimeType: String
) {
    val outputFormat: MediaFormat
        get() {
            return if (isSoftwareDecoder) {
                val format = MediaFormat()
                format.setString(MediaFormat.KEY_MIME, "audio/raw")
                format.setInteger(MediaFormat.KEY_SAMPLE_RATE, softSampleRate)
                format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, softChannelCount)
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, softEncoding) // 2 = PCM 16-bit
                format
            } else {
                mediaCodec!!.outputFormat
            }
        }

    private var isSoftwareDecoder = false

    // 软件解码音频格式信息
    private var softSampleRate = 8000
    private var softChannelCount = 1
    private var softEncoding = 2 // PCM 16-bit

    fun configure(format: MediaFormat?, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        if (!isSoftwareDecoder) {
            mediaCodec?.configure(format, surface, crypto, flags)
        }
    }

    fun start() {
        if (!isSoftwareDecoder) mediaCodec?.start()
    }

    fun stop() {
        if (!isSoftwareDecoder) mediaCodec?.stop()
    }

    fun release() {
        if (!isSoftwareDecoder) mediaCodec?.release()
    }

    // 软件解码用缓冲池
    private val softInputBuffers = mutableMapOf<Int, ByteBuffer>()
    private val softInputBufferSize = 2048
    private var softInputIndexCounter = 0
    private val softInputBuffer: ShortArray = ShortArray(softInputBufferSize)

    // 固定缓冲区
    private val byteBufferOutput: ByteBuffer = ByteBuffer.allocate(2048 * 2)
    private var outputSize = 0
    private var outputReady = false

    fun dequeueInputBuffer(timeoutUs: Long): Int {
        return if (isSoftwareDecoder) {
            softInputIndexCounter++
            val index = softInputIndexCounter
            softInputBuffers[index] = ByteBuffer.allocate(softInputBufferSize)
            index
        } else {
            mediaCodec?.dequeueInputBuffer(timeoutUs) ?: -1
        }
    }

    fun getInputBuffer(index: Int): ByteBuffer? {
        return if (isSoftwareDecoder) {
            softInputBuffers[index]
        } else {
            mediaCodec?.getInputBuffer(index)
        }
    }

    fun queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int
    ) {
        if (isSoftwareDecoder) {
            val buffer = softInputBuffers.remove(index) ?: return

            // 从 ByteBuffer 中取出数据
            val inputData = ByteArray(size)
            buffer.position(offset)
            buffer.get(inputData, 0, size)

            // G711 → PCM16
            G711Code.G711aDecoder(softInputBuffer, inputData, size)

            // 转小端字节流
            byteBufferOutput.clear()
            for (i in 0 until size) {
                val s = softInputBuffer[i].toInt()
                byteBufferOutput.put((s and 0xFF).toByte())
                byteBufferOutput.put(((s shr 8) and 0xFF).toByte())
            }
            byteBufferOutput.flip()

            outputSize = size * 2
            outputReady = true
        } else {
            mediaCodec?.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
        }
    }

    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        return if (isSoftwareDecoder) {
            if (outputReady) {
                outputReady = false
                info.offset = 0
                info.size = outputSize
                info.presentationTimeUs = System.nanoTime() / 1000
                0
            } else {
                -1
            }
        } else {
            mediaCodec?.dequeueOutputBuffer(info, timeoutUs) ?: -1
        }
    }

    fun getOutputBuffer(index: Int): ByteBuffer? {
        return if (isSoftwareDecoder) byteBufferOutput else mediaCodec?.getOutputBuffer(index)
    }

    fun releaseOutputBuffer(index: Int, render: Boolean) {
        if (!isSoftwareDecoder) mediaCodec?.releaseOutputBuffer(index, render)
    }

    companion object {
        const val TAG = "MediaCodecExt"
        val supportedSoftMimeTypes = listOf(
            "audio/g711-alaw",
            "audio/g711-mlaw"
        )

        fun createDecoderByType(mimeType: String): MediaCodecExt {
            val codec = try {
                MediaCodec.createDecoderByType(mimeType)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }

            return if (codec!=null){
                MediaCodecExt(codec, mimeType)
//                MediaCodecExt(null, mimeType).apply {
//                    isSoftwareDecoder = true
//                }
            }else if (mimeType.lowercase() in supportedSoftMimeTypes) {
                MediaCodecExt(null, mimeType).apply {
                    isSoftwareDecoder = true
                }
            }else{
                throw IOException("Failed to initialize MediaCodec")
            }
        }
    }
}
