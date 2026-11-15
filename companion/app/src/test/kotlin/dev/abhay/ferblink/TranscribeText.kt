package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject

/**
 * The transcribe prompt on text in place of audio, for checking its "text" rules on the audio
 * bench's heard lines (`companion:harness`'s `transcribe-text`; the audio wasn't kept). Not what
 * the phone sends, so it lives with the harness, out of the APK.
 */
object TranscribeText {
    fun body(model: String, heard: String): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", Prompts.TRANSCRIBE))
            .put(JSONObject().put("role", "user").put("content", "(The recording, as heard:) $heard")))
        .put("temperature", 0)
        .put("max_tokens", 300)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        .put("response_format", Prompts.FORMAT_SLOT)
        .toString()
        .replace("\"${Prompts.FORMAT_SLOT}\"", Prompts.TRANSCRIBE_FORMAT)

    /** [heard] through the transcribe prompt, then the same steps as [Voice.transcribe] after the model. */
    fun transcribe(client: ServerClient, heard: String, timeoutMs: Int?): Transcript {
        val transcript = client.transcribe({ body(it, heard) }, timeoutMs)
        return if (transcript.failure != null) transcript else transcript.copy(text = Amounts.normalize(transcript.heard, transcript.text))
    }
}
