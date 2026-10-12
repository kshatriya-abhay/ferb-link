package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Ferb Link's `complete` and `transcribe` on the laptop, with no phone: the same code (prompts,
 * parameters, [Amounts.forRequest], [Voice.transcribe]) against the local server. Skipped in
 * normal test runs; `mise run companion:harness in.jsonl [out.jsonl]` runs it. One JSON object per
 * input line, one reply per output line, shaped as Ferb Link's reply (`reply` in LinkContract):
 * - `{"request": "...", "tools": [...] or "...", "facts": "...", "timeout_ms": 10000}` → complete,
 *   plus "sent": the request after [Amounts.forRequest];
 * - `{"audio": "/path/clip.ogg"}` → transcribe. The file is decoded with ffmpeg to 16 kHz mono WAV
 *   (the phone decodes with MediaCodec), and ICU's transliteration runs through `uconv`.
 * An "id" field is copied to the reply. Errors in a line give {"id", "harness_error"}.
 */
class LaptopHarness {
    @Test
    fun run() {
        val input = System.getProperty("harness.in")
        assumeTrue("set -Pharness.in", !input.isNullOrBlank())
        val output = File(System.getProperty("harness.out") ?: "$input.out")
        val url = System.getProperty("harness.url")?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8080"
        val key = File(System.getProperty("harness.key")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config/ferb-link/api-key").readText().trim()
        val model = System.getProperty("harness.model")?.takeIf { it.isNotBlank() } ?: "gemma-4-e2b"
        val client = ServerClient(url, key, model = model)

        output.printWriter().use { out ->
            File(input!!).readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.forEach { line ->
                val reply = try {
                    answer(client, JSONObject(line))
                } catch (e: Exception) {
                    JSONObject().put("harness_error", e.toString())
                }
                runCatching { JSONObject(line).opt("id") }.getOrNull()?.let { reply.put("id", it) }
                out.println(reply.toString())
                out.flush()
            }
        }
    }

    private fun answer(client: ServerClient, line: JSONObject): JSONObject {
        val timeout = if (line.has("timeout_ms")) line.getInt("timeout_ms") else null
        return when {
            line.has("audio") -> {
                val wav = decode(File(line.getString("audio")))
                JSONObject(Voice.transcribe(client, wav, timeout ?: LinkContract.TRANSCRIBE_TIMEOUT_MS, ::latin).toJson())
            }
            line.has("request") -> {
                val request = line.getString("request")
                val tools = when (val value = line.get("tools")) {
                    is JSONArray -> value.toString()
                    else -> value.toString()
                }
                val sent = Amounts.forRequest(request)
                val reply = if (request.isBlank()) Reply.failed(ErrorKind.BadRequest, "No request", short = "bad request: no request")
                else client.complete(sent, tools, line.optString("facts").ifBlank { null }, timeout)
                JSONObject(reply.toJson()).put("sent", sent)
            }
            else -> throw IllegalArgumentException("a line needs \"request\" or \"audio\"")
        }
    }

    /** 16 kHz mono 16-bit WAV, what Ferb Link sends after decoding the phone's Opus. */
    private fun decode(file: File): ByteArray {
        val wav = File.createTempFile("harness", ".wav")
        try {
            run("ffmpeg", "-v", "error", "-y", "-i", file.path, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav.path)
            return wav.readBytes()
        } finally {
            wav.delete()
        }
    }

    /** [Latin.of], with ICU's steps through `uconv` (ICU4C, the same rules as Android's ICU). */
    private fun latin(text: String): String =
        if (!Latin.hasDevanagari(text)) text
        else Latin.convert(text, { run("uconv", "-x", "Any-Latin", input = it) }, { run("uconv", "-x", "Latin-ASCII", input = it) })

    private fun run(vararg command: String, input: String? = null): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.use { stream -> input?.let { stream.write(it.toByteArray()) } }
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "${command[0]} failed: $text" }
        return text.trimEnd('\n')
    }
}
