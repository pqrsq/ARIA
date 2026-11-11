package com.example.aria.ui

/**
 * The lifecycle of one assistant interaction, as shown to the user.
 *
 * WHY A SINGLE ENUM:
 * The screen must always be able to answer "what is happening right now?".
 * Four states are enough for Version 1 and map 1:1 to what the UI renders
 * (idle hint, listening animation, result card, error card).
 */
enum class AssistantStatus {
    /** Nothing has happened yet, or a result was dismissed. */
    IDLE,

    /** The microphone is open and ARIA waits for the user to finish speaking. */
    LISTENING,

    /** Speech is recognised / command is being parsed and executed. */
    PROCESSING,

    /** The command was understood and executed successfully. */
    SUCCESS,

    /** Something failed: no speech, unknown command, missing app, no permission… */
    ERROR
}

/**
 * One completed assistant interaction.
 *
 * This is deliberately a *pure data* class with no Android dependencies so it
 * can be unit-tested and (from Phase 12) persisted as-is.
 *
 * [rawSpeech]  – exactly what the speech recogniser returned
 * [normalizedText] – the parser's cleaned-up version (Phase 5)
 * [commandType] – e.g. "VOLUME_DOWN" (stable string, used for history + debug)
 * [handler]     – which ActionHandler executed it, e.g. "VolumeActionHandler"
 */
data class CommandRecord(
    val id: Long,
    val timestampMillis: Long,
    val rawSpeech: String,
    val normalizedText: String = "",
    val commandType: String = "UNKNOWN_COMMAND",
    val handler: String = "—",
    val status: AssistantStatus = AssistantStatus.SUCCESS,
    val message: String = ""
)

/**
 * Everything the Assistant screen needs to draw itself.
 *
 * A single immutable state object (not many separate fields) means the UI can
 * never show a combination that the app never intended — the classic source of
 * "listening spinner + error card" bugs.
 */
data class AssistantUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val rawSpeech: String? = null,
    val normalizedText: String? = null,
    val commandType: String? = null,
    val commandLabel: String? = null,
    val handler: String? = null,
    val message: String? = null,
    val history: List<CommandRecord> = emptyList(),
    val debugMode: Boolean = false,
    val isDebugToggleAvailable: Boolean = true
) {
    /** True when there is at least one recognisable field worth showing. */
    val hasResult: Boolean
        get() = rawSpeech != null || message != null
}
