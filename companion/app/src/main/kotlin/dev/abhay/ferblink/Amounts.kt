package dev.abhay.ferblink

/**
 * Hindi amounts, worked out in code. Asked to, the model hears sava, saade, paune, dedh and dhai
 * well, but its sums are unreliable ("paune 8 baje" became 8:45, "saade 10 minute" "a half 10
 * minutes"; in the audio bench "dhai minute" became 30 and "sava saat" lost its quarter). So the
 * amount comes from what was heard, and replaces the model's version of it in the English text.
 */
object Amounts {
    /** A time of day ("7:15") or a length ("1 hour 15 minutes"), as Ferb's readers take them. */
    data class Amount(val english: String, val isTime: Boolean)

    private val numbers = mapOf(
        "ek" to 1, "do" to 2, "teen" to 3, "chaar" to 4, "char" to 4, "paanch" to 5, "panch" to 5,
        "chhe" to 6, "chhah" to 6, "chah" to 6, "chahe" to 6, "che" to 6, "saat" to 7, "aath" to 8, "nau" to 9,
        "das" to 10, "gyarah" to 11, "barah" to 12, "pandrah" to 15, "bees" to 20, "pachchees" to 25,
        "tees" to 30, "chalees" to 40, "chalis" to 40, "pachaas" to 50,
    )
    private val words = "sava|saade|sadhe|paune|pone|dedh|derh|tedh|dhai|dhaai"
    private val units = "baje|ghanta|ghante|ghanton|minute|minut|minat|mint|min|second|sekand"

    // "paune 8 baje", "sava ghanta", "dhai minute"; the number is optional (sava ghanta = 1¼ hours).
    private val inHeard = Regex("\\b($words)\\b\\s*(?:(\\d{1,2}|[a-z]+)\\s+)?($units)\\b")

    /** The first Hindi amount in [heard], worked out; null when there's none. */
    fun parse(heard: String): Amount? {
        val match = inHeard.find(heard.lowercase()) ?: return null
        val (word, numberText, unit) = match.destructured
        val number = numberText.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: numbers[it] ?: return null }
        val time = unit == "baje"
        val base = number ?: if (word in setOf("sava", "paune", "pone")) 1 else 0
        val value = when (word) {
            "sava" -> base + 0.25
            "saade", "sadhe" -> base + 0.5
            "paune", "pone" -> base - 0.25
            "dedh", "derh", "tedh" -> 1.5
            else -> 2.5 // dhai
        }
        if (value <= 0) return null
        return if (time) Amount(clock(value), isTime = true) else Amount(length(value, unit), isTime = false)
    }

    /** [text] with its time or length replaced by the one worked out from [heard]. */
    fun fix(heard: String, text: String): String {
        val amount = parse(heard) ?: return text
        val pattern = if (amount.isTime) timeInText else lengthInText
        val found = pattern.find(text) ?: return text
        val english = if (amount.isTime) amount.english + halfOfDay(heard, found.value, amount.english) else amount.english
        return text.replaceRange(found.range, english)
    }

    /**
     * " am" or " pm" for a time, or "" when nothing says: Ferb takes a time without one as written
     * (5 is 5 am). The model's own am/pm first, then the time of day in [heard]: subah is morning,
     * dopahar and shaam afternoon and evening, raat night (pm from 7, else the small hours).
     */
    private fun halfOfDay(heard: String, modelTime: String, time: String): String {
        Regex("\\b([ap])\\.?m\\.?$", RegexOption.IGNORE_CASE).find(modelTime.trim())?.let { return " ${it.groupValues[1].lowercase()}m" }
        val hour = time.substringBefore(':').toInt()
        val words = heard.lowercase().split(Regex("[^a-z]+")).toSet()
        return when {
            "subah" in words -> " am"
            "dopahar" in words || "shaam" in words || "sham" in words -> " pm"
            "raat" in words -> if (hour in 7..11) " pm" else " am"
            else -> ""
        }
    }

    private fun clock(hours: Double): String {
        val minutes = Math.round(hours * 60).toInt()
        var h = minutes / 60 % 12
        if (h == 0) h = 12
        return "%d:%02d".format(h, minutes % 60)
    }

    private fun length(value: Double, unit: String): String {
        val (big, small, factor) = when {
            unit.startsWith("ghant") -> Triple("hour", "minute", 60)
            unit.startsWith("se") -> return "${Math.round(value)} seconds"
            else -> Triple("minute", "second", 60)
        }
        val total = Math.round(value * factor).toInt()
        val whole = total / factor
        val rest = total % factor
        val first = if (whole == 1) "1 $big" else "$whole ${big}s"
        return when {
            rest == 0 -> first
            whole == 0 -> "$rest ${small}s"
            else -> "$first $rest ${small}s"
        }
    }

    // How the model writes a time: "7", "7:15", "7 am", "quarter past 7", "half past", "8 o'clock".
    private val timeInText = Regex(
        "\\b(?:(?:a )?quarter (?:past|to) |half past ?)?\\d{1,2}(?::\\d{2})?(?:\\s*(?:am|pm|a\\.m\\.|p\\.m\\.|o'clock))?\\b|\\bhalf past\\b",
        RegexOption.IGNORE_CASE,
    )

    // How the model writes a length: up to four number-ish words before the unit, and an optional
    // second unit ("2 minutes 30 seconds", "a half 10 minutes", "dhai minutes", "1.5 hours").
    private val numberish = "(?:\\d+(?:\\.\\d+)?|a|an|and|one|two|three|four|five|ten|half|quarter|less|$words)"
    private val lengthInText = Regex(
        "\\b(?:$numberish\\s+){1,4}(?:hours?|minutes?|mins?|seconds?|$units)\\b(?:\\s+(?:and\\s+)?\\d+\\s+(?:minutes?|seconds?)\\b)?",
        RegexOption.IGNORE_CASE,
    )
}
