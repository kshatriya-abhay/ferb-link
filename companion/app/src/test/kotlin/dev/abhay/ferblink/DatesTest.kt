package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/** Relative dates written out in code, against Friday 16 October 2026. */
class DatesTest {
    private val friday = LocalDate.of(2026, 10, 16)
    private fun dates(request: String) = Dates.forRequest(request, friday)

    @Test
    fun `kal, tomorrow, parso, aaj and today become dates`() {
        // The bench's miss: kal became Thursday in the model's hands.
        assertThat(dates("kal 4 baje meeting calendar mein dal do")).isEqualTo("on Saturday 17 October 4 baje meeting calendar mein dal do")
        assertThat(dates("am i free tomorrow")).isEqualTo("am i free on Saturday 17 October")
        assertThat(dates("parso ka schedule")).isEqualTo("on Sunday 18 October ka schedule")
        assertThat(dates("remind me the day after tomorrow")).isEqualTo("remind me on Sunday 18 October")
        assertThat(dates("aaj shaam ko yaad dilana")).isEqualTo("on Friday 16 October shaam ko yaad dilana")
        assertThat(dates("Today at 5")).isEqualTo("on Friday 16 October at 5")
    }

    @Test
    fun `a preposition already there is kept, without another on`() {
        assertThat(dates("finish the report by tomorrow")).isEqualTo("finish the report by Saturday 17 October")
        assertThat(dates("a meeting on Monday at 10")).isEqualTo("a meeting on Monday 19 October at 10")
    }

    @Test
    fun `weekdays, English or Hindi, are the next one after today`() {
        assertThat(dates("call mom on monday")).isEqualTo("call mom on Monday 19 October")
        assertThat(dates("somvar ko meeting")).isEqualTo("on Monday 19 October ko meeting")
        assertThat(dates("next Wednesday")).isEqualTo("on Wednesday 21 October")
        assertThat(dates("agle shukravar")).isEqualTo("on Friday 23 October")
        assertThat(dates("on Friday")).isEqualTo("on Friday 23 October")
    }

    @Test
    fun `other words are left alone, and a month ends right`() {
        assertThat(dates("set a timer for 10 minutes")).isEqualTo("set a timer for 10 minutes")
        assertThat(dates("kalpana ko call karo")).isEqualTo("kalpana ko call karo")
        assertThat(Dates.forRequest("kal", LocalDate.of(2026, 10, 31))).isEqualTo("on Sunday 1 November")
        assertThat(Dates.dateOf("2026-10-16T09:00 (Friday)")).isEqualTo(friday)
        assertThat(Dates.dateOf("soon")).isNull()
    }

    @Test
    fun `v2_2 sends the dates worked out, and keeps them out of invented`() {
        val sent = Dates.forRequest(Amounts.forRequest("kal 4 baje meeting calendar mein dal do"), friday)
        assertThat(sent).isEqualTo("on Saturday 17 October 4:00 meeting calendar mein dal do")
        assertThat(Rewrite.invented("kal 4 baje meeting calendar mein dal do", sent, listOf("Add a meeting on Saturday 17 October at 4:00 to the calendar"))).isEmpty()
        assertThat(Rewrite.datesInCode(Rewrite.Variant.of("v2.2"))).isTrue()
        assertThat(Rewrite.datesInCode(Rewrite.Variant.of("v2"))).isFalse()
        assertThat(Rewrite.V22).doesNotContain("work out \"today\"")
        assertThat(Rewrite.V22).contains("torch band karo")
    }
}
