package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.ui.speech.SpeechState
import com.charleshartman.porchlightpress.ui.speech.calculateSpeechSeconds
import com.charleshartman.porchlightpress.ui.speech.formatTimeMmSs
import com.charleshartman.porchlightpress.ui.speech.splitSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SpeechServiceTest {

    @Test
    fun testFormatTimeMmSs() {
        assertEquals("0:00", formatTimeMmSs(0))
        assertEquals("0:05", formatTimeMmSs(5))
        assertEquals("0:59", formatTimeMmSs(59))
        assertEquals("1:00", formatTimeMmSs(60))
        assertEquals("1:15", formatTimeMmSs(75))
        assertEquals("1:24", formatTimeMmSs(84))
        assertEquals("2:05", formatTimeMmSs(125))
        assertEquals("10:05", formatTimeMmSs(605))
        assertEquals("0:00", formatTimeMmSs(-10))
    }

    @Test
    fun testCalculateSpeechSeconds() {
        val sentences = listOf(
            "This is a sentence with seven words.",  // 7 words
            "Here is another short test sentence.",  // 6 words
            "And a third one.",                      // 4 words
        )
        // Total = 17 words. At 1.0x (2.5 words/sec): 17 / 2.5 = 6.8 -> 6 seconds
        val totalSec = calculateSpeechSeconds(sentences, 0, 1.0f)
        assertEquals(6, totalSec)

        // Remaining from sentence 1 (sentences[1] + sentences[2] = 10 words): 10 / 2.5 = 4
        val remSec = calculateSpeechSeconds(sentences, 1, 1.0f)
        assertEquals(4, remSec)

        // Out of bounds
        assertEquals(0, calculateSpeechSeconds(sentences, 3, 1.0f))
        assertEquals(0, calculateSpeechSeconds(emptyList(), 0, 1.0f))
    }

    @Test
    fun testCalculateSpeechSecondsIsMonotonicInRemainingSentences() {
        val sentences = listOf(
            "This is the headline of the story.",
            "Here is the second sentence which has more words to read aloud.",
            "And a concluding sentence to finish up the brief.",
        )
        val total = calculateSpeechSeconds(sentences, 0, 1.0f)
        val fromMiddle = calculateSpeechSeconds(sentences, 1, 1.0f)
        val fromEnd = calculateSpeechSeconds(sentences, 2, 1.0f)

        assertTrue("Total should be positive", total > 0)
        assertTrue(fromMiddle < total)
        assertTrue(fromEnd < fromMiddle)
        assertEquals(0, calculateSpeechSeconds(sentences, 3, 1.0f))
    }

    @Test
    fun testCalculateSpeechSecondsWithSpeed() {
        val sentences = listOf(
            "A moderately long sentence containing fifteen distinct words to test rate scaling properly here." // 14 words
        )
        // 14 words at 2.5 words/sec: 14/2.5 = 5.6 -> 5 (truncated)
        assertEquals(5, calculateSpeechSeconds(sentences, 0, 1.0f))
        // 2.0x speed: 14/5 = 2.8 -> 2
        assertEquals(2, calculateSpeechSeconds(sentences, 0, 2.0f))
        // 0.5x speed: 14/1.25 = 11.2 -> 11
        assertEquals(11, calculateSpeechSeconds(sentences, 0, 0.5f))
    }

    @Test
    fun testSplitSentences() {
        val text = "First sentence! Second sentence? Third sentence. And fourth."
        val sentences = splitSentences(text, Locale.US)
        assertEquals(4, sentences.size)
        assertEquals("First sentence!", sentences[0])
        assertEquals("Second sentence?", sentences[1])
        assertEquals("Third sentence.", sentences[2])
        assertEquals("And fourth.", sentences[3])
    }

    @Test
    fun testSplitSentencesDropsBlankTrailingSegments() {
        val text = "First sentence here. Second sentence follows! Third one ends with a question?"
        val split = splitSentences(text, Locale.ENGLISH)
        assertEquals(3, split.size)
        assertEquals("First sentence here.", split[0])
        assertEquals("Second sentence follows!", split[1])
        assertEquals("Third one ends with a question?", split[2])
    }

    @Test
    fun testSpeechStateBackwardCompatibility() {
        // Construct with original 6 args
        val state = SpeechState(
            playing = true,
            title = "Test Article",
            sentence = "First sentence.",
            index = 0,
            count = 5,
            error = null,
        )
        assertEquals(true, state.playing)
        assertEquals("Test Article", state.title)
        assertEquals(0, state.totalSeconds)
        assertEquals(0, state.remainingSeconds)

        // Construct with 8 args
        val advancedState = SpeechState(
            playing = true,
            title = "Test Article",
            sentence = "First sentence.",
            index = 0,
            count = 5,
            totalSeconds = 60,
            remainingSeconds = 45,
            error = null,
        )
        assertEquals(60, advancedState.totalSeconds)
        assertEquals(45, advancedState.remainingSeconds)
    }
}
