package com.example.aria.ui

import com.example.aria.MainDispatcherRule
import com.example.aria.actions.ActionMessages
import com.example.aria.actions.FakeActionExecutor
import com.example.aria.command.CommandParser
import com.example.aria.command.RuleBasedCommandParser
import com.example.aria.history.FakeHistoryStore
import com.example.aria.history.MAX_HISTORY_RECORDS
import com.example.aria.history.capHistory
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.permissions.PolicyAccessChecker
import com.example.aria.voice.FakeSpeechRecognizerManager
import com.example.aria.voice.FakeTextToSpeechManager
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PHASE 12 — history persistence, from the ViewModel's side.
 *
 * WHAT THIS PROVES AND WHAT IT DOES NOT: everything here runs against
 * [FakeHistoryStore], so it pins the *wiring* — load on init, exactly one row per
 * command, nothing for a suppressed duplicate, the cap, clear, a broken store not
 * crashing the app. It cannot prove the JSON file survives a process death; that
 * is genuinely device-only and is in `HistoryStoreInstrumentedTest` and the manual
 * script. Pretending otherwise with a temp-directory test would be a lie, because
 * `org.json` and `android.util.Log` are stubs on the JVM.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelHistoryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val speech = FakeSpeechRecognizerManager()
    private val tts = FakeTextToSpeechManager()
    private val executor = FakeActionExecutor()
    private val parser: CommandParser = RuleBasedCommandParser()

    private var nowMillis: Long = 1_000L

    /** @param store the fake to wire in, so a test can seed or inspect it. */
    private fun viewModel(store: FakeHistoryStore): AssistantViewModel {
        speech.recognitionAvailable = true
        tts.isReady = true
        return AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { true },
            textToSpeechManager = tts,
            commandParser = parser,
            actionExecutor = executor,
            computeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis },
            policyAccessChecker = PolicyAccessChecker { false },
            historyStore = store
        )
    }

    /** One complete interaction: tap, speak, final transcript. */
    private fun AssistantViewModel.say(transcript: String) {
        onMicrophoneTapped()
        speech.emitSuccessfulSession(transcript)
    }

    /** A stored row, as an earlier session would have left it. */
    private fun record(
        id: Long,
        transcript: String,
        timestamp: Long = 1_000L,
        status: AssistantStatus = AssistantStatus.SUCCESS,
        source: InputSource = InputSource.VOICE
    ) = CommandRecord(
        id = id,
        timestampMillis = timestamp,
        rawSpeech = transcript,
        normalizedText = transcript.lowercase(),
        commandType = "VOLUME_UP",
        handler = "VolumeActionHandler",
        status = status,
        message = "stored",
        source = source
    )

    // ── Load on init ─────────────────────────────────────────────────────────

    @Test
    fun `init loads persisted records into the state in their stored order`() {
        val stored = listOf(
            record(id = 3, transcript = "third", timestamp = 3_000),
            record(id = 2, transcript = "second", timestamp = 2_000),
            record(id = 1, transcript = "first", timestamp = 1_000)
        )
        val store = FakeHistoryStore(stored = stored)

        val vm = viewModel(store)

        assertEquals(1, store.loadCount)
        assertEquals(3, vm.uiState.value.history.size)
        // Newest first, exactly as stored: the UI renders list order directly, so
        // a reordering here would be a visible regression.
        assertEquals(
            listOf("third", "second", "first"),
            vm.uiState.value.history.map { it.rawSpeech }
        )
        // Everything the row carries survives, including the P1.6 marker.
        assertEquals(stored, vm.uiState.value.history)
    }

    @Test
    fun `an empty store leaves the history empty, not broken`() {
        val vm = viewModel(FakeHistoryStore(stored = emptyList()))

        assertTrue(vm.uiState.value.history.isEmpty())
        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)
    }

    // ── A completed command persists one row ─────────────────────────────────

    @Test
    fun `a completed command persists exactly one record`() = runTest(dispatcher) {
        val store = FakeHistoryStore()
        val vm = viewModel(store)

        vm.say("turn the volume up")

        assertEquals(1, vm.uiState.value.history.size)
        // The LAST save is the one that matters; earlier saves (there are none
        // here, but there can be after a load) are superseded by it.
        assertEquals(1, store.saves.size)
        assertEquals(1, store.stored.size)
        val persisted = store.stored.single()
        // Persisted EXACTLY as displayed: requirement 6 forbids changing history
        // semantics, so every field must match the in-memory row.
        assertEquals(vm.uiState.value.history.single(), persisted)
        assertEquals("turn the volume up", persisted.rawSpeech)
        assertEquals("VOLUME_UP", persisted.commandType)
        assertEquals(AssistantStatus.SUCCESS, persisted.status)
        assertEquals(InputSource.VOICE, persisted.source)
    }

    @Test
    fun `a typed command persists with its own source marker`() = runTest(dispatcher) {
        val store = FakeHistoryStore()
        val vm = viewModel(store)

        vm.onSubmitTextInput("turn the volume up")

        assertEquals(InputSource.TEXT, store.stored.single().source)
    }

    @Test
    fun `a rejected command persists with the status it displays`() = runTest(dispatcher) {
        val store = FakeHistoryStore()
        val vm = viewModel(store)

        vm.onSubmitTextInput("set the volume to 120 percent")

        assertEquals(AssistantStatus.REJECTED, vm.uiState.value.status)
        val persisted = store.stored.single()
        assertEquals(AssistantStatus.REJECTED, persisted.status)
        assertEquals(ActionMessages.VOLUME_OUT_OF_RANGE, persisted.message)
        assertEquals(vm.uiState.value.history.single(), persisted)
    }

    @Test
    fun `an unrecognised command persists too`() = runTest(dispatcher) {
        // NotUnderstood rows are "success" rows in the UI (nothing failed), and
        // requirement 6 says the persisted list is the in-memory list.
        val store = FakeHistoryStore()
        val vm = viewModel(store)

        vm.say("make me a sandwich")

        assertEquals(1, store.stored.size)
        assertEquals("UNKNOWN", store.stored.single().commandType)
        assertEquals(vm.uiState.value.history.single(), store.stored.single())
    }

    @Test
    fun `a speech failure persists its failure row`() = runTest(dispatcher) {
        val store = FakeHistoryStore()
        val vm = viewModel(store)

        vm.onMicrophoneTapped()
        speech.emit(SpeechEvent.Failure(SpeechError.NO_MATCH))
        tts.finishSpeaking()

        assertEquals(AssistantStatus.ERROR, vm.uiState.value.history.first().status)
        assertEquals(1, store.stored.size)
        assertEquals(AssistantStatus.ERROR, store.stored.single().status)
    }

    // ── D1 must not double-persist ───────────────────────────────────────────

    @Test
    fun `a duplicate-suppressed repeat persists nothing new`() = runTest(dispatcher) {
        val store = FakeHistoryStore()
        val vm = viewModel(store)

        vm.say("turn the volume up")
        val savesAfterFirst = store.saves.size

        nowMillis += 300L
        vm.say("turn the volume up")

        // D1: one row in memory, one on disk, and no second save at all — the
        // suppression returns before publish(), where persisting happens.
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(1, store.stored.size)
        assertEquals(savesAfterFirst, store.saves.size)
    }

    // ── The cap ──────────────────────────────────────────────────────────────

    @Test
    fun `the cap keeps the newest two hundred and drops the oldest`() {
        // The rule lives in the pure layer, so it is asserted directly here…
        val records = (1..(MAX_HISTORY_RECORDS + 1)).map { index ->
            record(id = index.toLong(), transcript = "row $index", timestamp = index.toLong())
        }

        val capped = capHistory(records)

        assertEquals(MAX_HISTORY_RECORDS, capped.size)
        assertEquals("row 1", records.first().rawSpeech)
        // …the OLDEST is the one that goes.
        assertEquals("row 2", capped.first().rawSpeech)
        assertEquals("row ${MAX_HISTORY_RECORDS + 1}", capped.last().rawSpeech)
    }

    @Test
    fun `the ViewModel never puts more than the cap into the state or the store`() = runTest(dispatcher) {
        // Seeded at the cap, so one more command must evict exactly one row.
        val seeded = (1..MAX_HISTORY_RECORDS).map { index ->
            record(id = index.toLong(), transcript = "row $index", timestamp = index.toLong())
        }
        val store = FakeHistoryStore(stored = seeded)
        val vm = viewModel(store)
        assertEquals(MAX_HISTORY_RECORDS, vm.uiState.value.history.size)

        nowMillis += 10_000L
        vm.say("turn the volume up")

        assertEquals(MAX_HISTORY_RECORDS, vm.uiState.value.history.size)
        assertEquals(MAX_HISTORY_RECORDS, store.stored.size)
        // The new row is at the top; the oldest seeded row is gone.
        assertEquals("turn the volume up", store.stored.first().rawSpeech)
        assertNotEquals("row 1", store.stored.last().rawSpeech)
    }

    // ── A broken store must not break the app ────────────────────────────────

    @Test
    fun `a store that fails to load yields an empty history and no crash`() = runTest(dispatcher) {
        val store = FakeHistoryStore().apply { loadFailure = IllegalStateException("disk on fire") }

        val vm = viewModel(store)

        assertTrue(vm.uiState.value.history.isEmpty())
        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)

        // And the app still works afterwards: the failure is confined to history.
        vm.say("turn the volume up")
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)
    }

    @Test
    fun `a store that fails to save never disturbs the command result`() = runTest(dispatcher) {
        val store = FakeHistoryStore().apply { saveFailure = IllegalStateException("read-only fs") }
        val vm = viewModel(store)

        vm.say("turn the volume up")

        // The in-memory result is authoritative for the session.
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals("VOLUME_UP", vm.uiState.value.commandType)
        tts.finishSpeaking()
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    @Test
    fun `clear empties the state and the store`() = runTest(dispatcher) {
        val store = FakeHistoryStore()
        val vm = viewModel(store)
        vm.say("turn the volume up")
        assertEquals(1, store.stored.size)

        vm.clearHistory()

        assertTrue(vm.uiState.value.history.isEmpty())
        assertEquals(1, store.clearCount)
        assertTrue(store.stored.isEmpty())
    }

    @Test
    fun `clearing before the initial load finishes does not resurrect the rows`() = runTest(dispatcher) {
        // THE RACE THIS EXISTS FOR: the load is asynchronous, so a clear issued
        // while it is still in flight could otherwise be overwritten by the load
        // prepending the stored rows straight back into the state.
        val gate = CompletableDeferred<Unit>()
        val store = FakeHistoryStore(
            stored = listOf(record(id = 1, transcript = "stored row"))
        ).apply { loadGate = { gate.await() } }

        val vm = viewModel(store)
        // The load is now suspended inside the gate.
        vm.clearHistory()
        gate.complete(Unit)

        assertTrue(
            "a clear must not be undone by the pending load: ${vm.uiState.value.history}",
            vm.uiState.value.history.isEmpty()
        )
        assertTrue(store.stored.isEmpty())
    }

    // ── No production no-op default ──────────────────────────────────────────

    @Test
    fun `the store is required so production cannot silently skip persistence`() {
        // WHAT THIS CAN AND CANNOT CHECK: "no default value" is a Kotlin
        // compile-time property and is invisible to reflection, so a test cannot
        // assert it directly. What it CAN assert is the part that would actually
        // bite: the ViewModel takes a HistoryStore, and it is used rather than
        // ignored — a store whose load is never called would mean the
        // constructor grew a parameter nothing reads.
        val store = FakeHistoryStore(stored = listOf(record(id = 1, transcript = "mine")))
        val vm = viewModel(store)

        assertEquals("the store must be read on init", 1, store.loadCount)
        assertEquals("mine", vm.uiState.value.history.single().rawSpeech)

        vm.say("turn the volume up")
        assertEquals("the store must be written after a command", 1, store.saves.size)
    }
}

