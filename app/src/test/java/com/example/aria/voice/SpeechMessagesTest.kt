package com.example.aria.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the promise that no speech outcome is ever silent.
 *
 * PHASE 4 WILL DELETE THIS FILE: it reflects on a `when` that the Kotlin
 * compiler already exhausts, which is only worth having while the taxonomy is
 * new and likely to grow. Once `AssistantCommand` owns the user-facing outcomes,
 * the parser tests cover this instead.
 */
class SpeechMessagesTest {

    @Test
    fun `every SpeechError has its own distinct message`() {
        val messages = SpeechError.entries.map(SpeechMessages::forError)

        assertEquals(
            "Two errors share the same wording, so the user cannot tell them apart",
            SpeechError.entries.size,
            messages.toSet().size
        )
    }

    @Test
    fun `messages are sentences, not exception names`() {
        SpeechError.entries.forEach { error ->
            val message = SpeechMessages.forError(error)
            assertTrue("$error looks technical: $message", message.first().isUpperCase())
            assertTrue("$error has no closing punctuation: $message", message.last() in ".!")
        }
    }
}
