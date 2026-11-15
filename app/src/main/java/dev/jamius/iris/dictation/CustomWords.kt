/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.dictation

import kotlin.math.abs

/** Longest run of words (n-gram) compared with a custom word ("deep gram" -> "Deepgram"). */
private const val MAX_NGRAM = 3

/** A candidate is replaced when its score (0 = same key) is below this (Handy's default). */
private const val MATCH_THRESHOLD = 0.18

/** Score factor when the Soundex codes match (Handy: 0.3, which turns "Son of" into "Shinobi"). */
private const val PHONETIC_FACTOR = 0.5

/**
 * Shorter keys only get the exact fix (casing; joined words only with a digit or symbol, "GPT-5"): short words are too
 * often one edit apart, and short plain words too often two words ("is a", not "Isa").
 */
private const val MIN_FUZZY_KEY_LENGTH = 5

private val TOKEN = Regex("[\\p{L}\\p{N}]+")

private fun isAsciiLetterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

/** Lowercase ASCII letters and digits only. */
private fun key(s: String): String = s.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

/**
 * [text] with words that match one of [words] (the custom words) replaced by the custom word as typed. Port of
 * Handy's `apply_custom_words` (`src-tauri/src/audio_toolkit/text.rs`), stricter: phonetic factor 0.5, fuzzy matching
 * only for keys of 5+ characters, and only ASCII words. Runs of 1-3 words joined by spaces or tabs only (never across
 * punctuation or a line break) are compared by key (lowercase letters and digits): an equal key always matches
 * (casing, split words); otherwise the normalised Levenshtein distance, halved when the Soundex codes match, must stay
 * below 0.18. A plural (the key plus "s" or "es") is never a match. A letters-only custom word shorter than 5 matches
 * one word only ("is a" stays, not "Isa"; "gpt 5" still becomes "GPT-5"), and if it is all uppercase (an acronym:
 * "US", "IT") it leaves an all-lowercase word as is ("let us know"). The closest candidate wins, ties go to the longer
 * one. Unlike Handy, a fuzzy span gives way to a closer match starting inside it ("a deepgram" is not one fuzzy
 * "Deepgram": "deepgram" alone matches exactly). Everything outside a replaced span is kept as is.
 */
fun correctCustomWords(text: String, words: List<String>): String {
    if (words.isEmpty()) return text
    val custom = words.filter { word -> word.all { it.code < 128 } }
        .map { it to key(it) }
        .filter { (_, key) -> key.isNotEmpty() }
    if (custom.isEmpty()) return text
    val tokens = TOKEN.findAll(text).map { it.range }.toList()
    val result = StringBuilder()
    var copied = 0
    var i = 0
    while (i < tokens.size) {
        val best = bestMatch(text, tokens, i, custom)
        if (best == null || (i + 1 until i + best.n).any { j -> bestMatch(text, tokens, j, custom).beats(best) }) {
            i++
            continue
        }
        result.append(text, copied, tokens[i].first).append(best.word)
        copied = tokens[i + best.n - 1].last + 1
        i += best.n
    }
    if (copied == 0) return text
    return result.append(text, copied, text.length).toString()
}

/** A custom [word] matching [n] words with [score] (0 = same key). */
private class Match(val word: String, val score: Double, val n: Int)

private fun Match?.beats(other: Match): Boolean = this != null && score < other.score

/** The closest custom word to 1-3 words starting at token [i], or null if none is below the threshold. */
private fun bestMatch(text: String, tokens: List<IntRange>, i: Int, custom: List<Pair<String, String>>): Match? {
    var best: Match? = null
    var candidate = ""
    for (n in 1..MAX_NGRAM) {
        val last = i + n - 1
        if (last >= tokens.size) break
        val token = text.substring(tokens[last].first, tokens[last].last + 1)
        if (!token.all(::isAsciiLetterOrDigit)) break
        if (n > 1) {
            val gap = text.substring(tokens[last - 1].last + 1, tokens[last].first)
            if (!gap.all { it == ' ' || it == '\t' }) break
        }
        candidate += key(token)
        for ((word, wordKey) in custom) {
            if (wordKey.length < MIN_FUZZY_KEY_LENGTH && word.all { it.isLetter() }) {
                // Short plain words: no joins ("in a" is not "Ina"), and an acronym leaves a lowercase word as is.
                if (n > 1 || (word.all { it.isUpperCase() } && token.all { it.isLowerCase() })) continue
            }
            val score = score(candidate, wordKey) ?: continue
            val current = best
            val better = if (current == null) score < MATCH_THRESHOLD else {
                score < current.score || (score == current.score && n > current.n)
            }
            if (better) best = Match(word, score, n)
        }
    }
    return best
}

/**
 * 0 for equal keys, else the fuzzy score, or null when the pair isn't compared (a plural of [b], short, or too
 * different in length). [a] is the transcript's key, [b] the custom word's.
 */
private fun score(a: String, b: String): Double? {
    if (a == b) return 0.0
    if (a == b + "s" || a == b + "es") return null
    if (a.length < MIN_FUZZY_KEY_LENGTH || b.length < MIN_FUZZY_KEY_LENGTH) return null
    val maxLength = maxOf(a.length, b.length)
    if (abs(a.length - b.length) > maxOf(2.0, maxLength / 4.0)) return null
    val ratio = levenshtein(a, b).toDouble() / maxLength
    val phonetic = a.all { it in 'a'..'z' } && b.all { it in 'a'..'z' } && soundex(a) == soundex(b)
    return if (phonetic) ratio * PHONETIC_FACTOR else ratio
}

private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/** American Soundex of a lowercase a-z [word]: h and w don't separate equal codes, vowels and y do. */
private fun soundex(word: String): String {
    val code = StringBuilder().append(word[0])
    var last = soundexDigit(word[0])
    for (c in word.drop(1)) {
        if (code.length == 4) break
        val digit = soundexDigit(c)
        if (digit != '0' && digit != last) code.append(digit)
        if (c != 'h' && c != 'w') last = digit
    }
    while (code.length < 4) code.append('0')
    return code.toString()
}

private fun soundexDigit(c: Char): Char = when (c) {
    'b', 'f', 'p', 'v' -> '1'
    'c', 'g', 'j', 'k', 'q', 's', 'x', 'z' -> '2'
    'd', 't' -> '3'
    'l' -> '4'
    'm', 'n' -> '5'
    'r' -> '6'
    else -> '0'
}
