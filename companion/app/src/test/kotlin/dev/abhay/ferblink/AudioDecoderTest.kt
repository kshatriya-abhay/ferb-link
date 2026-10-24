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
}
