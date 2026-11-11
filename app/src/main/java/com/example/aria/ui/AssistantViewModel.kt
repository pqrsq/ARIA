package com.example.aria.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the screen state for the assistant.
 *
 * ── PHASE 1 SCOPE ────────────────────────────────────────────────────────────
 * There is no microphone, no parser and no ActionExecutor yet. This ViewModel
 * therefore only:
 *   1. exposes an immutable [AssistantUiState] through a StateFlow,
 *   2. implements the microphone *interaction seam* used in Phase 2,
 *   3. stores an in-memory command history (replaced by a repository in
 *      Phase 12 — the UI already reads history from state, so nothing above
 *      this class will need to change).
 *
 * WHY StateFlow + a plain ViewModel:
 *  - StateFlow always has a current value, so the UI has no "empty screen" flash
 *    and survives configuration changes (screen rotation) for free.
 *  - The ViewModel is the only place that mutates state, so there is exactly one
 *    source of truth. Phase 2 will call into a SpeechRecognizerManager from here.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class AssistantViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Monotonic id source for history entries. */
    private val nextId = AtomicLong(1L)

    /**
     * Invoked when the user taps the microphone.
     *
     * Phase 1: politely reports that voice input is not wired yet.
     * Phase 2: this method will start the SpeechRecognizer instead, and the
     *          same state fields (rawSpeech → commandType → status) will be
     *          filled in by the recognition + parsing + execution pipeline.
     */
    fun onMicrophoneTapped() {
        if (_uiState.value.status == AssistantStatus.LISTENING) {
            // Future: stopListening(). Nothing to stop in Phase 1.
            return
        }
        _uiState.update { it.copy(status = AssistantStatus.IDLE, message = MIC_NOT_WIRED_MESSAGE) }
    }

    /**
     * TEMPORARY Phase 1 affordance so the UI and the state machine can be
     * verified on a device before speech recognition exists.
     *
     * It runs a *fake* interaction through exactly the same state transitions
     * Phase 2 will use: LISTENING → PROCESSING → SUCCESS + history entry.
     * It will be deleted in Phase 2 — the UI does not know it exists beyond the
     * optional "Simulate" control.
     */
    fun simulateVoiceCommand(spokenText: String = "Turn the volume down") {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = AssistantStatus.LISTENING,
                    rawSpeech = null,
                    normalizedText = null,
                    commandType = null,
                    commandLabel = null,
                    handler = null,
                    message = null
                )
            }
            kotlinx.coroutines.delay(700)

            _uiState.update { it.copy(status = AssistantStatus.PROCESSING) }
            kotlinx.coroutines.delay(500)

            val record = CommandRecord(
                id = nextId.getAndIncrement(),
                timestampMillis = System.currentTimeMillis(),
                rawSpeech = spokenText,
                normalizedText = spokenText.lowercase().trim(),
                commandType = "VOLUME_DOWN",
                handler = "VolumeActionHandler (not implemented yet)",
                status = AssistantStatus.SUCCESS,
                message = "Simulated — no Android action was performed."
            )
            _uiState.update {
                it.copy(
                    status = AssistantStatus.SUCCESS,
                    rawSpeech = record.rawSpeech,
                    normalizedText = record.normalizedText,
                    commandType = record.commandType,
                    commandLabel = "Volume Down",
                    handler = record.handler,
                    message = record.message,
                    history = listOf(record) + it.history
                )
            }
        }
    }

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
        _uiState.update { it.copy(history = emptyList()) }
    }

    companion object {
        const val MIC_NOT_WIRED_MESSAGE = "Voice input arrives in Phase 2."
    }
}
