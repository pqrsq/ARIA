package com.example.aria.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 3 — the only completely pure part of the speaking pipeline.
 *
 * These tests exist because a TTS reply is easy to get subtly wrong: empty text
 * makes the engine do nothing silently, and an endlessly repeated phrase makes
 * the app feel broken.
 */
class TtsAcknowledgementsTest {

    @Test
    fun `first turn uses a neutral acknowledgement`() {
        assertEquals("I heard you.", TtsAcknowledgements.forTurn(0))
    }

    @Test
    fun `consecutive turns do not repeat the same reply`() {
        val first = TtsAcknowledgements.forTurn(0)
        val second = TtsAcknowledgements.forTurn(1)

        assertNotEquals("Two turns in a row must not say the same thing", first, second)
    }

    @Test
    fun `the cycle wraps around instead of running out`() {
        val size = TtsAcknowledgements.ALL.size

        assertEquals(TtsAcknowledgements.forTurn(0), TtsAcknowledgements.forTurn(size))
        assertEquals(
            TtsAcknowledgements.forTurn(1),
            TtsAcknowledgements.forTurn(size * 4 + 1)
        )
    }

    @Test
    fun `a negative turn index still yields a real acknowledgement`() {
        // Guards against an integer counter ever wrapping below zero.
        assertTrue(TtsAcknowledgements.forTurn(-1) in TtsAcknowledgements.ALL)
    }

    @Test
    fun `every acknowledgement is short, plain and non-empty`() {
        TtsAcknowledgements.ALL.forEach { reply ->
            assertTrue("Blank reply would make the engine silent", reply.isNotBlank())
            assertTrue("Too long to speak after every utterance: $reply", reply.length <= 24)
            assertTrue("Replies are sentences: $reply", reply.last() in ".!")
        }
    }

    @Test
    fun `no acknowledgement claims a command was performed`() {
        // Phase 3 has no parser, so nothing may imply an action happened.
        val forbidden = listOf("turned", "opened", "playing", "volume", "sending", "launched")
        TtsAcknowledgements.ALL.forEach { reply ->
            val lower = reply.lowercase()
            forbidden.forEach { word ->
                assertTrue("'$reply' implies an action ('$word')", !lower.contains(word))
            }
        }
    }

    @Test
    fun `every tts error maps to its own non-technical sentence`() {
        val messages = TtsError.entries.map(TtsMessages::forError)

        assertEquals(
            "Two TTS errors share wording, so the user cannot tell them apart",
            TtsError.entries.size,
            messages.toSet().size
        )
        messages.forEach { message ->
            assertTrue("Looks technical: $message", message.first().isUpperCase())
            assertTrue("No closing punctuation: $message", message.last() in ".!")
        }
    }
}
