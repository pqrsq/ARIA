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
    ERROR,

    /**
     * PHASE 3: ARIA is talking back. A distinct state (not a flag on SUCCESS)
     * because the screen must be able to show "speaking" while the result card
     * still shows what was said — one immutable state, no contradictions.
     */
    SPEAKING
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
    val isDebugToggleAvailable: Boolean = true,
    /**
     * PHASE 2: true while the screen must show the system microphone dialog.
     * The UI observes this and asks once; the ViewModel never launches it
     * (a permission request needs an Activity, which a ViewModel must not hold).
     */
    val isAwaitingPermission: Boolean = false,
    /**
     * PHASE 2: true when the microphone permission was refused for good, so the
     * UI can offer a link to the app's settings page without string-matching.
     */
    val isPermissionBlocked: Boolean = false,
    /**
     * PHASE 3: the user's preference — may ARIA speak its replies?
     * Defaults to true; Phase 12 will persist it. Toggled from the settings sheet.
     */
    val ttsEnabled: Boolean = true,
    /**
     * PHASE 3: the last line ARIA spoke (or tried to). Shown as text, so a muted
     * or broken engine still gives the user the reply. Null when nothing spoke.
     */
    val spokenReply: String? = null,
    /**
     * PHASE 3: the reply being spoken or just spoken. [AssistantStatus.SPEAKING]
     * is the state; this is the content, kept separately so the history and the
     * card never have to be rewritten while audio plays.
     */
    val lastSpokenText: String? = null,
    /**
     * PHASE 3: false when the TTS engine failed to initialise (or has no en-US
     * voice). The settings switch then explains itself instead of lying.
     */
    val isTtsAvailable: Boolean = true,
    /**
     * PHASE 3: why TTS is unavailable, ready to render. Null when all is well.
     */
    val ttsMessage: String? = null,
    /**
     * PHASE 6: the exact `Settings.ACTION_*` screen the last command needs the
     * user to visit, carried by `ExecutionResult.RequiresPermission`. Null when
     * nothing is missing.
     *
     * CARRIED, NOT LAUNCHED: ARIA reports which screen would help; opening it
     * stays an explicit user decision (see docs/PHASE6.md, security rules).
     */
    val pendingSettingsAction: String? = null
) {
    /** True when there is at least one recognisable field worth showing. */
    val hasResult: Boolean
        get() = rawSpeech != null || message != null || lastSpokenText != null

    /** True while audio is playing, so the UI can pulse its "speaking" indicator. */
    val isSpeaking: Boolean
        get() = status == AssistantStatus.SPEAKING

    /**
     * True when the user wants speech AND the engine can do it. The single
     * question the ViewModel asks before calling `speak`, so the two conditions
     * can never drift apart.
     */
    val canSpeak: Boolean
        get() = ttsEnabled && isTtsAvailable

    /**
     * True when the current result is a live transcript rather than a finished
     * one, so the card can show the words as they are recognised.
     */
    val isLiveTranscript: Boolean
        get() = status == AssistantStatus.LISTENING && !rawSpeech.isNullOrBlank()
}
