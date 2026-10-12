package dev.abhay.ferblink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * Whether a recording could hold speech at all, before the model hears it. Gemma 4 E2B, given
 * steady noise, writes down a command it imagines ("call mom") and is sure of it, the longer the
 * clip the likelier. Speech rises and falls several times a second; room noise, hiss and hum stay
 * level. So: the spread between the loud and quiet 20 ms frames (90th against 10th percentile of
 * their energy). Measured 2026-10-12 on Opus round-trips: silence, hiss, hum and pink or brown
 * noise of 1 to 30 s spread 0 to 6 dB; commands, alone or in noise, 30 dB and more.
 */
object SpeechCheck {
    /** Below this spread a recording is taken as no speech. Between 6 (noise) and 30 (speech). */
    const val MIN_SPREAD_DB = 12.0

    /** The frame-energy spread in dB of a 16-bit PCM WAV file, or null when it isn't one. */
    fun spreadDb(wav: ByteArray): Double? {
        val (samples, rate) = pcm16Mono(wav) ?: return null
        val frame = rate / 50
        if (frame <= 0 || samples.size < frame * 5) return 0.0
        val energies = (0 until samples.size / frame).map { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += samples[i].toDouble() * samples[i]
            10 * log10(sum / frame + 1e-9)
        }.sorted()
        fun at(q: Double) = energies[(q * (energies.size - 1)).toInt()]
        return at(0.9) - at(0.1)
    }

    /** False only when the spread says no speech; true when it can't tell (not 16-bit PCM WAV). */
    fun mayHoldSpeech(wav: ByteArray): Boolean = (spreadDb(wav) ?: Double.MAX_VALUE) >= MIN_SPREAD_DB

    /** The samples (channels mixed down) and rate of a 16-bit PCM WAV file. */
    private fun pcm16Mono(wav: ByteArray): Pair<ShortArray, Int>? {
        if (wav.size < 12 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var at = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (at + 8 <= wav.size) {
            val id = String(wav, at, 4, Charsets.US_ASCII)
            val size = buffer.getInt(at + 4)
            if (size < 0) return null
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (buffer.getShort(body).toInt() != 1) return null // PCM only
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (bits != 16 || channels < 1 || rate <= 0) return null
                    val end = minOf(body + size, wav.size)
                    val count = (end - body) / 2 / channels
                    val samples = ShortArray(count) { i ->
                        var sum = 0
                        for (c in 0 until channels) sum += buffer.getShort(body + (i * channels + c) * 2)
                        (sum / channels).toShort()
                    }
                    return samples to rate
                }
            }
            at = body + size + (size and 1)
        }
        return null
    }
}
