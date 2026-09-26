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
        assertEquals("10:05", formatTimeMmSs(605))
        assertEquals("0:00", formatTimeMmSs(-10))
    }

    @Test
    fun testCalculateSpeechSeconds() {
        val sentences = listOf(
            "This is a sentence with seven words.", // 7 words
            "Here is another short test sentence.",  // 6 words
            "And a third one.",                     // 4 words
        )
        // Total words = 17 words. At 1.0x (2.5 words/sec), 17 / 2.5 = 6.8 -> 6 seconds
        val totalSec = calculateSpeechSeconds(sentences, 0, 1.0f)
        assertTrue("Expected between 5 and 8 seconds, got $totalSec", totalSec in 5..8)

        // Remaining from sentence 1 (sentences[1] + sentences[2] = 10 words)
        val remSec = calculateSpeechSeconds(sentences, 1, 1.0f)
        assertTrue("Expected between 3 and 5 seconds, got $remSec", remSec in 3..5)

        // Out of bounds
        assertEquals(0, calculateSpeechSeconds(sentences, 3, 1.0f))
        assertEquals(0, calculateSpeechSeconds(emptyList(), 0, 1.0f))
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
        assertEquals(true, state.hasNext)
        assertEquals(false, state.hasPrevious)

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
        assertEquals(true, advancedState.hasNext)
        assertEquals(false, advancedState.hasPrevious)
    }
}
