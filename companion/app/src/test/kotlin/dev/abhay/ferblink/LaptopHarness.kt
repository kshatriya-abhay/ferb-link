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
 *   (the phone decodes with MediaCodec), and ICU's transliteration runs through `uconv`;
 * - `{"method": "decide", "request": "...", "tools": [...], "timeout_ms": 10000}` → TypeSafe's Jev
 *   ([JevClient]), plus "input_tokens", "usd" and "estimated". It costs money: every call goes in
 *   [JevLedger], a run that could take the tests past their cap is refused before anything is sent,
 *   and a call costing over [JevLedger.SURPRISE] times its estimate stops the run. -Pharness.limit=N
 *   sends at most N. -Pharness.dry=true sends nothing at all (complete and transcribe lines
 *   included) and writes one line: the decide count, its estimated cost, and the other lines' count.
 *   The key is read from -Pharness.jev-key's file (default ~/.config/ferb-link/typesafe-key);
 *   -Pharness.jev-url points it at a fake TypeSafe instead, with -Pharness.jev-ledger for its own ledger.
 * - `{"method": "rewrite", "request": "...", "now": "2026-10-16T09:00 (Friday)", "variant": "v1+shots",
 *   "tools": [...]}` → the rewrite experiment ([Rewrite]): `commands` (empty when nothing is asked),
 *   `sent` (after [Amounts.forRequest]), `raw` (the model's text), `variant`. "tools" (names or
 *   schemas) is only used by +tools. Gemma on the laptop: free, one model at a time, under the lock.
 * An "id" field is copied to the reply. Errors in a line give {"id", "harness_error"}; decide lines
 * not sent give {"id", "skipped"}.
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
        val lines = File(input!!).readLines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val jev = JevRun(lines, File(input).name)
        val dry = System.getProperty("harness.dry") == "true"

        output.printWriter().use { out ->
            // A dry run sends nothing at all, to the laptop's model or to Jev: it only counts.
            if (dry) {
                out.println(jev.dryRun().put("other_lines", lines.size - jev.decideLines).toString())
                return
            }
            jev.refusal()?.let { reason ->
                out.println(reason.toString())
                out.flush()
                error(reason.getString("refused"))
            }
            for (line in lines) {
                val reply = try {
                    val json = JSONObject(line)
                    if (json.optString("method") == "decide") jev.decide(json) else answer(client, json)
                } catch (e: Exception) {
                    JSONObject().put("harness_error", e.toString())
                }
                runCatching { JSONObject(line).opt("id") }.getOrNull()?.let { reply.put("id", it) }
                out.println(reply.toString())
                out.flush()
            }
            jev.end()
        }
    }

    /** A run's `decide` lines: the budget checks, the calls, and the ledger. */
    private class JevRun(lines: List<String>, private val name: String) {
        private val limit = System.getProperty("harness.limit")?.takeIf { it.isNotBlank() }?.toInt() ?: Int.MAX_VALUE
        // -Pharness.jev-ledger: another ledger, for trying the harness against a fake.
        private val ledger = JevLedger(File(System.getProperty("harness.jev-ledger")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config/ferb-link/jev-spend.json"))

        /** Each decide line's estimated input tokens, for the lines this run would send. */
        private val estimates: List<Int> = lines.mapNotNull { line ->
            val json = runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.optString("method") == "decide" } ?: return@mapNotNull null
            runCatching { estimate(json) }.getOrDefault(0)
        }.take(limit)

        // Twice the estimate, for safety (Hinglish may take more tokens than English).
        private val estimatedUsd = estimates.sum() * 2 * JevClient.USD_PER_INPUT_TOKEN

        private val client by lazy {
            val keyFile = File(System.getProperty("harness.jev-key")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config/ferb-link/typesafe-key")
            // -Pharness.jev-url: a fake TypeSafe, to try the harness itself without spending.
            JevClient(keyFile.readText().trim(), System.getProperty("harness.jev-url")?.takeIf { it.isNotBlank() } ?: JevClient.BASE_URL)
        }
        private var sent = 0
        private var tokens = 0L
        private var usd = 0.0
        private var stopped: String? = null

        /** All the decide lines in the input, sent or not. */
        val decideLines = lines.count { line -> runCatching { JSONObject(line).optString("method") == "decide" }.getOrDefault(false) }

        /** What a run would send to Jev and cost, for -Pharness.dry. */
        fun dryRun(): JSONObject = JSONObject().put("dry_run", true).put("decide_calls", estimates.size).put("estimated_usd", estimatedUsd)
            .put("ledger_usd", ledger.usd).put("cap_usd", ledger.capUsd)

        /** Why this run mustn't start (one line for the output), or null. */
        fun refusal(): JSONObject? = when {
            estimates.isEmpty() -> null
            !ledger.allows(estimatedUsd) -> JSONObject().put("refused", "this run could cost \$${"%.4f".format(estimatedUsd)}; " +
                "the tests have spent \$${"%.4f".format(ledger.usd)} of their \$${ledger.capUsd}")
            else -> null
        }

        fun decide(line: JSONObject): JSONObject {
            stopped?.let { return JSONObject().put("skipped", "stopped: $it") }
            if (sent >= limit) return JSONObject().put("skipped", "limit")
            val estimate = estimates[sent]
            if (!ledger.allows(estimate * 2 * JevClient.USD_PER_INPUT_TOKEN)) {
                stopped = "the tests' \$${ledger.capUsd} cap"
                return JSONObject().put("skipped", "stopped: $stopped")
            }
            sent++
            val decision = client.decide(line.getString("request"), tools(line), if (line.has("timeout_ms")) line.getInt("timeout_ms") else LinkContract.DECIDE_TIMEOUT_MS)
            val billed = decision.billed
            ledger.record(billed)
            tokens += billed.inputTokens
            usd += billed.usd
            if (!billed.estimated && billed.inputTokens > JevLedger.SURPRISE * estimate) {
                stopped = "a call took ${billed.inputTokens} input tokens, over ${JevLedger.SURPRISE.toInt()}× its estimate of $estimate"
            }
            return JSONObject(decision.toJson()).put("input_tokens", billed.inputTokens).put("usd", billed.usd).put("estimated", billed.estimated)
        }

        fun end() {
            if (sent > 0 || stopped != null) ledger.endRun(name, sent, tokens, usd, stopped)
        }

        private fun estimate(line: JSONObject): Int {
            val request = line.getString("request")
            val tools = JevClient.tools(JSONArray(tools(line)))
            val options = JevClient.options(request, tools.map { it.first })
            return JevClient.estimateTokens(JevClient.body(request, tools.toMap(), options), options.size)
        }

        private fun tools(line: JSONObject) = when (val value = line.get("tools")) {
            is JSONArray -> value.toString()
            else -> value.toString()
        }
    }

    private fun answer(client: ServerClient, line: JSONObject): JSONObject {
        val timeout = if (line.has("timeout_ms")) line.getInt("timeout_ms") else null
        return when {
            // The transcribe prompt's "text" rules on a heard line, without audio (not the phone's path).
            line.optString("method") == "transcribe-text" -> JSONObject(client.transcribeText(line.getString("heard"), timeout).toJson())
            line.optString("method") in setOf("rewrite", "comprehend") -> {
                // "tools": names, or schemas as for complete; only their names are used (+tools).
                val tools = line.optJSONArray("tools")
                val names = (0 until (tools?.length() ?: 0)).map { i -> tools!!.optJSONObject(i)?.getString("name") ?: tools.getString(i) }
                val name = line.optString("variant").ifBlank { if (line.optString("method") == "comprehend") "c1" else "v1" }
                val variant = Rewrite.Variant.of(name)
                require(Rewrite.comprehends(variant) == (line.optString("method") == "comprehend")) { "variant $name doesn't fit method ${line.optString("method")}" }
                JSONObject(client.rewrite(line.getString("request"), line.getString("now"), variant, names, timeout).toJson())
                    .put("variant", name)
            }
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
