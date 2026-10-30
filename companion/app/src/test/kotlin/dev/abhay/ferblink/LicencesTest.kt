package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class LicencesTest {
    @Test
    fun `paragraphs are joined into single lines`() {
        val text = "  1. Definitions.\n\n      \"License\" shall mean the terms\n      and conditions.\n\n\n   2. Grant.\n"
        assertThat(Licences.paragraphs(text)).containsExactly("1. Definitions.", "\"License\" shall mean the terms and conditions.", "2. Grant.").inOrder()
    }

    @Test
    fun `the bundled licence is the repo's LICENSE`() {
        // Tests run in companion/app; the raw copy must not drift from the repo's licence.
        val bundled = File("src/main/res/raw/apache_2_0.txt").readText()
        assertThat(bundled).isEqualTo(File("../../LICENSE").readText())
        assertThat(Licences.paragraphs(bundled).none { '\n' in it }).isTrue()
    }
}
