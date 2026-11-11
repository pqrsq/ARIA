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
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import com.example.aria.voice.SpeechMessages
import com.example.aria.voice.SpeechRecognizerManager
import com.example.aria.voice.TextToSpeechManager
import com.example.aria.voice.TtsAcknowledgements
import com.example.aria.voice.TtsError
import com.example.aria.voice.TtsEvent
import com.example.aria.voice.TtsMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * The last three constructor parameters exist only so a JVM test can be
 * deterministic, and all three have production defaults that callers never
 * override:
 *  - `computeDispatcher` — where the pure parser runs,
 *  - `ioDispatcher` — where the executor runs (system services, `startActivity`),
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
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
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
     * How many *acknowledgements* have been spoken; drives the rotating reply.
     *
     * Deliberately not incremented by [speak]: a command result is not an
     * acknowledgement, and counting it would skip phrases in the rotation.
     */
    private var spokenTurns = 0

    init {
        viewModelScope.launch {
            speechRecognizerManager.events.collect(::onSpeechEvent)
        }
        viewModelScope.launch {
            textToSpeechManager.events.collect(::onTtsEvent)
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

        // A new utterance supersedes any execution still in flight. Cancelling is
        // correct rather than rude: a result computed for the *previous* sentence
        // must never be presented as the answer to this one.
        executionJob?.cancel()

        _uiState.update {
            it.copy(
                status = AssistantStatus.PROCESSING,
                rawSpeech = text,
                normalizedText = text.lowercase().trim(),
                commandType = null,
                commandLabel = null,
                handler = null,
                message = null,
                spokenReply = null,
                lastSpokenText = null
            )
        }

        executionJob = viewModelScope.launch {
            val command = withContext(computeDispatcher) { commandParser.parse(text) }

            if (isDuplicate(command)) {
                // Nothing is executed twice; the existing card already describes
                // the first, identical attempt.
                return@launch
            }
            remember(command)
            val commandType = commandTypeFor(command)

            // Acknowledge now: the microphone is closed, so speech output no
            // longer overlaps the user's voice.
            acknowledgeOutLoud()

            val result = executeSafely(command)

            publish(text, command, commandType, result)
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
        result: ExecutionResult
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
            message = result.message
        )

        _uiState.update {
            it.copy(
                status = status,
                rawSpeech = record.rawSpeech,
                normalizedText = record.normalizedText,
                commandType = record.commandType,
                commandLabel = command.displayName,
                handler = record.handler,
                message = record.message,
                history = listOf(record) + it.history,
                // PHASE 6: a missing permission is carried to the card as a
                // *hint*, never acted on: ARIA does not open settings by itself.
                pendingSettingsAction = (result as? ExecutionResult.RequiresPermission)
                    ?.settingsIntentAction,
                isAwaitingPermission = false
            )
        }

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

        if (textToSpeechManager.speak(reply)) {
            // NOTE: [spokenTurns] is deliberately NOT incremented here. It exists
            // to rotate the fixed acknowledgement list; a result sentence is not
            // an acknowledgement, and counting it would make every second turn
            // skip a phrase.
            _uiState.update {
                it.copy(
                    // A failed command is spoken too, but the card keeps its
                    // ERROR colour: SPEAKING would repaint a failure as neutral.
                    status = if (it.status == AssistantStatus.ERROR) {
                        AssistantStatus.ERROR
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

        if (textToSpeechManager.speak(reply)) {
            spokenTurns++
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

            TtsEvent.SpeakStarted -> _uiState.update {
                if (it.lastSpokenText == null) it
                else it.copy(status = AssistantStatus.SPEAKING)
            }

            TtsEvent.SpeakFinished -> _uiState.update {
                if (it.status == AssistantStatus.SPEAKING) {
                    it.copy(status = AssistantStatus.SUCCESS)
                } else {
                    it
                }
            }

            TtsEvent.SpeakFailed -> _uiState.update {
                it.copy(
                    status = if (it.status == AssistantStatus.SPEAKING) {
                        AssistantStatus.SUCCESS
                    } else {
                        it.status
                    },
                    ttsMessage = TtsMessages.forError(TtsError.SPEAK_FAILED)
                )
            }
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
    }

    /** Prepends a FAIL history row for an attempt that produced no transcript. */
    private fun AssistantUiState.withFailureRow(error: SpeechError): List<CommandRecord> =
        listOf(
            CommandRecord(
                id = nextId.getAndIncrement(),
                timestampMillis = System.currentTimeMillis(),
                rawSpeech = SpeechMessages.NO_SPEECH_CAPTURED,
                commandType = NO_COMMAND_YET,
                handler = NO_HANDLER_YET,
                status = AssistantStatus.ERROR,
                message = SpeechMessages.forError(error)
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
        _uiState.update { it.copy(history = emptyList(), isAwaitingPermission = false) }
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
