package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.Normalizer

class LatinTest {
    // ICU's "Any-Latin" for these words, as on the phone, and a stand-in for "Latin-ASCII".
    private val latin = mapOf("पेटीएम" to "pēṭī'ēma", "खोलो" to "khōlō", "क्या" to "kyā", "कर" to "kara", "डेढ़" to "ḍēṛha", "घंटे" to "ghaṇṭē", "टाइमर" to "ṭā'imara")
    private fun ascii(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

    @Test
    fun `only Devanagari words are converted, without the unspoken last a`() {
        assertThat(Latin.convert("पेटीएम खोलो", latin::getValue, ::ascii)).isEqualTo("petiem kholo")
        assertThat(Latin.convert("Paytm खोलो", latin::getValue, ::ascii)).isEqualTo("Paytm kholo")
        assertThat(Latin.convert("क्या कर", latin::getValue, ::ascii)).isEqualTo("kya kar")
        assertThat(Latin.convert("डेढ़ घंटे टाइमर", latin::getValue, ::ascii)).isEqualTo("dedh ghante taimar")
        assertThat(Latin.hasDevanagari("ptm kholo")).isFalse()
    }

    @Test
    fun `a long a or a vowel before it stays, and so do short words`() {
        assertThat(Latin.dropFinalSchwa("kyā")).isEqualTo("kyā")
        assertThat(Latin.dropFinalSchwa("hua")).isEqualTo("hua")
        assertThat(Latin.dropFinalSchwa("ka")).isEqualTo("ka")
        assertThat(Latin.dropFinalSchwa("kara")).isEqualTo("kar")
    }
}
