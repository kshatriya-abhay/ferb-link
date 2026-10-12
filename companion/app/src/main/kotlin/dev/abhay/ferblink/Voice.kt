package dev.abhay.ferblink

/**
 * A recording to a transcript, as Ferb Link answers `transcribe`: silence and steady noise never
 * reach the model ([SpeechCheck]), Devanagari becomes Latin letters ([Latin]), and amounts and
 * times are worked out ([Amounts]). Shared with the laptop harness (`mise run companion:harness`),
 * which passes ICU through `uconv` for [latin].
 */
object Voice {
    fun transcribe(client: ServerClient, wav: ByteArray, timeoutMs: Int, latin: (String) -> String = Latin::of): Transcript {
        if (!SpeechCheck.mayHoldSpeech(wav)) return Transcript("", "", "", 0, null)
        val transcript = client.transcribe(wav, timeoutMs)
        if (transcript.failure != null) return transcript
        val heard = latin(transcript.heard)
        return transcript.copy(heard = heard, text = Amounts.normalize(heard, latin(transcript.text)))
    }
}
