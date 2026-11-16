package com.example.aria.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aria.actions.ActionExecutor
import com.example.aria.actions.CommandTypeNaming
import com.example.aria.actions.ActionMessages
import com.example.aria.actions.ExecutionResult
import com.example.aria.actions.HandlerNames
import com.example.aria.command.AssistantCommand
import com.example.aria.command.CommandParser
import com.example.aria.history.HistoryStore
import com.example.aria.history.capHistory
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.permissions.PolicyAccessChecker
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import com.example.aria.voice.SpeechMessages
import com.example.aria.voice.SpeechRecognizerManager
import com.example.aria.voice.SpeakPriority
import com.example.aria.voice.TextToSpeechManager
import com.example.aria.voice.TtsAcknowledgements
import com.example.aria.voice.TtsError
import com.example.aria.voice.TtsEvent
import com.example.aria.voice.TtsMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the screen state for the assistant.
 *
 * ── EARLIER PHASES, STILL IN FORCE ──────────────────────────────────────────
 *  1. exposes an immutable [AssistantUiState] through a StateFlow,
 *  2. asks for the microphone permission at the moment of use, never at start-up,
 *  3. folds [SpeechRecognizerManager]'s [SpeechEvent]s into the same state fields
 *     Phase 1 introduced (rawSpeech → status → history),
 *  4. speaks through [TextToSpeechManager] unless the user muted ARIA or the
 *     engine is unavailable — a short acknowledgement while the action runs, then
 *     the real result sentence.
 *
 * ── PHASE 6 SCOPE ────────────────────────────────────────────────────────────
 * Speech in, action out. A final transcript now goes through
 * [CommandParser] → [AssistantCommand] → [ActionExecutor] → [ExecutionResult],
 * and the result — never an invented one — becomes the card, the history row and
 * the spoken reply:
 *
 * ```
 * FinalResult(text)
 *    └─ parse (Dispatchers.Default)
 *         └─ execute (Dispatchers.IO)
 *              └─ ExecutionResult → AssistantUiState → TTS
 * ```
 *
 * THREADING: `SpeechRecognizer` requires the main thread, so the *speech* flow
 * stays on `viewModelScope` (Main.immediate). Parsing and execution are pushed
 * off it explicitly, because execution does `PackageManager` queries and
 * `startActivity` — both of which can block long enough to look like a freeze.
 * State is only ever *updated* on the main dispatcher, so `MutableStateFlow`
 * updates from the IO hop are applied back on Main via [withContext].
 *
 * ── TEST SEAMS ──────────────────────────────────────────────────────────────
 * The last four constructor parameters exist only so a JVM test can be
 * deterministic, and all four have production defaults that callers never
 * override:
 *  - `computeDispatcher` — where the pure parser runs,
 *  - `ioDispatcher` — where the executor runs (system services, `startActivity`),
 *  - `policyAccessChecker` — the Android-free seam that answers "did the user
 *    grant Do Not Disturb access while they were away?" (Phase 6.5, D2),
 *  - `clockMillis` — so the one-second duplicate window can be asserted exactly
 *    instead of by sleeping (a timing-dependent test is worse than no test).
 * ─────────────────────────────────────────────────────────────────────────────
 */
class AssistantViewModel(
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val permissionChecker: MicrophonePermissionChecker,
    private val textToSpeechManager: TextToSpeechManager,
    private val commandParser: CommandParser,
    private val actionExecutor: ActionExecutor,
    // Injected execution seams; production always uses the defaults. See the
    // "TEST SEAMS" note in the class KDoc for why these three exist.
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * PHASE 6.5 (D2) — asks whether Do Not Disturb access is now granted, so a
     * return from Settings can retry the refused command. Injected as a port to
     * keep this class Android-free.
     */
    private val policyAccessChecker: PolicyAccessChecker =
        PolicyAccessChecker { false },
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    /**
     * PHASE 12 — where the history rows live between sessions.
     *
     * REQUIRED, DELIBERATELY: no default. A default could only be a no-op store,
     * which would make "history is persisted" quietly false in production while
     * every test still passed with a fake. Because it has no default it is also
     * the LAST parameter, so every existing positional construction keeps working
     * — except that it must now actually be supplied, which is the point.
     */
    private val historyStore: HistoryStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState(
        // Tell the truth immediately: an engine that is still binding, or that
        // will never work, must not leave the switch promising sound.
        isTtsAvailable = textToSpeechManager.isReady
    ))
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Monotonic id source for history entries. */
    private val nextId = AtomicLong(1L)

    /**
     * PHASE 6 — the in-flight execution, so a new utterance (or the app going to
     * the background) can cancel work that would otherwise land on a stale card.
     */
    private var executionJob: Job? = null

    /**
     * PHASE 6 — duplicate-suppression memory: the last executed command and when.
     *
     * ── WHY THIS EXISTS ──────────────────────────────────────────────────────
     * `SpeechRecognizer` can deliver the same final transcript twice (a repeated
     * callback, a user tapping twice, a re-delivered result after a pause). Two
     * "volume up" executions for one spoken word is a visible bug. Identical
     * commands inside [DUPLICATE_WINDOW_MILLIS] are therefore ignored — *after*
     * the first one has run — and the card stays as it is.
     *
     * Deliberately narrow: only the *same command value* is suppressed, so
     * "volume up, volume up" said twice quickly does nothing the second time,
     * while "volume up" then "volume down" both run.
     */
    private var lastCommand: AssistantCommand? = null
    private var lastCommandAtMillis: Long = 0L

    /**
     * PHASE 6.5 (D7) — the utterance currently being spoken, if any.
     *
     * Used only to decide barge-in: when the user starts a new utterance, whatever
     * ARIA is saying is stopped.
     */
    private var currentUtteranceId: String? = null

    /**
     * PHASE 6.5 (D7) — the utterance whose completion should settle the card.
     *
     * Events carrying any other id are ignored, so a late `SpeakFinished` from a
     * superseded reply can no longer flip the status of a newer result.
     */
    private var awaitingUtteranceId: String? = null

    /**
     * How many *acknowledgements* have been spoken; drives the rotating reply.
     *
     * Deliberately not incremented by [speak]: a command result is not an
     * acknowledgement, and counting it would skip phrases in the rotation.
     */
    private var spokenTurns = 0

    /**
     * PHASE 12 — serialises every touch of [historyStore].
     *
     * WHY A MUTEX AND NOT JUST "LAUNCH IT": every read-modify-write of the store
     * runs inside this mutex, in one coroutine at a time, so two store accesses can
     * never interleave and a write can never observe a half-applied load.
     *
     * WHAT THE MUTEX ALONE DOES *NOT* DO (corrected in R1, and the reason this
     * comment is worded this way): it does not ORDER the startup load against a
     * save. A lock serialises accesses; it cannot make a coroutine that has not
     * asked for it yet run first. The original wording here claimed that it did —
     * "so the load always lands first" — and that was false: a command typed the
     * instant the app opens could write a one-row list over the stored history
     * before the load ever reached the lock. The ordering is now established
     * explicitly, by [persistHistory] joining [historyLoadJob] *before* taking this
     * mutex. The lock is still required, but for atomicity, not for ordering.
     */
    private val historyMutex = Mutex()

    /**
     * R1 (C1b) — bumped by every [clearHistory], read by every history write.
     *
     * WHY A COUNTER AND NOT job.cancel(): a load already suspended inside
     * `historyStore.load()` has *already read* the rows it is about to merge. If
     * the user clears the history during that suspension, cancelling the job is a
     * race — it might already be past the suspension point — and a cancel cannot
     * help a persist that was queued before the clear either. A monotonic counter
     * is decided at the moment the write is about to happen: if a clear occurred
     * since the request was made, the request is stale and is dropped. Simple,
     * deterministic, and it covers BOTH the load merge and an in-flight save (the
     * case where a clear is followed by an older snapshot being written back).
     */
    private val historyGeneration = AtomicInteger(0)

    /**
     * PHASE 12 — completes when the initial load has been applied to the state.
     *
     * Kept so a clear during startup can wait for the load instead of racing it:
     * without this, "clear" could finish first and then be overwritten by the
     * load's records reappearing.
     */
    private var historyLoadJob: Job? = null

    init {
        viewModelScope.launch {
            speechRecognizerManager.events.collect(::onSpeechEvent)
        }
        viewModelScope.launch {
            textToSpeechManager.events.collect(::onTtsEvent)
        }
        // PHASE 12: restore before the user can do anything meaningful. The rows
        // are PREPENDED to whatever is already in memory (usually nothing), so a
        // command that somehow completes first is not thrown away.
        // Captured BEFORE the job is launched, so a clear issued at any point
        // from here on is detected when the load returns.
        val loadGeneration = historyGeneration.get()
        historyLoadJob = viewModelScope.launch {
            // A store that throws must not take the app with it: history is a
            // convenience, and the ViewModel's contract is that a broken file
            // yields an empty history. [HistoryStore] documents that it does not
            // throw, so this is second-line defence for a buggy implementation —
            // and it is what makes "load failure → empty history, no crash" true
            // rather than merely intended.
            val stored = guardHistory { historyStore.load() } ?: emptyList()
            if (stored.isEmpty()) return@launch
            // R1 (C1b): the history was cleared while `load()` was suspended. The
            // rows in `stored` were read from a file that no longer exists, so
            // merging them would resurrect exactly what the user deleted. Discard
            // the whole result — including the id counter — and leave the empty
            // state the clear produced.
            if (historyGeneration.get() != loadGeneration) return@launch
            // Continue numbering after the restored rows, so a new record can
            // never collide with a stored id (the ids are only used for list
            // identity in Compose).
            //
            // R1 — SCOPE NOTE, STATED RATHER THAN HIDDEN: a command that completed
            // WHILE this load was in flight was already given an id from 1, so it
            // can carry the same id as a restored row. That is not caused by the
            // persistence fix and is unchanged by it — it is visible only when the
            // user commands ARIA before the first read returns. [HistoryList] does
            // not supply a `key()`, so Compose is affected at most as a list
            // identity, never as a crash; renumbering rows here would mean mutating
            // history in a coroutine that a clear() may have already invalidated.
            // Left as an observation for the audit rather than silently "fixed".
            nextId.updateAndGet { current -> maxOf(current, stored.maxOf { it.id } + 1) }
            _uiState.update { state ->
                state.copy(history = capHistory(state.history + stored))
            }
        }
    }

    /**
     * The single entry point for the microphone button.
     *
     * Three outcomes, all of them honest:
     *  - permission missing   → set [AssistantUiState.isAwaitingPermission]; the
     *                           screen shows the system dialog and calls
     *                           [onMicrophonePermissionResult],
     *  - permission granted   → start listening,
     *  - no speech service    → explain, do not crash, do not hang.
     */
    fun onMicrophoneTapped() {
        // While listening, the same button stops the session: pressing "stop" is
        // the natural gesture, and it gives the recogniser its final transcript.
        if (_uiState.value.status == AssistantStatus.LISTENING) {
            speechRecognizerManager.stopListening()
            return
        }

        if (!speechRecognizerManager.isRecognitionAvailable()) {
            _uiState.update {
                it.copy(
                    status = AssistantStatus.ERROR,
                    message = SpeechMessages.forError(SpeechError.SERVICE_UNAVAILABLE),
                    history = it.withFailureRow(SpeechError.SERVICE_UNAVAILABLE)
                )
            }
            persistHistory()
            return
        }

        if (!permissionChecker.hasMicrophonePermission()) {
            // Nothing to say yet: the system dialog asks the real question. The
            // "why" line is the only text shown while it is open.
            _uiState.update {
                it.copy(
                    isAwaitingPermission = true,
                    status = AssistantStatus.LISTENING,
                    message = null
                )
            }
            return
        }

        startListening()
    }

    /**
     * PHASE P1.6 — the debug text field's submit.
     *
     * ── WHY THIS IS ONE LINE ────────────────────────────────────────────────
     * Typed input must exercise the SAME code as speech, or it is worthless as a
     * testing affordance: a separate "text path" would let the parser pass in
     * tests while the real voice path differed. So this method does nothing
     * except label the origin and hand the string to [runCommand], the exact
     * function a final recogniser result calls.
     *
     * WHAT IT DELIBERATELY DOES NOT DO: no permission check, no
     * `startListening()`, no recogniser call. Typing must work on a device whose
     * microphone is denied or whose recognition service is missing — that is
     * half the point of the field.
     *
     * A blank string is a no-op rather than a FAIL row: a mis-tapped submit
     * button is not a failed command, and the debug list would fill with noise.
     */
    fun onSubmitTextInput(text: String) {
        if (text.isBlank()) return
        runCommand(text, InputSource.TEXT)
    }

    /**
     * Called by the screen once the system permission dialog is answered.
     *
     * @param granted whether `RECORD_AUDIO` is now held.
     * @param permanentlyDenied true when the user refused repeatedly (or chose
     *   "don't ask again"), so only the system settings can change the answer.
     */
    fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (granted) {
            _uiState.update { it.copy(isAwaitingPermission = false, isPermissionBlocked = false) }
            startListening()
        } else {
            _uiState.update {
                it.copy(
                    isAwaitingPermission = false,
                    isPermissionBlocked = permanentlyDenied,
                    status = AssistantStatus.ERROR,
                    message = if (permanentlyDenied) SpeechMessages.PERMISSION_BLOCKED
                    else SpeechMessages.PERMISSION_DENIED
                )
            }
        }
    }

    /** Opens the microphone. Callers must already hold the permission. */
    private fun startListening() {
        _uiState.update {
            it.copy(
                status = AssistantStatus.LISTENING,
                // PHASE P1.6: the current interaction is voice again. Without
                // this, a card left behind by a typed command would keep claiming
                // TEXT while the microphone is open.
                inputSource = InputSource.VOICE,
                rawSpeech = null,
                normalizedText = null,
                commandType = null,
                commandLabel = null,
                handler = null,
                message = null,
                isAwaitingPermission = false,
                isPermissionBlocked = false,
                // Phase 3: a new attempt starts with a clean slate; the old reply
                // must not linger under the new transcript.
                spokenReply = null,
                lastSpokenText = null,
                pendingSettingsAction = null,
                ttsMessage = if (_uiState.value.isTtsAvailable) null else _uiState.value.ttsMessage
            )
        }
        speechRecognizerManager.startListening()
    }

    /**
     * Single translation point from recogniser events to UI state.
     *
     * Partial results overwrite `rawSpeech` so the user watches their words
     * appear; the final result freezes it and writes one history entry.
     */
    private fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            SpeechEvent.ReadyForSpeech -> _uiState.update {
                it.copy(status = AssistantStatus.LISTENING, message = null)
            }

            is SpeechEvent.PartialResult -> _uiState.update {
                it.copy(status = AssistantStatus.LISTENING, rawSpeech = event.text)
            }

            SpeechEvent.EndOfSpeech -> _uiState.update {
                it.copy(status = AssistantStatus.PROCESSING)
            }

            is SpeechEvent.FinalResult -> onFinalResult(event.text)

            is SpeechEvent.Failure -> onFailure(event.error)
        }
    }

    /**
     * PHASE 6 — the whole pipeline for one utterance.
     *
     * Order matters and is deliberate:
     *  1. parse on [Dispatchers.Default] (pure CPU, must not block Main),
     *  2. honour duplicate suppression *before* any side effect,
     *  3. acknowledge out loud immediately (the user hears "I heard you" while
     *     the action runs — silence during a system call reads as a hang),
     *  4. execute on [Dispatchers.IO], guarded by cancellation and a catch,
     *  5. write the *real* result: status, message, history row, spoken reply.
     */
    private fun onFinalResult(text: String) {
        if (text.isBlank()) {
            // The recogniser reported success but produced nothing usable.
            onFailure(SpeechError.NO_MATCH)
            return
        }
        runCommand(text, InputSource.VOICE)
    }

    /**
     * PHASE P1.6 — THE one pipeline, shared by voice and debug text.
     *
     * Everything that makes a command correct lives here and only here: parse off
     * the main thread, duplicate suppression before any side effect, barge-in on
     * TTS, the cancellation guard, the acknowledge-then-execute-then-publish
     * order. [onFinalResult] and [onSubmitTextInput] are both just callers, which
     * is what guarantees typed input cannot drift from speech.
     *
     * [source] is carried for attribution only; it changes no behaviour. That is
     * deliberate — a debug affordance must not alter the thing it is testing.
     */
    private fun runCommand(text: String, source: InputSource) {

        // A new utterance supersedes any execution still in flight. Cancelling is
        // correct rather than rude: a result computed for the *previous* sentence
        // must never be presented as the answer to this one.
        executionJob?.cancel()

        // PHASE 6.5 (D1): the card is NOT reset to PROCESSING here. Parsing and
        // the duplicate check run first, so a suppressed repeat can never be left
        // showing "Processing…" — the defect that made D1 necessary.
        executionJob = viewModelScope.launch {
            val command = withContext(computeDispatcher) { commandParser.parse(text) }

            if (isDuplicate(command)) {
                // PHASE 6.5 (D1): nothing is executed twice, and the card must go
                // back to describing the FIRST attempt.
                //
                // WHY THE CARD IS REBUILT RATHER THAN LEFT ALONE: by the time this
                // runs, the recogniser has already driven the card through
                // LISTENING and PROCESSING for this repeat, and startListening()
                // cleared the visible fields. "Leave the previous card untouched"
                // therefore has to mean *restore* it, or the user is left looking
                // at "Processing…" — the exact defect D1 was meant to remove.
                // NOTE: called ON the state, not through copy() — copy()'s first
                // positional parameter is `status`, so `it.copy(x)` would read as
                // a status and does not compile.
                _uiState.update { state -> state.suppressedDuplicateState() }
                return@launch
            }
            remember(command)
            val commandType = commandTypeFor(command)

            // Only now, with real work to do, does the card start over.
            _uiState.update {
                it.copy(
                    status = AssistantStatus.PROCESSING,
                    inputSource = source,
                    rawSpeech = text,
                    normalizedText = text.lowercase().trim(),
                    commandType = null,
                    commandLabel = null,
                    handler = null,
                    message = null,
                    spokenReply = null,
                    lastSpokenText = null,
                    pendingSettingsAction = null,
                    pendingCommand = null,
                    pendingRawSpeech = null
                )
            }

            // PHASE 6.5 (D7): a new utterance is the one case where barge-in is
            // wanted — if ARIA is mid-sentence, the user talking must silence it.
            currentUtteranceId?.let { textToSpeechManager.stop() }
            currentUtteranceId = null
            awaitingUtteranceId = null

            // Acknowledge now: the microphone is closed, so speech output no
            // longer overlaps the user's voice.
            acknowledgeOutLoud()

            val result = executeSafely(command)

            publish(text, command, commandType, result, source)
        }
    }

    /**
     * Runs the executor, converting anything it throws into an honest result.
     *
     * WHY NOT LET [ActionExecutor]'s own guarantee suffice: the interface promises
     * a total function, but this is a boundary into Android system services on a
     * background thread — a `SecurityException` from a vendor `AudioManager` must
     * not take the app down. [CancellationException] is rethrown so coroutine
     * cancellation keeps working.
     */
    private suspend fun executeSafely(command: AssistantCommand): ExecutionResult =
        try {
            withContext(ioDispatcher) { actionExecutor.execute(command) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RuntimeException) {
            ExecutionResult.Failure(ActionMessages.UNEXPECTED)
        }

    /**
     * Writes the outcome of [command] into the state and speaks it.
     *
     * The record's [CommandRecord.status] is mapped from the *result type*, not
     * from optimism: only a verified [ExecutionResult.Success] is `SUCCESS`; an
     * unrecognised sentence keeps `SUCCESS` too (nothing failed — see
     * [ExecutionResult.NotUnderstood]) while a real failure is `ERROR`.
     */
    private fun publish(
        text: String,
        command: AssistantCommand,
        commandType: String,
        result: ExecutionResult,
        source: InputSource
    ) {
        val status = statusFor(result)
        val record = CommandRecord(
            id = nextId.getAndIncrement(),
            timestampMillis = clockMillis(),
            rawSpeech = text,
            normalizedText = text.lowercase().trim(),
            commandType = commandType,
            handler = HandlerNames.forCommand(command),
            status = status,
            message = result.message,
            source = source
        )

        _uiState.update {
            it.copy(
                status = status,
                inputSource = source,
                rawSpeech = record.rawSpeech,
                normalizedText = record.normalizedText,
                commandType = record.commandType,
                commandLabel = command.displayName,
                handler = record.handler,
                message = record.message,
                history = capHistory(listOf(record) + it.history),
                // PHASE 6: a missing permission is carried to the card as a
                // *hint*, never acted on: ARIA does not open settings by itself.
                pendingSettingsAction = (result as? ExecutionResult.RequiresPermission)
                    ?.settingsIntentAction,
                // PHASE 6.5 (D2): remember WHAT was refused so the grant can be
                // acted on without asking the user to repeat themselves.
                pendingCommand = if (result is ExecutionResult.RequiresPermission) command else null,
                pendingRawSpeech = if (result is ExecutionResult.RequiresPermission) text else null,
                isAwaitingPermission = false
            )
        }

        // PHASE 6.5 (D1): snapshot the card *before* speaking, so a later
        // suppressed duplicate can put the user back on this settled result
        // instead of the LISTENING/PROCESSING state its own recognition caused.
        rememberSettledState(_uiState.value)

        // PHASE 12: persist AFTER the state update, and persist the state's own
        // list rather than "record + old list" — there is then exactly one source
        // of truth for what is on disk, and the cap is applied in one place.
        //
        // D1 is untouched by construction: a suppressed duplicate returns before
        // publish(), so it can never create a second row here.
        persistHistory()

        // The result sentence is what gets spoken: the user asked for something,
        // so being told what actually happened is the useful reply. The rotating
        // "I heard you" acknowledgements stay as the pre-execution sound.
        speak(result.message)
    }

    /**
     * Maps an outcome to the traffic-light the card shows.
     *
     * `NotUnderstood` is deliberately **SUCCESS**: nothing failed, nothing was
     * attempted, and the sentence explains itself. `NotSupported` is **ERROR**
     * because the user asked for something the device cannot do and needs to see
     * that it did not happen.
     */
    private fun statusFor(result: ExecutionResult): AssistantStatus = when (result) {
        is ExecutionResult.Success -> AssistantStatus.SUCCESS
        is ExecutionResult.NotUnderstood -> AssistantStatus.SUCCESS
        // PHASE 6.5: a deliberate refusal is neither a success nor a failure.
        is ExecutionResult.Rejected -> AssistantStatus.REJECTED
        is ExecutionResult.Failure -> AssistantStatus.ERROR
        is ExecutionResult.NotSupported -> AssistantStatus.ERROR
        is ExecutionResult.RequiresPermission -> AssistantStatus.ERROR
    }

    /**
     * The stable history/debug identifier for [command].
     *
     * WHY NOT `displayName`: the display name is prose meant for humans ("Set
     * Volume to 40%"). History rows and later phases (persistence, filters) need
     * something stable and greppable, which is what the uppercase simple name
     * gives — and it keeps the Phase 2..5 field meaningful now that it is finally
     * populated.
     */
    private fun commandTypeFor(command: AssistantCommand): String =
        CommandTypeNaming.of(command)

    /** PHASE 6.5 (D1) — the settled card of the last executed command. */
    private var settledState: AssistantUiState? = null

    /** PHASE 6.5 (D1) — records the card as it was after [publish]. */
    private fun rememberSettledState(state: AssistantUiState) {
        settledState = state
    }

    /**
     * PHASE 6.5 (D1) — the card a suppressed duplicate must show.
     *
     * Copies the visible fields of the last settled card — everything except the
     * live speaking flags, which the recognition pass just changed and which must
     * not be resurrected. When there is no snapshot (defensive: a duplicate can
     * only exist after a publish) the current state is returned unchanged.
     */
    private fun AssistantUiState.suppressedDuplicateState(): AssistantUiState {
        val settled = settledState ?: return this
        return copy(
            status = settled.status,
            inputSource = settled.inputSource,
            rawSpeech = settled.rawSpeech,
            normalizedText = settled.normalizedText,
            commandType = settled.commandType,
            commandLabel = settled.commandLabel,
            handler = settled.handler,
            message = settled.message,
            spokenReply = settled.spokenReply,
            lastSpokenText = settled.lastSpokenText,
            pendingSettingsAction = settled.pendingSettingsAction,
            pendingCommand = settled.pendingCommand,
            pendingRawSpeech = settled.pendingRawSpeech
        )
    }

    /** True when [command] is identical to the one executed moments ago. */
    private fun isDuplicate(command: AssistantCommand): Boolean {
        val previous = lastCommand ?: return false
        val elapsed = clockMillis() - lastCommandAtMillis
        return previous == command && elapsed in 0..DUPLICATE_WINDOW_MILLIS
    }

    private fun remember(command: AssistantCommand) {
        lastCommand = command
        lastCommandAtMillis = clockMillis()
    }

    /**
     * PHASE 6 — speaks one sentence (a command result) through the same rules as
     * [acknowledgeOutLoud]: mute wins, a dead engine still shows the text.
     *
     * WHY A SECOND METHOD RATHER THAN GENERALISING THE FIRST: the acknowledgement
     * rotates through a fixed list and counts turns; a result is a fixed sentence
     * that must never be counted or rotated. Folding them together would mean a
     * boolean flag deciding which half runs — two clear methods are better.
     */
    private fun speak(reply: String) {
        val state = _uiState.value

        if (!state.ttsEnabled) {
            _uiState.update { it.copy(lastSpokenText = null, spokenReply = reply) }
            return
        }

        if (!state.isTtsAvailable) {
            _uiState.update {
                it.copy(
                    spokenReply = reply,
                    ttsMessage = TtsMessages.forError(TtsError.ENGINE_INIT_FAILED)
                )
            }
            return
        }

        // PHASE 6.5 (D7): APPEND, so the result sentence queues behind the
        // acknowledgement instead of cutting it off mid-word.
        val utteranceId = textToSpeechManager.speak(reply, SpeakPriority.APPEND)

        if (utteranceId != null) {
            // NOTE: [spokenTurns] is deliberately NOT incremented here. It exists
            // to rotate the fixed acknowledgement list; a result sentence is not
            // an acknowledgement, and counting it would make every second turn
            // skip a phrase.
            currentUtteranceId = utteranceId
            awaitingUtteranceId = utteranceId
            _uiState.update {
                it.copy(
                    // A failed action, or a deliberate refusal, is spoken too, but
                    // the card keeps its own colour: SPEAKING would repaint a
                    // failure or a rejection as a neutral success.
                    status = if (it.status == AssistantStatus.ERROR ||
                        it.status == AssistantStatus.REJECTED
                    ) {
                        it.status
                    } else {
                        AssistantStatus.SPEAKING
                    },
                    lastSpokenText = reply,
                    spokenReply = reply
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    spokenReply = reply,
                    ttsMessage = TtsMessages.forError(TtsError.SPEAK_FAILED)
                )
            }
        }
    }

    /**
     * PHASE 3 — the speaking half of the loop.
     *
     * Three rules, in order:
     *  1. the user's mute wins over everything (no sound, no state change),
     *  2. an unavailable engine is not an error here: the reply is still written
     *     to the card as text, so nothing is lost,
     *  3. otherwise speak a short acknowledgement and let [onTtsEvent] decide
     *     when to leave the SPEAKING state.
     *
     * The pipeline stops here by design: Phase 5 replaces the *text* with a real
     * command result, and this method keeps working unchanged.
     */
    private fun acknowledgeOutLoud() {
        val state = _uiState.value
        val reply = TtsAcknowledgements.forTurn(spokenTurns)

        if (!state.ttsEnabled) {
            // Muted: still show what ARIA would have said, so the user can tell
            // the difference between "quiet" and "broken".
            _uiState.update {
                it.copy(lastSpokenText = null, spokenReply = reply)
            }
            return
        }

        if (!state.isTtsAvailable) {
            // The engine is dead. Still show the reply, and keep saying why.
            _uiState.update {
                it.copy(
                    spokenReply = reply,
                    ttsMessage = TtsMessages.forError(TtsError.ENGINE_INIT_FAILED)
                )
            }
            return
        }

        val utteranceId = textToSpeechManager.speak(reply, SpeakPriority.REPLACE)
        if (utteranceId != null) {
            spokenTurns++
            currentUtteranceId = utteranceId
            _uiState.update {
                it.copy(
                    status = AssistantStatus.SPEAKING,
                    lastSpokenText = reply,
                    spokenReply = reply
                )
            }
        } else {
            // speak() refused: the engine is degraded but the app carries on.
            _uiState.update {
                it.copy(
                    spokenReply = reply,
                    ttsMessage = TtsMessages.forError(TtsError.SPEAK_FAILED)
                )
            }
        }
    }

    /**
     * PHASE 3 — single translation point from engine events to UI state.
     *
     * Deliberately narrow: audio events only ever touch the speaking fields, so
     * a late or duplicated callback can never rewrite a finished result card.
     */
    private fun onTtsEvent(event: TtsEvent) {
        when (event) {
            TtsEvent.Ready -> _uiState.update {
                it.copy(isTtsAvailable = true, ttsMessage = null)
            }

            is TtsEvent.Unavailable -> _uiState.update { state ->
                state.copy(
                    isTtsAvailable = false,
                    ttsMessage = TtsMessages.forError(event.error),
                    // A dead engine must not leave the screen stuck on SPEAKING.
                    status = if (state.status == AssistantStatus.SPEAKING) {
                        AssistantStatus.SUCCESS
                    } else {
                        state.status
                    }
                )
            }

            // PHASE 6.5 (D7): every utterance event is matched against the one
            // being awaited. A late callback from a superseded reply is dropped
            // instead of repainting the current card.
            is TtsEvent.SpeakStarted -> _uiState.update {
                if (it.lastSpokenText == null || isSettled(it.status)) it
                else it.copy(status = AssistantStatus.SPEAKING)
            }

            is TtsEvent.SpeakFinished -> {
                // Accept the finish only for the utterance we are waiting on.
                val isOurs = isCurrentUtterance(event.utteranceId)
                if (isOurs) awaitingUtteranceId = null
                _uiState.update { state ->
                    if (!isOurs) {
                        state
                    } else if (state.status == AssistantStatus.SPEAKING) {
                        // Settle to the colour the RESULT deserves, not to green:
                        // a spoken failure must never end up looking successful.
                        state.copy(status = settledStatusFor(state))
                    } else {
                        state
                    }
                }
            }

            is TtsEvent.SpeakFailed -> {
                val isOurs = isCurrentUtterance(event.utteranceId)
                if (isOurs) awaitingUtteranceId = null
                _uiState.update { state ->
                    // R1 (C2a): mirror SpeakFinished. A failure belonging to a
                    // superseded utterance must leave the CURRENT card alone — the
                    // id was computed above but this branch used to update the
                    // state regardless, so a late failure from an old reply
                    // repainted the new card with a stale "could not speak"
                    // warning. A non-matching event touches neither the status
                    // nor ttsMessage.
                    if (!isOurs) {
                        state
                    } else {
                        state.copy(
                            status = if (state.status == AssistantStatus.SPEAKING) {
                                settledStatusFor(state)
                            } else {
                                state.status
                            },
                            ttsMessage = TtsMessages.forError(TtsError.SPEAK_FAILED)
                        )
                    }
                }
            }
        }
    }

    /**
     * PHASE 6.5 (D7) / R1 (C2b) — true when [utteranceId] is the utterance being
     * awaited.
     *
     * A null id (the engine stopped everything, or a fake without ids) is
     * accepted: those events are about "audio stopped", not about one utterance,
     * and ignoring them would leave the card stuck on SPEAKING.
     *
     * DELIBERATELY PERMISSIVE, AND LEFT THAT WAY ON PURPOSE. The obvious
     * "tightening" — `utteranceId != null && utteranceId == awaitingUtteranceId` —
     * was evaluated and rejected, because the id-less event is not noise, it is
     * the engine's *intentional* stop:
     *
     *  - [TextToSpeechManager.stop] emits `SpeakFinished()` with NO id (line 216).
     *    That is the event a barge-in or a trip to the background produces, and
     *    something has to settle the card when it arrives.
     *  - `onAppBackgrounded()` relies on exactly that: it stops the engine and then
     *    clears both ids, and the id-less event must still be honoured.
     *  - A silent engine failure reported without an id still has to reach the
     *    user; the "speak failure" tests assert that an id-less `SpeakFailed`
     *    produces the warning.
     *
     * Making matching strict would therefore require resetting the state
     * synchronously at every `stop()` call site to replace what the id-less event
     * currently does — a behavioural change to barge-in, which this round is
     * explicitly forbidden from making.
     *
     * WHAT WAS ACTUALLY FIXED INSTEAD (C2a): a *non-matching, id-bearing* event is
     * now ignored by BOTH handlers. That was the real defect — an old utterance
     * carrying an identifiable id was repainting the current card — and it is
     * fixed without touching the id-less contract.
     *
     * NOTE ON THE `awaitingUtteranceId == null` TERM: when nothing is awaited the
     * ViewModel is not filtering anything, so an event is admitted. Tightening
     * this term alone would strand the card whenever an id-less stop arrived
     * after the ids had been cleared.
     */
    private fun isCurrentUtterance(utteranceId: String?): Boolean =
        utteranceId == null || awaitingUtteranceId == null || utteranceId == awaitingUtteranceId

    /**
     * PHASE 6.5 (D7) — a status that must survive being spoken.
     *
     * `ERROR` and `REJECTED` describe the *result*, not the audio, so speech must
     * never repaint them. This is the fix for the Phase 6 defect where a spoken
     * failure ended up green.
     */
    private fun isSettled(status: AssistantStatus): Boolean =
        status == AssistantStatus.ERROR || status == AssistantStatus.REJECTED

    /**
     * PHASE 6.5 (D7) — the colour to settle on when audio for the current result
     * ends.
     *
     * Derived from the newest history row, which already records the *result's*
     * own status: that keeps the card telling one story instead of inventing a
     * second source of truth for "did it work?".
     */
    private fun settledStatusFor(state: AssistantUiState): AssistantStatus =
        when (state.history.firstOrNull()?.status) {
            AssistantStatus.ERROR -> AssistantStatus.ERROR
            AssistantStatus.REJECTED -> AssistantStatus.REJECTED
            else -> AssistantStatus.SUCCESS
        }

    /**
     * PHASE 6.5 (D2) — called by the UI when the app returns to the foreground.
     *
     * The UI cannot know whether the user granted the permission while they were
     * in Settings, so it simply reports "we are back". This method asks the
     * injected port again and:
     *
     *  - if the permission is now granted AND a command was waiting on it, the
     *    ORIGINAL command is retried (the user should not have to repeat
     *    themselves), and the pending hint is cleared;
     *  - if it is still missing, nothing changes: the hint and the pending command
     *    stay, so the button remains available.
     *
     * WHY THE PORT AND NOT A DIRECT CHECK: the ViewModel must stay Android-free;
     * [PolicyAccessChecker] is the seam that keeps this testable on the JVM.
     *
     * WHY NOT CLEAR ON LAUNCH: ARIA must not assume a trip to Settings succeeded.
     * Returning from Settings is not evidence of a grant — only the port is.
     */
    fun onReturnedFromSettings() {
        val state = _uiState.value
        if (state.pendingSettingsAction == null) return
        if (!policyAccessChecker.canChangeSoundModes()) {
            // Still denied: keep the hint so the button stays visible.
            return
        }

        val command = state.pendingCommand
        val spokenText = state.pendingRawSpeech ?: state.rawSpeech ?: ""
        // PHASE P1.6: the retry keeps the ORIGIN of the refused attempt, so a
        // typed command that needed a permission does not come back as VOICE.
        val source = state.inputSource

        _uiState.update {
            it.copy(
                pendingSettingsAction = null,
                pendingCommand = null,
                pendingRawSpeech = null
            )
        }

        if (command == null) return

        // Retry exactly what was understood, not a re-parse of the transcript.
        // If the device still refuses, publish() re-arms the hint, so the button
        // reappears rather than silently disappearing.
        executionJob?.cancel()
        executionJob = viewModelScope.launch {
            remember(command)
            acknowledgeOutLoud()
            val result = executeSafely(command)
            publish(spokenText, command, commandTypeFor(command), result, source)
        }
    }

    /**
     * PHASE 3 — the mute switch.
     *
     * Turning speech off mid-sentence stops the audio at once: mute must be
     * immediate, not "after this sentence".
     */
    fun onTtsEnabledChanged(enabled: Boolean) {
        if (!enabled) textToSpeechManager.stop()
        _uiState.update {
            it.copy(
                ttsEnabled = enabled,
                status = if (!enabled && it.status == AssistantStatus.SPEAKING) {
                    AssistantStatus.SUCCESS
                } else {
                    it.status
                }
            )
        }
    }

    private fun onFailure(error: SpeechError) {
        // Cancellations never reach this point: the manager suppresses the
        // platform's "client error" that always follows cancel(), so the card is
        // only ever shown for a real problem.
        _uiState.update {
            it.copy(
                status = AssistantStatus.ERROR,
                rawSpeech = null,
                normalizedText = null,
                commandType = NO_COMMAND_YET,
                commandLabel = null,
                handler = NO_HANDLER_YET,
                message = SpeechMessages.forError(error),
                history = it.withFailureRow(error),
                isAwaitingPermission = false
            )
        }
        // PHASE 12: a failure row is part of the history, so it survives a
        // restart like any other. Requirement 6 forbids changing what the
        // in-memory history contains; persisting exactly that is the point.
        persistHistory()
    }

    /**
     * Prepends a FAIL history row for an attempt that produced no transcript.
     *
     * PHASE P1.6: the row is VOICE, unconditionally, because EVERY caller of this
     * is a speech failure (no match, no speech service, a recogniser error) — the
     * failure events can only originate from the recogniser. The debug text path
     * cannot reach it: blank typed input is a no-op before the pipeline starts,
     * and a non-blank one always produces a real command.
     *
     * WHY NOT READ THE CARD'S SOURCE: an earlier version did, and it was wrong —
     * after a typed command the card still said TEXT, so the next *spoken*
     * failure was recorded against the wrong input.
     */
    private fun AssistantUiState.withFailureRow(error: SpeechError): List<CommandRecord> =
        listOf(
            CommandRecord(
                id = nextId.getAndIncrement(),
                timestampMillis = System.currentTimeMillis(),
                rawSpeech = SpeechMessages.NO_SPEECH_CAPTURED,
                commandType = NO_COMMAND_YET,
                handler = NO_HANDLER_YET,
                status = AssistantStatus.ERROR,
                message = SpeechMessages.forError(error),
                source = InputSource.VOICE
            )
        ) + history

    /** Debug mode is a developer aid; it is persisted in memory only for now. */
    fun onDebugModeChanged(enabled: Boolean) {
        _uiState.update { it.copy(debugMode = enabled) }
    }

    /** Clears the last result card but keeps the history. */
    fun dismissResult() {
        _uiState.update {
            it.copy(
                status = AssistantStatus.IDLE,
                rawSpeech = null,
                normalizedText = null,
                commandType = null,
                commandLabel = null,
                handler = null,
                message = null,
                spokenReply = null,
                lastSpokenText = null,
                pendingSettingsAction = null
            )
        }
    }

    fun clearHistory() {
        // PHASE 6.5 (D1): the snapshot must go with the history, or a duplicate
        // spoken just after a clear would resurrect the card that was cleared.
        settledState = null
        // R1 (C1b): invalidate every history write requested before this point.
        // A load already suspended in `load()`, or a save already queued, belongs
        // to the history the user just deleted; without this they would write it
        // back and the records would return after a restart.
        historyGeneration.incrementAndGet()
        _uiState.update { it.copy(history = emptyList(), isAwaitingPermission = false) }

        // PHASE 12: the store is cleared too, inside the same mutex the load and
        // every save use. The load job is joined FIRST: a clear that ran before
        // the initial load returned would otherwise be undone by the load
        // prepending the old rows back into the state.
        viewModelScope.launch {
            // Wait for the initial load, then empty the state AGAIN. Clearing only
            // before the join is not enough: the load's own step prepends the
            // stored rows into the state, so a clear that ran while the load was
            // in flight would be undone by it moments later — the rows would
            // disappear from the file and reappear on screen. Emptying after the
            // join makes the outcome independent of the race.
            historyLoadJob?.join()
            _uiState.update { it.copy(history = emptyList()) }
            guardHistory { historyStore.clear() }
        }
    }

    /**
     * PHASE 12 — runs one store call, swallowing anything it throws.
     *
     * WHY THIS IS NOT PARANOIA: [HistoryStore]'s own contract says it does not
     * throw, but this is the boundary between the app and the filesystem. A
     * read-only volume, a full disk or a bug in an implementation must cost the
     * user their history, never their command. [CancellationException] is rethrown
     * so coroutine cancellation keeps working — swallowing it would leave work
     * running after its scope died.
     *
     * The mutex is taken HERE rather than at each call site, so no store access
     * can escape the serialisation that keeps the initial load and an append from
     * interleaving.
     */
    private suspend fun <T> guardHistory(block: suspend () -> T): T? =
        try {
            historyMutex.withLock { block() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Deliberately broad: a storage failure of ANY kind is non-fatal here.
            null
        }

    /**
     * R1 (C1) — writes the current history to [historyStore].
     *
     * WHAT IS WRITTEN: the state's own list, capped, read while the store lock is
     * held. Not "the new row plus a stale copy" — that duplication is how a store
     * drifts from the UI — and not a list captured earlier, for the same reason.
     *
     * WHAT IT WAITS FOR: the initial load. It joins [historyLoadJob] before taking
     * the mutex, so a command run immediately after a cold start persists the
     * restored rows along with the new one. A previous version of this comment
     * claimed the mutex alone made that safe ("the save simply queues behind it…
     * the merge is preserved whichever one wins"). That was wrong: the load is a
     * separate coroutine, nothing ordered it before the save, and a save that ran
     * first wrote a list without the stored rows — losing them on disk. The join
     * is what makes the ordering real; the mutex only makes each store access
     * atomic.
     *
     * WHY THE JOIN CANNOT DEADLOCK: the mutex is taken by [guardHistory], i.e.
     * strictly after the join returns. Holding it while joining would have the
     * save waiting on the load and the load waiting on the lock.
     *
     * WHY IT STILL DOES NOT BLOCK THE CALLER: the join happens inside the
     * launched coroutine, so the command pipeline returns to the UI immediately.
     * A slow disk never delays the result card or the spoken reply.
     */
    private fun persistHistory() {
        // R1 (C1b): the generation is captured with the request, not with the
        // write, so a clear that lands while this save is still queued can be
        // detected and the stale write dropped instead of resurrecting the rows.
        val generation = historyGeneration.get()
        viewModelScope.launch {
            // R1 (C1) — WAIT FOR THE STARTUP LOAD FIRST, AND BEFORE THE MUTEX.
            //
            // This is the fix for the cold-start data loss: the load runs in its
            // own coroutine, so without this join a command executed immediately
            // after launch saved `[new]` over a file that still held the older
            // rows, and the load's merge then happened only in memory.
            //
            // WHY THE JOIN SITS OUTSIDE guardHistory: guardHistory is what takes
            // historyMutex, and the load needs that same mutex to apply its
            // result. Joining while holding it would wait for a job that is
            // waiting for the lock — a deadlock. Join first, lock second.
            historyLoadJob?.join()
            if (historyGeneration.get() != generation) return@launch
            guardHistory {
                // THE SNAPSHOT IS READ *INSIDE* THE MUTEX. Reading it before the
                // launch (as this used to) let two concurrent saves capture
                // overlapping lists and let the second overwrite the first with a
                // snapshot that predated it — a lost update. Under the lock, each
                // save writes the state as it is at the moment of writing.
                historyStore.save(_uiState.value.history)
            }
        }
    }

    /**
     * Called when the app leaves the foreground. Stops the microphone so ARIA
     * never records in the background — a privacy rule, not a nicety.
     */
    fun onAppBackgrounded() {
        // PHASE 6: an execution started for a sentence the user has now left must
        // not finish and pop a result card over a different screen.
        executionJob?.cancel()
        speechRecognizerManager.cancel()
        // Phase 3: a leaked utterance would talk over whatever the user opened
        // next. Silence it for the same reason the microphone is closed.
        textToSpeechManager.stop()
        // Phase 6.5 (D7): stop() emits an id-less SpeakFinished, but the ids must
        // not survive a trip to the background — a later, unrelated utterance
        // would otherwise be matched against one that no longer exists.
        currentUtteranceId = null
        awaitingUtteranceId = null
        _uiState.update {
            if (it.status == AssistantStatus.LISTENING ||
                it.status == AssistantStatus.PROCESSING ||
                it.status == AssistantStatus.SPEAKING
            ) {
                it.copy(status = AssistantStatus.IDLE, isAwaitingPermission = false)
            } else {
                it.copy(isAwaitingPermission = false)
            }
        }
    }

    override fun onCleared() {
        // The recogniser is per-ViewModel; the TTS engine lives in the
        // Application, so it is only silenced here and shut down there.
        executionJob?.cancel()
        speechRecognizerManager.release()
        textToSpeechManager.stop()
        super.onCleared()
    }

    companion object {
        /**
         * Command type used for rows that never reached the parser (a failed
         * recognition). Since Phase 6 every successfully parsed command records
         * its own type, so this is only the "no transcript" marker.
         */
        const val NO_COMMAND_YET = "NO_COMMAND_YET"

        /** Handler column for an attempt that never reached a handler. */
        const val NO_HANDLER_YET = "—"

        /**
         * PHASE 6 — how long an identical command is treated as a repeat.
         *
         * One second, per the Phase 6 specification. Long enough to absorb a
         * duplicated recogniser callback (milliseconds) or a double tap, short
         * enough that a user deliberately repeating "volume up" still works.
         */
        const val DUPLICATE_WINDOW_MILLIS = 1_000L
    }
}
