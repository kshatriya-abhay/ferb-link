package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The audio bench's misses and the laptop's test clips (2026-10-12): heard and the model's text. */
class AmountsTest {
    @Test
    fun `times of day`() {
        assertThat(Amounts.fix("sava saat baje utha dena", "Wake me up at 7 AM")).isEqualTo("Wake me up at 7:15 am")
        assertThat(Amounts.fix("sava 7 baje utha dena", "Wake me up at 7:15")).isEqualTo("Wake me up at 7:15")
        assertThat(Amounts.fix("paune 8 baje ka alarm laga do", "Set an alarm for 8:45")).isEqualTo("Set an alarm for 7:45")
        assertThat(Amounts.fix("saade paanch baje ka alarm laga do", "Set an alarm for half past 5")).isEqualTo("Set an alarm for 5:30")
        assertThat(Amounts.fix("dhai baje yaad dilana", "Remind me at half past")).isEqualTo("Remind me at 2:30")
        assertThat(Amounts.fix("paune ek baje", "at 1")).isEqualTo("at 12:45")
    }

    @Test
    fun `am or pm is kept, or taken from the time of day said`() {
        assertThat(Amounts.fix("shaam saade 7 baje", "Set an alarm for 7:30 pm")).isEqualTo("Set an alarm for 7:30 pm")
        assertThat(Amounts.fix("shaam saade 7 baje", "Set an alarm for 7")).isEqualTo("Set an alarm for 7:30 pm")
        assertThat(Amounts.fix("subah sava 6 baje utha dena", "Wake me up at 6 am")).isEqualTo("Wake me up at 6:15 am")
        assertThat(Amounts.fix("raat paune 11 baje", "at 11")).isEqualTo("at 10:45 pm")
        assertThat(Amounts.fix("raat dhai baje", "at half past 2")).isEqualTo("at 2:30 am")
        assertThat(Amounts.fix("dopahar sava 1 baje", "at 1")).isEqualTo("at 1:15 pm")
    }

    @Test
    fun `lengths`() {
        assertThat(Amounts.fix("dhai minute ka timer lagao", "Set a timer for 30 minutes")).isEqualTo("Set a timer for 2 minutes 30 seconds")
        assertThat(Amounts.fix("dhai minute ka timer laga do", "Set a timer for dhai minutes")).isEqualTo("Set a timer for 2 minutes 30 seconds")
        assertThat(Amounts.fix("dedh ghante ka timer laga do", "Set a timer for 1.5 hours")).isEqualTo("Set a timer for 1 hour 30 minutes")
        assertThat(Amounts.fix("sava ghante ka timer laga do", "Set a timer for a quarter hours")).isEqualTo("Set a timer for 1 hour 15 minutes")
        assertThat(Amounts.fix("paune do ghante ka timer lagao", "Set a timer for two less a quarter hours")).isEqualTo("Set a timer for 1 hour 45 minutes")
        assertThat(Amounts.fix("dedh ghante ka timer laga do", "Set a timer for dedh ghanta")).isEqualTo("Set a timer for 1 hour 30 minutes")
        assertThat(Amounts.fix("saade 10 minute ka timer", "Set a timer for a half 10 minutes")).isEqualTo("Set a timer for 10 minutes 30 seconds")
    }

    @Test
    fun `the rerun's alarm misses`() {
        assertThat(Amounts.normalize("shaam saat baje ka alarm lagao", "Set an alarm for 7 o'clock")).isEqualTo("Set an alarm for 7 pm")
        assertThat(Amounts.normalize("alarm add quarter to 8", "alarm add quarter to 8")).isEqualTo("alarm add 7:45")
    }

    @Test
    fun `English clock phrases become H MM`() {
        assertThat(Amounts.englishClock("wake me at a quarter past seven")).isEqualTo("wake me at 7:15")
        assertThat(Amounts.englishClock("alarm for half past 5 pm")).isEqualTo("alarm for 5:30 pm")
        assertThat(Amounts.englishClock("quarter to 1")).isEqualTo("12:45")
        assertThat(Amounts.englishClock("set a timer for a quarter of an hour")).isEqualTo("set a timer for a quarter of an hour")
    }

    @Test
    fun `dopahar however it was written down`() {
        for (heard in listOf("dopahar do baje", "do peher do baje", "dopehar 2 baje", "dopaher 2 baje", "dophar 2 baje", "dupahar 2 baje", "do pahar 2 baje")) {
            assertThat(Amounts.withHalfOfDay(heard, "Wake me up at 2")).isEqualTo("Wake me up at 2 pm")
        }
        assertThat(Amounts.withHalfOfDay("do baje", "Wake me up at 2")).isEqualTo("Wake me up at 2")
        assertThat(Amounts.withHalfOfDay("do paar 2 baje", "at 2")).isEqualTo("at 2")
    }

    @Test
    fun `minutes past and to the hour`() {
        assertThat(Amounts.englishClock("alarm at 5 past 7 in the morning")).isEqualTo("alarm at 7:05 in the morning")
        assertThat(Amounts.englishClock("wake me at ten to eight")).isEqualTo("wake me at 7:50")
        assertThat(Amounts.englishClock("20 minutes past 6")).isEqualTo("6:20")
        assertThat(Amounts.englishClock("twenty-five to nine")).isEqualTo("8:35")
        assertThat(Amounts.englishClock("quarter to eight")).isEqualTo("7:45")
        assertThat(Amounts.englishClock("set a timer for 10 minutes")).isEqualTo("set a timer for 10 minutes")
    }

    @Test
    fun `am or pm goes on bare times only when the time of day was said`() {
        assertThat(Amounts.withHalfOfDay("raat 11 baje", "Remind me at 11")).isEqualTo("Remind me at 11 pm")
        assertThat(Amounts.withHalfOfDay("subah 6:30 baje", "Wake me at 6:30")).isEqualTo("Wake me at 6:30 am")
        assertThat(Amounts.withHalfOfDay("shaam 7 baje", "Alarm for 7 in the evening")).isEqualTo("Alarm for 7 in the evening")
        assertThat(Amounts.withHalfOfDay("7 baje ka alarm", "Set an alarm for 7")).isEqualTo("Set an alarm for 7")
        assertThat(Amounts.withHalfOfDay("shaam ko 10 minute ka timer", "Set a timer for 10 minutes")).isEqualTo("Set a timer for 10 minutes")
    }

    @Test
    fun `no Hindi amount leaves the text alone`() {
        assertThat(Amounts.fix("subah 5 baje ka alarm laga do", "Set an alarm for 5 am")).isEqualTo("Set an alarm for 5 am")
        assertThat(Amounts.fix("10 minute ka timer lagao", "Set a timer for 10 minutes")).isEqualTo("Set a timer for 10 minutes")
        assertThat(Amounts.fix("set a timer for 2 minutes", "set a timer for 2 minutes")).isEqualTo("set a timer for 2 minutes")
        assertThat(Amounts.parse("sava kya hai")).isNull()
    }
}
