package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * What Ferb Link asks llama-server: the system prompts and request bodies for `complete`,
 * `transcribe` and waking the model. [ServerClient] sends them ([LlamaHttp]) and reads the answers.
 */
object Prompts {
    /** Ferb's bench's instruction, word for word (`tool-check/bench.py`), so its results hold here. */
    const val SYSTEM = "You are Ferb, an assistant on an Android phone. When the user asks for something one of the tools does, " +
        "call that tool with the arguments in the user's words. If no tool fits, or the user isn't asking for an " +
        "action, reply in a few words without calling a tool."

    /**
     * How a spoken request is written down. Hindi comes back in Latin letters for the user to
     * read, and translated, because Ferb's keywords, date reader and Needle 3 need English.
     * "speech" comes first, so the model decides whether anyone spoke before writing words: asked
     * only to leave the fields empty, it turned silence and room noise into commands ("call
     * mom"). The model also "translated" English (it dropped a "not"), so it says whether any
     * word was Hindi, and [ServerClient.parseTranscript] keeps what was heard for English. Tried on synthetic
     * clips, noise and silence (2026-10-12). The amounts paragraph is from the audio bench, where
     * sava, dhai and paune were misheard ("subah", "thai", "phone") or dropped: named, they're heard.
     * Its sums still slip, so [Amounts] works them out from "heard".
     */
    const val TRANSCRIBE = """You write down a voice request to a phone assistant. The recording may contain no speech at all: silence, room noise, hiss, hum, wind, breathing, rustling or a tap on the phone. Reply with JSON only, with four fields, in this order:
- "speech": true only if you clearly hear a person saying words. False for silence, noise or any other sound. Never guess words from noise.
- "hindi": true if any word said was Hindi (Hinglish counts), false if every word was English.
- "heard": the words that were said, in Latin letters. Write Hindi words the way people type Hinglish ("subah 5 baje ka alarm laga do"), never in Devanagari. Write English words in English. Write numbers as digits. Empty when "speech" is false.
- "text": the same request as a short, plain English command, for example "Set an alarm for 5 am". Translate only: keep every name, app, place, number, time and date exactly as in "heard", don't add, drop or guess anything, and don't answer the request. Keep a word you aren't sure of as it is in "heard" rather than replacing it with a word you know. Empty when "speech" is false.
Hindi amounts are common in these requests; listen for them and keep them: sava (a quarter more: sava saat = 7:15, sava ghanta = 1 hour 15 minutes), saade (a half more: saade paanch = 5:30), paune (a quarter less: paune aath = 7:45, paune do ghante = 1 hour 45 minutes), dedh (one and a half: dedh ghanta = 1 hour 30 minutes), dhai (two and a half: dhai minute = 2 minutes 30 seconds, dhai baje = 2:30). In "heard" write the amount word as said ("sava 7 baje"). In "text" write the exact time or length ("Wake me up at 7:15", "Set a timer for 2 minutes 30 seconds"). If you aren't sure which amount word was said, keep it as said in "text" too ("Set a timer for dhai minutes") rather than guessing a number.
In "text", write other amounts as numbers too: half is 50 percent, a quarter 25 percent, full or max 100 percent ("aadhi aawaz" → "Set the volume to 50 percent"). A request of one or two action words is still a command: "next", "stop", "pause", "louder" stay as said ("Play the next track", "Stop", "Pause", "Turn the volume up")."""

    /** The smallest request that loads the model. */
    fun wakeBody(model: String) = JSONObject()
        .put("model", model)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
        .put("max_tokens", 1)
        .toString()

    /** The bench's stop strings: FunctionGemma otherwise goes on to imagine the tool's answer. */
    private val STOP = listOf("<start_function_response>", "<end_function_call>call:")

    fun requestBody(model: String, request: String, tools: JSONArray, facts: String?): JSONObject {
        val system = if (facts.isNullOrBlank()) SYSTEM else "$SYSTEM\n\n$facts"
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", request)))
            .put("tools", JSONArray((0 until tools.length()).map { JSONObject().put("type", "function").put("function", tools.get(it)) }))
            .put("tool_choice", "auto")
            .put("temperature", 0)
            .put("max_tokens", 256)
            .put("logprobs", true)
            .put("stop", JSONArray(STOP))
            // Thinking models (Qwen3.5) answer straight away, as in the bench.
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
    }

    /**
     * The answer's shape, as a literal: the model writes the fields in this order (whether anyone
     * spoke, before any words), and the JVM's org.json (the laptop harness) doesn't keep key order.
     */
    internal const val TRANSCRIBE_FORMAT = """{"type": "json_schema", "json_schema": {"schema": {"type": "object", """ +
        """"properties": {"speech": {"type": "boolean"}, "hindi": {"type": "boolean"}, "heard": {"type": "string"}, "text": {"type": "string"}}, """ +
        """"required": ["speech", "hindi", "heard", "text"]}}}"""

    fun transcribeBody(model: String, wav: ByteArray): String {
        val audio = JSONObject().put("data", Base64.getEncoder().encodeToString(wav)).put("format", "wav")
        return JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", TRANSCRIBE))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "input_audio").put("input_audio", audio)))))
            .put("temperature", 0)
            .put("max_tokens", 300)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            // Constrained to the four fields, so the answer is always JSON.
            .put("response_format", FORMAT_SLOT)
            .toString()
            .replace("\"$FORMAT_SLOT\"", TRANSCRIBE_FORMAT)
    }

    internal const val FORMAT_SLOT = "@response_format@"
}
