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
        val notWav = byteArrayOf(1, 2, 3)
        assertThat(AudioDecoder.toWav(notWav, "audio/wav")).isSameInstanceAs(notWav)
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
