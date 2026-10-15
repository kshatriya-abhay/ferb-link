package dev.abhay.ferblink

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * What the laptop's Jev runs have cost, in [file] (`~/.config/ferb-link/jev-spend.json`, outside
 * the repo): every call's input tokens and dollars, kept for good. Tests may spend [capUsd] in all
 * (the user's rule: $1 of the $5 Jev budget). The phone keeps its own counter for real use.
 */
class JevLedger(private val file: File, val capUsd: Double = TEST_CAP_USD) {
    var calls = 0
        private set
    var inputTokens = 0L
        private set
    var usd = 0.0
        private set
    private val runs = JSONArray()

    init {
        if (file.exists()) {
            val json = JSONObject(file.readText())
            val tests = json.getJSONObject("tests")
            calls = tests.getInt("calls")
            inputTokens = tests.getLong("input_tokens")
            usd = tests.getDouble("usd")
            json.optJSONArray("runs")?.let { for (i in 0 until it.length()) runs.put(it.get(i)) }
        }
    }

    /** Whether spending [moreUsd] still leaves the tests within [capUsd]. */
    fun allows(moreUsd: Double) = usd + moreUsd <= capUsd

    /** Records one call, at once: a run that stops halfway still counts what it spent. */
    fun record(billed: JevClient.Billed) {
        calls++
        inputTokens += billed.inputTokens
        usd += billed.usd
        save()
    }

    /** Notes a finished (or stopped) run. */
    fun endRun(name: String, calls: Int, inputTokens: Long, usd: Double, stopped: String?) {
        runs.put(JSONObject().put("at", Instant.now().toString()).put("input", name).put("calls", calls)
            .put("input_tokens", inputTokens).put("usd", usd).apply { stopped?.let { put("stopped", it) } })
        save()
    }

    private fun save() {
        val json = JSONObject()
            .put("note", "Jev spend on the laptop (companion:harness). Tests are capped at \$$capUsd of the \$5 Jev budget.")
            .put("cap_tests_usd", capUsd)
            .put("tests", JSONObject().put("calls", calls).put("input_tokens", inputTokens).put("usd", usd))
            .put("runs", runs)
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.toString(2) + "\n")
        check(temp.renameTo(file)) { "couldn't write $file" }
    }

    companion object {
        const val TEST_CAP_USD = 1.0
        /** A call that costs more than this many times its estimate stops the run (ferb-manager's rule). */
        const val SURPRISE = 10.0
    }
}
