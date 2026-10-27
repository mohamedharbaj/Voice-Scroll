package com.voicescroll.app.engine

import java.text.Normalizer
import java.util.regex.Pattern

object ArabicNormalizer {

    private val DIACRITICS_PATTERN = Pattern.compile("[\u064B-\u065F\u0670\u0640]")
    private val LATIN_ACCENTS_PATTERN = Pattern.compile("[\u0300-\u036F]")
    private val PUNCTUATION_REGEX = Regex("[,.!?:;\"'\\-_/\\\\()\\[\\]{}~`@#$%^&*+=<>|،؛؟…«»]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Normalizes text for reliable matching across Arabic dialects (Darija, Standard), French, and English:
     * - Removes Arabic tashkeel / harakat and tatweel
     * - Unifies alefs (أ, إ, آ, ٱ -> ا)
     * - Unifies taa marbuta (ة -> ه)
     * - Unifies yeh (ى -> ي)
     * - Normalizes Maghrebi / Persian characters (ڤ, ڥ -> ف, گ, ݣ -> ك, ڨ -> ق)
     * - Strips Latin accents (é, è, à, ç ... -> e, e, a, c) so "précédent" == "precedent"
     * - Removes punctuation (including Arabic punctuation) and excess whitespace
     * - Converts latin characters to lowercase
     */
    fun normalize(rawText: String?): String {
        if (rawText.isNullOrBlank()) return ""

        var text = rawText.trim().lowercase()

        // Remove Arabic diacritics + tatweel
        text = DIACRITICS_PATTERN.matcher(text).replaceAll("")

        // Normalize Alef variants
        text = text.replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')

        // Hamza carriers: ؤ -> و, ئ -> ي, ء removed (different recognizers spell the same word differently)
        text = text.replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace("ء", "")

        // Arabic-Indic / Persian digits -> ASCII
        text = text.map { c ->
            when (c) {
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                else -> c
            }
        }.joinToString("")

        // Normalize Taa Marbuta
        text = text.replace('ة', 'ه')

        // Normalize Alef Maqsura
        text = text.replace('ى', 'ي')

        // Normalize Maghrebi dialect letter variants
        text = text.replace('ڤ', 'ف')
            .replace('ڥ', 'ف')
            .replace('گ', 'ك')
            .replace('ݣ', 'ك')
            .replace('ڨ', 'ق')
            .replace('پ', 'ب')
            .replace('ک', 'ك')
            .replace('ی', 'ي')

        // Strip Latin accents (é -> e). Arabic letters are already normalized above.
        if (text.any { it.code in 0x00C0..0x024F }) {
            text = Normalizer.normalize(text, Normalizer.Form.NFD)
            text = LATIN_ACCENTS_PATTERN.matcher(text).replaceAll("")
        }

        // Remove common punctuation and symbols
        text = text.replace(PUNCTUATION_REGEX, " ")

        // Collapse whitespace
        text = text.replace(WHITESPACE_REGEX, " ").trim()

        return text
    }
}
