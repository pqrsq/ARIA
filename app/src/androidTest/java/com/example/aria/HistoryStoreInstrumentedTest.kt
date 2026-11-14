package com.example.aria

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aria.history.FileHistoryStore
import com.example.aria.history.MAX_HISTORY_RECORDS
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.CommandRecord
import com.example.aria.ui.InputSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PHASE 12 — the file-backed store, on a device.
 *
 * WHY THIS CANNOT BE A JVM TEST: [FileHistoryStore] uses `org.json` and
 * `android.util.Log`, both of which are stubs in unit tests and throw at runtime.
 * So the adapter's real behaviour — round-tripping through JSON, recovering from a
 * corrupt file, writing atomically — is proven here, against a private directory
 * that is deleted afterwards.
 *
 * WHAT A TEST CANNOT PROVE: "survives process death" in the literal sense, because
 * the instrumentation process stays alive. What it *can* prove is the mechanism
 * that makes it true: a second store instance over the same directory reads back
 * exactly what the first wrote, with no state shared in memory.
 */
@RunWith(AndroidJUnit4::class)
class HistoryStoreInstrumentedTest {

    private lateinit var directory: File

    private fun record(
        id: Long,
        transcript: String = "turn the volume up",
        status: AssistantStatus = AssistantStatus.SUCCESS,
        source: InputSource = InputSource.VOICE
    ) = CommandRecord(
        id = id,
        timestampMillis = id * 1_000L,
        rawSpeech = transcript,
        normalizedText = transcript.lowercase(),
        commandType = "VOLUME_UP",
        handler = "VolumeActionHandler",
        status = status,
        message = "message $id",
        source = source
    )

    private fun newStore() = FileHistoryStore(directory)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.filesDir, "history-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun aMissingFile_isAnEmptyHistory() = runBlocking {
        assertTrue(newStore().load().isEmpty())
    }

    @Test
    fun records_roundTripThroughJson() = runBlocking {
        val written = listOf(
            record(3, "third", source = InputSource.TEXT),
            record(2, "second", status = AssistantStatus.REJECTED),
            record(1, "first", status = AssistantStatus.ERROR)
        )

        newStore().save(written)

        // A SECOND store over the same directory: nothing is shared in memory, so
        // this is the same path a fresh process would take.
        val read = newStore().load()

        assertEquals(written, read)
        assertEquals(InputSource.TEXT, read.first().source)
        assertEquals(AssistantStatus.REJECTED, read[1].status)
        assertEquals(AssistantStatus.ERROR, read[2].status)
    }

    @Test
    fun load_returnsNewestFirstEvenIfTheFileDoesNot() = runBlocking {
        // The UI renders list order directly, so ordering must not depend on the
        // writer having stored the rows in the right order.
        newStore().save(listOf(record(1), record(3), record(2)))

        assertEquals(listOf(3L, 2L, 1L), newStore().load().map { it.id })
    }

    @Test
    fun aCorruptFile_recoversToAnEmptyHistoryInsteadOfCrashing() = runBlocking {
        val store = newStore()
        store.file.writeText("{ this is not json ][")

        assertTrue(store.load().isEmpty())
    }

    @Test
    fun aTruncatedFile_recoversToAnEmptyHistory() = runBlocking {
        // Cut off halfway through a key: what a process death mid-write would
        // have looked like before the atomic rename made that impossible.
        newStore().file.writeText("""[{"id":1,"timestampMillis":1000,"rawSpe""")

        assertTrue(newStore().load().isEmpty())
    }

    @Test
    fun anEmptyFile_isAnEmptyHistory() = runBlocking {
        newStore().file.writeText("")

        assertTrue(newStore().load().isEmpty())
    }

    @Test
    fun oneUnreadableRow_doesNotDiscardTheOthers() = runBlocking {
        // A row without an id cannot be ordered, so it is dropped; the rest must
        // survive, because losing one debug row beats losing the whole history.
        newStore().file.writeText(
            """[
                {"id":2,"timestampMillis":2000,"rawSpeech":"kept second"},
                {"timestampMillis":1500,"rawSpeech":"no id, dropped"},
                {"id":1,"timestampMillis":1000,"rawSpeech":"kept first"}
            ]"""
        )

        val read = newStore().load()

        assertEquals(listOf("kept second", "kept first"), read.map { it.rawSpeech })
    }

    @Test
    fun unknownEnumNames_fallBackInsteadOfFailing() = runBlocking {
        // A file written by a newer build must not make an older build crash.
        newStore().file.writeText(
            """[{"id":1,"timestampMillis":1000,"rawSpeech":"x","status":"FROM_THE_FUTURE","source":"HOLOGRAM"}]"""
        )

        val read = newStore().load()

        assertEquals(1, read.size)
        assertEquals(AssistantStatus.SUCCESS, read.single().status)
        assertEquals(InputSource.VOICE, read.single().source)
    }

    @Test
    fun save_truncatesToTheCap() = runBlocking {
        val many = (1L..(MAX_HISTORY_RECORDS + 20).toLong()).map { record(it) }

        newStore().save(many)

        assertEquals(MAX_HISTORY_RECORDS, newStore().load().size)
    }

    @Test
    fun save_replacesTheFileRatherThanAppending() = runBlocking {
        val store = newStore()
        store.save(listOf(record(1), record(2)))
        store.save(listOf(record(3)))

        assertEquals(listOf(3L), store.load().map { it.id })
    }

    @Test
    fun clear_removesEverythingAndIsIdempotent() = runBlocking {
        val store = newStore()
        store.save(listOf(record(1)))

        store.clear()
        assertTrue(store.load().isEmpty())

        // Clearing twice must not throw.
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun save_leavesNoTemporaryFileBehind() = runBlocking {
        val store = newStore()
        store.save(listOf(record(1)))

        val leftovers = directory.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()

        assertTrue("a temp file was left behind: $leftovers", leftovers.isEmpty())
        assertTrue(store.file.isFile)
    }

    @Test
    fun theFileIsNamedInOneaPlaceSoTheBackupRulesCanExcludeIt() {
        // The backup rules exclude "history.json" by name. If the adapter ever
        // renamed the file, the exclusion would silently stop applying and
        // transcripts would start leaving the device — so the name is pinned here.
        assertEquals("history.json", FileHistoryStore.FILE_NAME)
        assertEquals("history.json", newStore().file.name)
    }

    @Test
    fun save_createsTheDirectoryWhenMissing() = runBlocking {
        val missing = File(directory, "nested/does-not-exist")
        val store = FileHistoryStore(missing)

        store.save(listOf(record(1)))

        assertTrue(store.file.isFile)
        assertFalse(newStore().load().isEmpty())
    }
}
