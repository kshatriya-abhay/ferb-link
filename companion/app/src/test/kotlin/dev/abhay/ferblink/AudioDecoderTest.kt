package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The steps around Android's decoder (MediaCodec itself only runs on the phone). */
class AudioDecoderTest {
    @Test
    fun `WAV goes on as it is, by type or by its header`() {
        val wav = AudioDecoder.wav(ShortArray(160), 16_000)
        assertThat(AudioDecoder.toWav(wav, "audio/ogg")).isSameInstanceAs(wav)
        // Said to be WAV, but no header to read its length from.
        assertThrows(AudioDecoder.Unreadable::class.java) { AudioDecoder.toWav(byteArrayOf(1, 2, 3), "audio/wav") }
    }

    @Test
    fun `WAV over the time limit is turned down, by its header`() {
        val ok = AudioDecoder.wav(ShortArray(16_000 * LinkContract.MAX_AUDIO_SECONDS), 16_000)
        assertThat(AudioDecoder.toWav(ok, "audio/wav")).isSameInstanceAs(ok)
        // 8 kHz 8-bit-sized: 40 s in under 512 KB, which the byte cap alone let through.
        val long = AudioDecoder.wav(ShortArray(8_000 * 40), 8_000)
        val e = assertThrows(AudioDecoder.Unreadable::class.java) { AudioDecoder.toWav(long, null) }
        assertThat(e.message).contains("over ${LinkContract.MAX_AUDIO_SECONDS} s")
        val noRate = AudioDecoder.wav(ShortArray(160), 16_000).also { java.nio.ByteBuffer.wrap(it, 28, 4).putInt(0) }
        assertThrows(AudioDecoder.Unreadable::class.java) { AudioDecoder.toWav(noRate, "audio/wav") }
    }

    @Test
    fun `decoding stops at the time limit or the byte ceiling, whatever the format says`() {
        val limit = 48_000L * 2 * 2 * (LinkContract.MAX_AUDIO_SECONDS + 1)
        assertThat(AudioDecoder.overLimit(limit, 48_000, 2)).isFalse()
        assertThat(AudioDecoder.overLimit(limit + 1, 48_000, 2)).isTrue()
        // 16 kHz mono: 31 s is the most.
        assertThat(AudioDecoder.overLimit(16_000L * 2 * 31, 16_000, 1)).isFalse()
        assertThat(AudioDecoder.overLimit(16_000L * 2 * 31 + 2, 16_000, 1)).isTrue()
        // A format claiming 8 channels at 192 kHz still stops at the ceiling.
        assertThat(AudioDecoder.overLimit(AudioDecoder.MAX_DECODED_BYTES + 1, 192_000, 8)).isTrue()
    }

    @Test
    fun `48 kHz stereo becomes 16 kHz mono`() {
        val stereo = ShortArray(48_000 * 2) { if (it % 2 == 0) 1_000 else 3_000 }
        val wav = AudioDecoder.fromPcm(stereo, 48_000, 2)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(header.getShort(22).toInt()).isEqualTo(1) // channels
        assertThat(header.getInt(24)).isEqualTo(16_000)
        assertThat(header.getShort(44).toInt()).isEqualTo(2_000)
        assertThat(AudioDecoder.seconds(wav)).isWithin(0.01).of(1.0)
    }

    @Test
    fun `a rate that isn't a multiple of 16 kHz is kept`() {
        val wav = AudioDecoder.fromPcm(ShortArray(44_100), 44_100, 1)
        assertThat(ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24)).isEqualTo(44_100)
    }

    @Test
    fun `over the longest recording is refused`() {
        val seconds = LinkContract.MAX_AUDIO_SECONDS + 2
        assertThrows(AudioDecoder.Unreadable::class.java) { AudioDecoder.fromPcm(ShortArray(16_000 * seconds), 16_000, 1) }
    }

    @Test
    fun `a WAV with a chunk before fmt goes on as it is`() {
        val plain = AudioDecoder.wav(ShortArray(16_000), 16_000)
        val junk = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).put("JUNK".toByteArray()).putInt(28).array()
        val wav = plain.copyOfRange(0, 12) + junk + plain.copyOfRange(12, plain.size)
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(4, wav.size - 8)
        assertThat(AudioDecoder.toWav(wav, "audio/wav")).isSameInstanceAs(wav)
        assertThat(AudioDecoder.seconds(wav)).isWithin(0.01).of(1.0)
    }

    @Test
    fun `a WAV whose fmt chunk is cut short is unreadable, not passed on`() {
        val e = assertThrows(AudioDecoder.Unreadable::class.java) { AudioDecoder.toWav(BARE_FMT, "audio/wav") }
        assertThat(e.message).contains("no usable header")
    }

    @Test
    fun `a WAV with no data chunk is unreadable`() {
        val noData = AudioDecoder.wav(ShortArray(16_000), 16_000).copyOfRange(0, 36)
        assertThrows(AudioDecoder.Unreadable::class.java) { AudioDecoder.toWav(noData, "audio/wav") }
    }

    companion object {
        /**
         * 60 bytes that the old fixed-offset check passed: RIFF/WAVE, a LIST chunk over bytes 20-52
         * (so offset 28 reads 32000), then "fmt " with size 0 and no body at the very end.
         */
        val BARE_FMT: ByteArray = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(52); put("WAVE".toByteArray())
            put("LIST".toByteArray()); putInt(32); putInt(0); putInt(0); putInt(32_000)
            position(52); put("fmt ".toByteArray()); putInt(0)
        }.array()
    }
}
