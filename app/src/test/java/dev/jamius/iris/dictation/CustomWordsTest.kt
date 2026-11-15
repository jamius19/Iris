/*
* Author: Jamius Siam
* Since: 15/11/2026
*/

package dev.jamius.iris.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CustomWordsTest {

    @Test
    fun exactKeyFixesTheCasing() {
        assertEquals("I run Kubernetes daily", correctCustomWords("I run kubernetes daily", listOf("Kubernetes")))
    }

    @Test
    fun correctTextIsUnchanged() {
        assertEquals("I use Deepgram.", correctCustomWords("I use Deepgram.", listOf("Deepgram")))
    }

    @Test
    fun splitWordsAreJoined() {
        assertEquals("try Deepgram now", correctCustomWords("try deep gram now", listOf("Deepgram")))
    }

    @Test
    fun digitsCount() {
        assertEquals("use GPT-5 here", correctCustomWords("use gpt 5 here", listOf("GPT-5")))
    }

    @Test
    fun similarSoundingWordsAreFixed() {
        assertEquals("I use Soniox daily", correctCustomWords("I use sonics daily", listOf("Soniox")))
        assertEquals("Kubernetes", correctCustomWords("Kubernetis", listOf("Kubernetes")))
    }

    @Test
    fun multiWordCustomWords() {
        assertEquals("hi Jamius Siam", correctCustomWords("hi jamius siam", listOf("Jamius Siam")))
    }

    @Test
    fun aFuzzySpanGivesWayToACloserMatchInsideIt() {
        assertEquals("a Deepgram key", correctCustomWords("a deepgram key", listOf("Deepgram")))
        // "a stackoverflw" alone is a fuzzy match too, but "stackoverflw" is closer.
        assertEquals(
            "a Stackoverflow answer",
            correctCustomWords("a stackoverflw answer", listOf("Stackoverflow"))
        )
    }

    @Test
    fun shortPlainWordsAreNotJoined() {
        assertEquals(
            "This is a test, I live in a house",
            correctCustomWords("This is a test, I live in a house", listOf("Isa", "Ina"))
        )
        assertEquals("a lot of stuff", correctCustomWords("a lot of stuff", listOf("Alot")))
        assertEquals("GPT-5", correctCustomWords("gpt 5", listOf("GPT-5")))
    }

    @Test
    fun pluralsAreNotMatched() {
        assertEquals(
            "two Teslas and three iPhones",
            correctCustomWords("two Teslas and three iPhones", listOf("Tesla", "iPhone"))
        )
        assertEquals("Deepgrams are cool", correctCustomWords("Deepgrams are cool", listOf("Deepgram")))
    }

    @Test
    fun acronymsLeaveLowercaseWords() {
        assertEquals("let us know about it", correctCustomWords("let us know about it", listOf("US", "IT")))
        assertEquals("the US market", correctCustomWords("the Us market", listOf("US")))
        assertEquals("the US market", correctCustomWords("the US market", listOf("US")))
        assertEquals("Go Ami", correctCustomWords("go ami", listOf("Go", "Ami")))
    }

    @Test
    fun sonOfIsNotShinobi() {
        assertEquals("the Son of man", correctCustomWords("the Son of man", listOf("Shinobi")))
    }

    @Test
    fun shortKeysGetNoFuzzyMatch() {
        assertEquals("Sam said", correctCustomWords("Sam said", listOf("Siam")))
    }

    @Test
    fun punctuationAndLineBreaksEndAnNgram() {
        val words = listOf("Deepgram", "Jamius Siam")
        assertEquals("deep. Gram", correctCustomWords("deep. Gram", words))
        assertEquals("Jamius, Siam", correctCustomWords("Jamius, Siam", words))
        assertEquals("deep\ngram", correctCustomWords("deep\ngram", words))
    }

    @Test
    fun punctuationAroundAMatchIsKept() {
        assertEquals("(Deepgram, ok)", correctCustomWords("(deepgram, ok)", listOf("Deepgram")))
    }

    @Test
    fun possessives() {
        assertEquals("Deepgram's model", correctCustomWords("Deepgram's model", listOf("Deepgram")))
        assertEquals("Deepgram's model", correctCustomWords("deepgram's model", listOf("Deepgram")))
    }

    @Test
    fun nonAsciiTokensAndWordsAreSkipped() {
        assertEquals("un café", correctCustomWords("un café", listOf("Cafe")))
        assertEquals("привет мир", correctCustomWords("привет мир", listOf("Privet")))
        assertEquals("a cafe", correctCustomWords("a cafe", listOf("Café")))
    }

    @Test
    fun tiesPreferTheLongerNgram() {
        assertEquals("Deepgram", correctCustomWords("deep gram", listOf("Deep", "Deepgram")))
    }

    @Test
    fun noWordsIsIdentity() {
        val text = "deep gram"
        assertSame(text, correctCustomWords(text, emptyList()))
    }
}
