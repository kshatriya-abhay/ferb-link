package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstructedTest {
    @Test
    fun `a first line that ran into the cap is cut off`() {
        assertThat(Instructed.cutOff("Send a message to bhaiya: I", "length")).isTrue()
        assertThat(Instructed.cutOff("Command: \"Set an alarm for", "length")).isTrue()
    }

    @Test
    fun `a finished answer, a cut later line, or NONE is whole`() {
        assertThat(Instructed.cutOff("Send a message to bhaiya: I am late", "stop")).isFalse()
        assertThat(Instructed.cutOff("Send a message to bhaiya: I am late", null)).isFalse()
        assertThat(Instructed.cutOff("Turn on the flashlight\nThe user wants", "length")).isFalse()
        assertThat(Instructed.cutOff("NONE", "length")).isFalse()
        assertThat(Instructed.cutOff("torch -> none.", "length")).isFalse()
        assertThat(Instructed.cutOff("  ", "length")).isFalse()
    }
}
