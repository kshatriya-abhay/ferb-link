package dev.abhay.ferblink

import android.icu.text.Transliterator

/**
 * Hindi in Latin letters, the way people type Hinglish. The transcribe prompt asks for Latin
 * letters, but the model sometimes writes Devanagari ("पेटीएम खोलो", in the voice test). Holding
 * it to ASCII with a schema pattern made llama.cpp's Gemma 4 parser fail on some answers (HTTP
 * 500), so Ferb Link converts afterwards, with the same ICU transliterator Ferb uses for contacts.
 */
object Latin {
    private val devanagari = Regex("[\\u0900-\\u097F]")
    private val toLatin by lazy { Transliterator.getInstance("Any-Latin") }
    private val toAscii by lazy { Transliterator.getInstance("Latin-ASCII") }

    fun hasDevanagari(text: String) = devanagari.containsMatchIn(text)

    /** [text] with each Devanagari word in Latin letters; other words as they are. */
    fun of(text: String): String =
        if (!hasDevanagari(text)) text else convert(text, toLatin::transliterate, toAscii::transliterate)

    /** [of], with ICU's two steps passed in (ICU isn't there in JVM tests). */
    fun convert(text: String, latin: (String) -> String, ascii: (String) -> String): String =
        text.split(" ").joinToString(" ") { word ->
            if (hasDevanagari(word)) ascii(dropFinalSchwa(hinglish(latin(word)))) else word
        }

    /**
     * ICU's spelling the way Hinglish types it: no apostrophe between vowels ("ṭā'imara"), and ड़
     * and ढ़ as "d" and "dh" (dedh, padho), where ICU writes "ṛ" and "ṛh".
     */
    private fun hinglish(latin: String) = latin.replace("'", "").replace("ṛh", "dh").replace("ṛ", "d")

    /**
     * Hindi doesn't say a word's last inherent "a" (खोल is "khol", not "khola"), but ICU writes it.
     * On ICU's Latin with its accents still on, so a long "ā" (क्या, "kyā") stays: drop a final
     * short "a" after a consonant, in words longer than two letters.
     */
    fun dropFinalSchwa(word: String): String {
        if (word.length <= 2 || !word.endsWith("a")) return word
        val before = word[word.length - 2]
        return if (before.isLetter() && before.lowercaseChar() !in "aeiouāēīōū") word.dropLast(1) else word
    }
}
