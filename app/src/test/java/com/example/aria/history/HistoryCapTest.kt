package com.example.aria.history

import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.CommandRecord
import com.example.aria.ui.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 12 — the cap rule, tested on its own.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE VIEWMODEL TESTS: the cap is behaviour, not
 * storage, and it is the one part of persistence that can be proven purely. Keeping
 * it in a pure function is what makes 200 rows testable in a millisecond instead of
 * by writing 201 records to a file on a device.
 */
class HistoryCapTest {

    private fun record(id: Long) = CommandRecord(
        id = id,
        timestampMillis = id,
        rawSpeech = "row $id",
        normalizedText = "row $id",
        commandType = "VOLUME_UP",
        handler = "VolumeActionHandler",
        status = AssistantStatus.SUCCESS,
        message = "ok",
        source = InputSource.VOICE
    )

    @Test
    fun `a list shorter than the cap is returned untouched`() {
        val records = (1L..5L).map(::record)

        val capped = capHistory(records)

        assertEquals(records, capped)
        // The very same instance: no copying work for the common case.
        assertSame(records, capped)
    }

    @Test
    fun `exactly the cap is not truncated`() {
        val records = (1L..MAX_HISTORY_RECORDS.toLong()).map(::record)

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        assertSame(records, capped)
    }

    @Test
    fun `one over the cap drops the oldest and keeps order`() {
        val records = (1L..(MAX_HISTORY_RECORDS + 1).toLong()).map(::record)

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        // The list is newest-first, so "the oldest" is the last element.
        assertEquals(2L, capped.first().id)
        assertEquals((MAX_HISTORY_RECORDS + 1).toLong(), capped.last().id)
        assertTrue("the dropped row must be the oldest", capped.none { it.id == 1L })
    }

    @Test
    fun `far over the cap keeps only the newest`() {
        val records = (1L..500L).map(::record)

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        assertEquals(301L, capped.first().id)
        assertEquals(500L, capped.last().id)
    }

    @Test
    fun `an empty list is empty, not an error`() {
        assertTrue(capHistory(emptyList()).isEmpty())
    }

    @Test
    fun `the cap is idempotent, so applying it twice is harmless`() {
        // The ViewModel caps the state list and the adapter caps what it writes.
        // If that were not idempotent, the second application would lose a row.
        val records = (1L..250L).map(::record)

        val once = capHistory(records)
        val twice = capHistory(once)

        assertEquals(once, twice)
    }

    @Test
    fun `a custom cap is honoured`() {
        val records = (1L..10L).map(::record)

        assertEquals(3, capHistory(records, max = 3).size)
        assertEquals(8L, capHistory(records, max = 3).first().id)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive cap is rejected rather than silently emptying the history`() {
        // A cap of 0 would erase the user's history on the next save. Better to
        // fail loudly at the one place that could be wrong.
        capHistory(listOf(record(1L)), max = 0)
    }
}
