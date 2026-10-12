package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class SpeechCheckTest {
    private val random = Random(7)

    @Test
    fun `silence and steady noise hold no speech`() {
        assertThat(SpeechCheck.mayHoldSpeech(wav(ShortArray(48_000)))).isFalse()
        assertThat(SpeechCheck.mayHoldSpeech(wav(noise(seconds = 3.0) { 600 }))).isFalse()
        assertThat(SpeechCheck.mayHoldSpeech(wav(noise(seconds = 30.0) { 600 }))).isFalse()
    }

    @Test
    fun `sound that rises and falls like syllables may hold speech, even over noise`() {
        // Loud for 120 ms, quiet for 130 ms, four times a second, over steady noise.
        val syllables = noise(seconds = 2.0) { i -> if (i % 4_000 < 1_920) 12_000 else 600 }
        assertThat(SpeechCheck.mayHoldSpeech(wav(syllables))).isTrue()
    }

    @Test
    fun `anything that isn't 16-bit PCM WAV is passed on`() {
        assertThat(SpeechCheck.spreadDb(byteArrayOf(1, 2, 3))).isNull()
        assertThat(SpeechCheck.mayHoldSpeech(byteArrayOf(1, 2, 3))).isTrue()
    }

    @Test
    fun `stereo is mixed down`() {
        val mono = noise(seconds = 2.0) { i -> if (i % 4_000 < 1_920) 12_000 else 600 }
        val stereo = ShortArray(mono.size * 2) { mono[it / 2] }
        assertThat(SpeechCheck.spreadDb(wav(stereo, channels = 2))!!).isWithin(0.5).of(SpeechCheck.spreadDb(wav(mono))!!)
    }

    private fun noise(seconds: Double, amplitude: (Int) -> Int) =
        ShortArray((seconds * 16_000).toInt()) { i -> (random.nextDouble(-1.0, 1.0) * amplitude(i)).toInt().toShort() }

    private fun wav(samples: ShortArray, channels: Int = 1, rate: Int = 16_000): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(rate); putInt(rate * 2 * channels); putShort((2 * channels).toShort()); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}
