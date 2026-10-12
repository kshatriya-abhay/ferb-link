package dev.abhay.ferblink

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns Ferb's recording (Opus in OGG, AAC, or anything else Android decodes) into a WAV file for
 * the server: llama.cpp's server takes only WAV or MP3. Mono, 16-bit, and 16 kHz when the source
 * rate is a multiple of it (Opus decodes at 48 kHz), so a 10 s clip is about 320 KB to send.
 */
object AudioDecoder {
    class Unreadable(message: String) : Exception(message)

    private const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    /** WAV as it is; anything else decoded. Throws [Unreadable], with a reason for the user. */
    fun toWav(audio: ByteArray, mime: String?): ByteArray {
        if (mime in WAV_TYPES || isWav(audio)) return audio
        val (pcm, rate, channels) = decode(audio)
        var samples = mono(pcm, channels)
        var outRate = rate
        if (rate > TARGET_RATE && rate % TARGET_RATE == 0) {
            samples = downsample(samples, rate / TARGET_RATE)
            outRate = TARGET_RATE
        }
        if (samples.size > outRate.toLong() * (LinkContract.MAX_AUDIO_SECONDS + 1)) {
            throw Unreadable("The recording is over ${LinkContract.MAX_AUDIO_SECONDS} s")
        }
        return wav(samples, outRate)
    }

    /** About how long a WAV file is, from its header's bytes per second. */
    fun seconds(wav: ByteArray): Double {
        if (wav.size < 44) return 0.0
        val byteRate = ByteBuffer.wrap(wav, 28, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return if (byteRate <= 0) 0.0 else (wav.size - 44).toDouble() / byteRate
    }

    private val WAV_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")

    private fun isWav(audio: ByteArray) = audio.size > 12 &&
        String(audio, 0, 4, Charsets.US_ASCII) == "RIFF" && String(audio, 8, 4, Charsets.US_ASCII) == "WAVE"

    /** 16-bit PCM, interleaved, with its rate and channel count. */
    private fun decode(audio: ByteArray): Triple<ShortArray, Int, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                extractor.setDataSource(BytesSource(audio))
            } catch (e: Exception) {
                throw Unreadable("Couldn't read the recording: ${e.message ?: "unknown format"}")
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Unreadable("The recording has no audio")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = codec.outputFormat
                    rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
                    ) throw Unreadable("The decoder gave an unexpected sample format")
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    val chunk = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            val bytes = out.toByteArray()
            val pcm = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
            return Triple(pcm, rate, channels)
        } catch (e: Unreadable) {
            throw e
        } catch (e: Exception) {
            throw Unreadable("Couldn't decode the recording: ${e.message ?: e}")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun mono(pcm: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return pcm
        return ShortArray(pcm.size / channels) { i ->
            var sum = 0
            for (c in 0 until channels) sum += pcm[i * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Averages each [factor] samples: a crude low-pass, enough for speech. */
    private fun downsample(pcm: ShortArray, factor: Int) = ShortArray(pcm.size / factor) { i ->
        var sum = 0
        for (k in 0 until factor) sum += pcm[i * factor + k]
        (sum / factor).toShort()
    }

    private fun wav(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1) // PCM, mono
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }

    private class BytesSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun getSize() = bytes.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size, bytes.size - position.toInt())
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }
        override fun close() {}
    }
}
