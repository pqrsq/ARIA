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

    /**
     * A list shaped like the app's: **index 0 is the NEWEST**.
     *
     * WHY THIS HELPER EXISTS: the first version of these tests built
     * `(1..n).map(::record)` — ascending ids — and then asserted on "first" and
     * "last" as if the list were newest-first. The cap keeps the FIRST `max`
     * elements, so with ascending ids it dropped the newest and kept the oldest:
     * the tests were checking a convention the app never uses, and the assertions
     * could not hold. `publish()` builds `listOf(new) + old`, so newest really is
     * at index 0; the fixture has to match or the test proves nothing.
     */
    private fun newest(count: Int): List<CommandRecord> =
        (count downTo 1).map { record(it.toLong()) }

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
        val records = newest(5)

        val capped = capHistory(records)

        assertEquals(records, capped)
        // The very same instance: no copying work for the common case.
        assertSame(records, capped)
    }

    @Test
    fun `exactly the cap is not truncated`() {
        val records = newest(MAX_HISTORY_RECORDS)

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        assertSame(records, capped)
    }

    @Test
    fun `one over the cap drops the oldest and keeps order`() {
        // ids run MAX+1 (newest) down to 1 (oldest), so the evicted row is id 1.
        val records = newest(MAX_HISTORY_RECORDS + 1)

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        // Order is preserved: the newest stays first, the second-oldest is last.
        assertEquals((MAX_HISTORY_RECORDS + 1).toLong(), capped.first().id)
        assertEquals(2L, capped.last().id)
        assertTrue("the dropped row must be the oldest", capped.none { it.id == 1L })
        // The surviving ids are exactly the newest MAX rows, in order.
        assertEquals(
            ((MAX_HISTORY_RECORDS + 1) downTo 2).map { it.toLong() },
            capped.map { it.id }
        )
    }

    @Test
    fun `far over the cap keeps only the newest`() {
        val records = newest(500)

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        // Newest 200 of 500: ids 500 down to 301.
        assertEquals(500L, capped.first().id)
        assertEquals(301L, capped.last().id)
        assertTrue("nothing older than 301 may survive", capped.all { it.id > 300L })
    }

    @Test
    fun `an empty list is empty, not an error`() {
        assertTrue(capHistory(emptyList()).isEmpty())
    }

    @Test
    fun `the cap is idempotent, so applying it twice is harmless`() {
        // The ViewModel caps the state list and the adapter caps what it writes.
        // If that were not idempotent, the second application would lose a row.
        val records = newest(250)

        val once = capHistory(records)
        val twice = capHistory(once)

        assertEquals(once, twice)
    }

    @Test
    fun `a custom cap is honoured`() {
        // Newest-first 10..1, capped to 3 -> 10, 9, 8.
        val records = newest(10)

        val capped = capHistory(records, max = 3)

        assertEquals(3, capped.size)
        assertEquals(listOf(10L, 9L, 8L), capped.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive cap is rejected rather than silently emptying the history`() {
        // A cap of 0 would erase the user's history on the next save. Better to
        // fail loudly at the one place that could be wrong.
        capHistory(newest(1), max = 0)
    }
}
