package com.example.aria.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 unit tests.
 *
 * These test the state model only — no Android framework, no device, no
 * microphone. `./gradlew test` runs them in a couple of seconds, which is what
 * makes the "build + test after every phase" workflow cheap.
 *
 * From Phase 5 the parser tests described in the brief will join this folder.
 */
class AssistantUiStateTest {

    @Test
    fun `initial state is idle, empty and not debuggable by default`() {
        val state = AssistantUiState()

        assertEquals(AssistantStatus.IDLE, state.status)
        assertTrue(state.history.isEmpty())
        assertFalse(state.debugMode)
        assertFalse("A fresh state must not render a result card", state.hasResult)
    }

    @Test
    fun `hasResult is true when speech was recognised`() {
        val state = AssistantUiState(rawSpeech = "turn the volume down")
        assertTrue(state.hasResult)
    }

    @Test
    fun `hasResult is false for a listening state without speech`() {
        val state = AssistantUiState(status = AssistantStatus.LISTENING)
        assertFalse(state.hasResult)
    }

    @Test
    fun `command record exposes stable command type for history and debug`() {
        val record = CommandRecord(
            id = 1L,
            timestampMillis = 1_700_000_000_000L,
            rawSpeech = "Could you make the phone quieter?",
            normalizedText = "could you make the phone quieter",
            commandType = "VOLUME_DOWN",
            handler = "VolumeActionHandler",
            status = AssistantStatus.SUCCESS
        )

        assertEquals("VOLUME_DOWN", record.commandType)
        assertEquals(AssistantStatus.SUCCESS, record.status)
    }
}
