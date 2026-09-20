package com.alexvas.rtsp.codec

import android.media.*
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder


class AudioDecodeThread (
        private val mimeType: String,
        private val sampleRate: Int,
        private val channelCount: Int,
        private val codecConfig: ByteArray?,
        private val audioFrameQueue: AudioFrameQueue) : Thread() {

    private var isRunning = true

    @Volatile
    var audioDecoderName: String? = null
        private set

    @Volatile
    private var audioTrack: AudioTrack? = null

    /**
     * Playback volume gain in range 0.0 (silence) .. 1.0 (max, unmodified signal).
     * Can be changed at any time, before or after the thread is started.
     */
    @Volatile
    var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            audioTrack?.setVolume(field)
        }

    fun stopAsync() {
        if (DEBUG) Log.v(TAG, "stopAsync()")
        isRunning = false
        // Wake up sleep() code
        interrupt()
    }

    override fun run() {
        if (DEBUG) Log.d(TAG, "$name started")

        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Creating audio decoder
        val decoder = MediaCodec.createDecoderByType(mimeType)
        val format = MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount)

        if (mimeType == MediaFormat.MIMETYPE_AUDIO_AAC) {
            val csd0 = codecConfig ?: getAacDecoderConfigData(MediaCodecInfo.CodecProfileLevel.AACObjectLC, sampleRate, channelCount)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        } else if (mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            // Android's Opus decoder expects three codec-specific-data buffers
            // mirroring the Ogg Opus header layout (see RFC 7845 §5.1 and
            // ExoPlayer's OpusUtil, which this follows):
            //  csd-0: Opus identification header ("OpusHead" + stream params)
            //  csd-1: pre-skip / codec delay in nanoseconds (8 bytes, little-endian)
            //  csd-2: seek pre-roll in nanoseconds (8 bytes, little-endian)
            // RTSP/SDP for Opus carries no equivalent of these values, so
            // pre-skip and seek pre-roll are sent as zero (no adjustment).
            val csd0 = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            csd0.put("OpusHead".toByteArray(Charsets.US_ASCII)) // Magic signature
            csd0.put(1)                           // Version
            csd0.put(channelCount.toByte())       // Channel count
            csd0.putShort(0)                      // Pre-skip
            csd0.putInt(sampleRate)               // Input sample rate (Hz)
            csd0.putShort(0)                      // Output gain (Q7.8 in dB)
            csd0.put(0)                           // Channel mapping family
            val csd1 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L)
            val csd2 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0L)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.array()))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.array()))
            format.setByteBuffer("csd-2", ByteBuffer.wrap(csd2.array()))
        }

        decoder.configure(format, null, null, 0)
        decoder.start()
        audioDecoderName = decoder.name

        // Creating audio playback device
        val outChannel = if (channelCount > 1) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val outAudio = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, outChannel, outAudio)
//      Log.i(TAG, "sampleRate: $sampleRate, bufferSize: $bufferSize".format(sampleRate, bufferSize))
        val audioTrack = AudioTrack(
                AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                AudioFormat.Builder()
                        .setEncoding(outAudio)
                        .setChannelMask(outChannel)
                        .setSampleRate(sampleRate)
                        .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                0)
        this.audioTrack = audioTrack
        // Apply volume requested before the track was created
        audioTrack.setVolume(volume)
        audioTrack.play()

        val bufferInfo = MediaCodec.BufferInfo()
        while (isRunning) {
            val inIndex: Int = decoder.dequeueInputBuffer(10000L)
            if (inIndex >= 0) {
                // fill inputBuffers[inputBufferIndex] with valid data
                var byteBuffer: ByteBuffer?
                try {
                    byteBuffer = decoder.getInputBuffer(inIndex)
                } catch (e: Exception) {
                    e.printStackTrace()
                    break
                }
                byteBuffer?.rewind()

                // Preventing BufferOverflowException
//              if (length > byteBuffer.limit()) throw DecoderFatalException("Error")

                val audioFrame: FrameQueue.Frame?
                try {
                    audioFrame = audioFrameQueue.pop()
                    if (audioFrame == null) {
                        Log.d(TAG, "Empty audio frame")
                        // Release input buffer
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                    } else {
                        byteBuffer?.put(audioFrame.data, audioFrame.offset, audioFrame.length)
                        decoder.queueInputBuffer(inIndex, audioFrame.offset, audioFrame.length, audioFrame.timestampMs, 0)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
//            Log.i(TAG, "inIndex: ${inIndex}")

            try {
//                Log.w(TAG, "outIndex: ${outIndex}")
                if (!isRunning) break
                when (val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(TAG, "Decoder format changed: ${decoder.outputFormat}")
                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (DEBUG) Log.d(TAG, "No output from decoder available")
                    else -> {
                        if (outIndex >= 0) {
                            val byteBuffer: ByteBuffer? = decoder.getOutputBuffer(outIndex)

                            val chunk = ByteArray(bufferInfo.size)
                            byteBuffer?.get(chunk)
                            byteBuffer?.clear()

                            if (chunk.isNotEmpty()) {
                                audioTrack.write(chunk, 0, chunk.size)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // All decoded frames have been rendered, we can stop playing now
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                break
            }
        }
        audioTrack.flush()
        audioTrack.release()
        this.audioTrack = null

        audioDecoderName = null
        try {
            decoder.stop()
            decoder.release()
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioFrameQueue.clear()
        if (DEBUG) Log.d(TAG, "$name stopped")
    }

    companion object {
        private val TAG: String = AudioDecodeThread::class.java.simpleName
        private const val DEBUG = false

        fun getAacDecoderConfigData(audioProfile: Int, sampleRate: Int, channels: Int): ByteArray {
            // AOT_LC = 2
            // 0001 0000 0000 0000
            var extraDataAac = audioProfile shl 11
            // Sample rate
            when (sampleRate) {
                7350 -> extraDataAac = extraDataAac or (0xC shl 7)
                8000 -> extraDataAac = extraDataAac or (0xB shl 7)
                11025 -> extraDataAac = extraDataAac or (0xA shl 7)
                12000 -> extraDataAac = extraDataAac or (0x9 shl 7)
                16000 -> extraDataAac = extraDataAac or (0x8 shl 7)
                22050 -> extraDataAac = extraDataAac or (0x7 shl 7)
                24000 -> extraDataAac = extraDataAac or (0x6 shl 7)
                32000 -> extraDataAac = extraDataAac or (0x5 shl 7)
                44100 -> extraDataAac = extraDataAac or (0x4 shl 7)
                48000 -> extraDataAac = extraDataAac or (0x3 shl 7)
                64000 -> extraDataAac = extraDataAac or (0x2 shl 7)
                88200 -> extraDataAac = extraDataAac or (0x1 shl 7)
                96000 -> extraDataAac = extraDataAac or (0x0 shl 7)
            }
            // Channels
            extraDataAac = extraDataAac or (channels shl 3)
            val extraData = ByteArray(2)
            extraData[0] = (extraDataAac and 0xff00 shr 8).toByte() // high byte
            extraData[1] = (extraDataAac and 0xff).toByte()         // low byte
            return extraData
        }
    }

}

