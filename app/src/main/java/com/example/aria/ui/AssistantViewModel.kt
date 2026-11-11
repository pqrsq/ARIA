package com.example.aria.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import com.example.aria.voice.SpeechMessages
import com.example.aria.voice.SpeechRecognizerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the screen state for the assistant.
 *
 * ── PHASE 2 SCOPE ────────────────────────────────────────────────────────────
 * Speech-to-text only. This ViewModel now:
 *   1. exposes an immutable [AssistantUiState] through a StateFlow,
 *   2. asks for the microphone permission at the moment of use,
 *   3. drives [SpeechRecognizerManager] and folds its [SpeechEvent]s into the
 *      very same state fields Phase 1 introduced (rawSpeech → status → history).
 *
 * WHAT IT STILL DOES NOT DO (by design, per the phase plan):
 * no command parsing (Phase 5), no action execution (Phase 6+), no TTS
 * (Phase 3). A successful transcript ends as a history entry marked
 * `NO_COMMAND_YET` — proof that speech works, honestly labelled as not yet
 * understood.
 *
 * THREADING: `SpeechRecognizer` requires the main thread, so everything runs on
 * `viewModelScope` (Main.immediate). No `delay`, no timers: the state machine is
 * driven purely by recogniser callbacks, which is what makes it testable.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class AssistantViewModel(
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val permissionChecker: MicrophonePermissionChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Monotonic id source for history entries. */
    private val nextId = AtomicLong(1L)

    init {
        viewModelScope.launch {
            speechRecognizerManager.events.collect(::onSpeechEvent)
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
                isPermissionBlocked = false
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

    private fun onFinalResult(text: String) {
        if (text.isBlank()) {
            // The recogniser reported success but produced nothing usable.
            onFailure(SpeechError.NO_MATCH)
            return
        }

        val record = CommandRecord(
            id = nextId.getAndIncrement(),
            timestampMillis = System.currentTimeMillis(),
            rawSpeech = text,
            normalizedText = text.lowercase().trim(),
            // Phase 5 will replace this with the parsed command type.
            commandType = NO_COMMAND_YET,
            handler = PHASE_2_HANDLER_LABEL,
            status = AssistantStatus.SUCCESS,
            message = SpeechMessages.CAPTURED_NOT_EXECUTED
        )

        _uiState.update {
            it.copy(
                status = AssistantStatus.SUCCESS,
                rawSpeech = record.rawSpeech,
                normalizedText = record.normalizedText,
                commandType = record.commandType,
                commandLabel = null,
                handler = record.handler,
                message = record.message,
                history = listOf(record) + it.history
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
                message = null
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
        speechRecognizerManager.cancel()
        _uiState.update {
            if (it.status == AssistantStatus.LISTENING || it.status == AssistantStatus.PROCESSING) {
                it.copy(status = AssistantStatus.IDLE, isAwaitingPermission = false)
            } else {
                it.copy(isAwaitingPermission = false)
            }
        }
    }

    override fun onCleared() {
        speechRecognizerManager.release()
        super.onCleared()
    }

    companion object {
        /** Command type used until Phase 5 can actually parse speech. */
        const val NO_COMMAND_YET = "NO_COMMAND_YET"

        /** Shown in the debug block so the phase boundary is explicit. */
        const val PHASE_2_HANDLER_LABEL = "None (Phase 2 captures speech only)"

        /** Handler column for an attempt that never reached a handler. */
        const val NO_HANDLER_YET = "—"
    }
}
