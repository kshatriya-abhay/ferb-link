package dev.abhay.ferblink

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Relative dates, worked out in code before the model reads a request, as [Amounts] does for
 * amounts: the model got them wrong ("kal 4 baje meeting" became Thursday when kal was Saturday).
 * "kal", "tomorrow", "parso", "day after tomorrow", "aaj", "today" and weekday names (English and
 * Hindi) become a date written out, "Saturday 17 October", with "on" unless a preposition is
 * already there ("by tomorrow" → "by Saturday 17 October").
 *
 * In a request "kal" and "parso" are the days ahead; they also mean the days before, but a
 * request about yesterday asks for nothing, and Ferb's gate (Gemma's own "no tool") handles it.
 * A weekday is the next one after today ("on Friday", said on a Friday, is a week on), and "next
 * Monday" is the coming Monday too.
 */
object Dates {
    private val weekdays: Map<String, DayOfWeek> = DayOfWeek.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase() } + mapOf(
        "somvar" to DayOfWeek.MONDAY, "somvaar" to DayOfWeek.MONDAY, "somwar" to DayOfWeek.MONDAY,
        "mangalvar" to DayOfWeek.TUESDAY, "mangalvaar" to DayOfWeek.TUESDAY, "mangalwar" to DayOfWeek.TUESDAY,
        "budhvar" to DayOfWeek.WEDNESDAY, "budhvaar" to DayOfWeek.WEDNESDAY, "budhwar" to DayOfWeek.WEDNESDAY,
        "guruvar" to DayOfWeek.THURSDAY, "guruvaar" to DayOfWeek.THURSDAY, "guruwar" to DayOfWeek.THURSDAY, "veervar" to DayOfWeek.THURSDAY, "brihaspativar" to DayOfWeek.THURSDAY,
        "shukravar" to DayOfWeek.FRIDAY, "shukravaar" to DayOfWeek.FRIDAY, "shukrawar" to DayOfWeek.FRIDAY,
        "shanivar" to DayOfWeek.SATURDAY, "shanivaar" to DayOfWeek.SATURDAY, "shaniwar" to DayOfWeek.SATURDAY,
        "ravivar" to DayOfWeek.SUNDAY, "ravivaar" to DayOfWeek.SUNDAY, "raviwar" to DayOfWeek.SUNDAY, "itvar" to DayOfWeek.SUNDAY, "itwar" to DayOfWeek.SUNDAY, "itvaar" to DayOfWeek.SUNDAY,
    )

    private val days = mapOf(
        "day after tomorrow" to 2L, "parso" to 2L, "parson" to 2L, "parsoon" to 2L,
        "tomorrow" to 1L, "kal" to 1L, "today" to 0L, "aaj" to 0L,
    )

    // Longest first, so "day after tomorrow" isn't read as "tomorrow"; "next" is dropped with a weekday.
    private val phrase = Regex(
        "\\b(?:(on|by|till|until|before|after|from|for)\\s+)?(?:the\\s+)?(?:(?:next|agle|agla)\\s+)?(" +
            // Word by word: Regex.escape quotes a whole phrase, which would make \\s+ literal.
            (days.keys + weekdays.keys).sortedByDescending { it.length }.joinToString("|") { it.split(" ").joinToString("\\s+") { word -> Regex.escape(word) } } +
            ")\\b",
        RegexOption.IGNORE_CASE,
    )

    /** [request] with its relative dates written out against [today]. */
    fun forRequest(request: String, today: LocalDate): String = phrase.replace(request) { match ->
        val preposition = match.groupValues[1]
        val word = match.groupValues[2].lowercase().replace(Regex("\\s+"), " ")
        val date = days[word]?.let { today.plusDays(it) } ?: next(today, weekdays.getValue(word))
        val written = written(date)
        if (preposition.isEmpty()) "on $written" else "$preposition $written"
    }

    /** "Saturday 17 October". */
    fun written(date: LocalDate) = "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${date.dayOfMonth} " +
        date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun next(today: LocalDate, day: DayOfWeek): LocalDate {
        val ahead = (day.value - today.dayOfWeek.value + 7) % 7
        return today.plusDays(if (ahead == 0) 7L else ahead.toLong())
    }

    /** The date in a "now" as [Rewrite]'s prompt takes it ("2026-10-16T09:00 (Friday)"); null if it has none. */
    fun dateOf(now: String): LocalDate? = runCatching { LocalDate.parse(now.trim().take(10)) }.getOrNull()
}
