package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The audio bench's misses and the laptop's test clips (2026-10-12): heard and the model's text. */
class AmountsTest {
    @Test
    fun `times of day`() {
        assertThat(Amounts.fix("sava saat baje utha dena", "Wake me up at 7 AM")).isEqualTo("Wake me up at 7:15")
        assertThat(Amounts.fix("sava 7 baje utha dena", "Wake me up at 7:15")).isEqualTo("Wake me up at 7:15")
        assertThat(Amounts.fix("paune 8 baje ka alarm laga do", "Set an alarm for 8:45")).isEqualTo("Set an alarm for 7:45")
        assertThat(Amounts.fix("saade paanch baje ka alarm laga do", "Set an alarm for half past 5")).isEqualTo("Set an alarm for 5:30")
        assertThat(Amounts.fix("dhai baje yaad dilana", "Remind me at half past")).isEqualTo("Remind me at 2:30")
        assertThat(Amounts.fix("paune ek baje", "at 1")).isEqualTo("at 12:45")
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
    fun `no Hindi amount leaves the text alone`() {
        assertThat(Amounts.fix("subah 5 baje ka alarm laga do", "Set an alarm for 5 am")).isEqualTo("Set an alarm for 5 am")
        assertThat(Amounts.fix("10 minute ka timer lagao", "Set a timer for 10 minutes")).isEqualTo("Set a timer for 10 minutes")
        assertThat(Amounts.fix("set a timer for 2 minutes", "set a timer for 2 minutes")).isEqualTo("set a timer for 2 minutes")
        assertThat(Amounts.parse("sava kya hai")).isNull()
    }
}
