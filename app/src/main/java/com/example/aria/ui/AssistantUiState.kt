package com.example.aria.ui

import com.example.aria.command.AssistantCommand

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
     * PHASE 6.5: the words were understood, and ARIA declined on purpose.
     *
     * WHY A FOURTH OUTCOME: "I didn't understand that" (SUCCESS, green) and "that
     * failed" (ERROR, red) are both wrong for a deliberate refusal like "I can
     * only handle one command at a time". A neutral colour is the honest one:
     * nothing malfunctioned and nothing succeeded.
     */
    REJECTED,

    /**
     * PHASE 3: ARIA is talking back. A distinct state (not a flag on SUCCESS)
     * because the screen must be able to show "speaking" while the result card
     * still shows what was said — one immutable state, no contradictions.
     */
    SPEAKING
}

/**
 * PHASE P1.6 — where an interaction came from.
 *
 * WHY IT EXISTS: speech recognition on a noisy device is unreliable, so a typed
 * debug command and a spoken one can look identical in the history even though
 * only one of them involved the microphone. Recording the origin makes every
 * result attributable afterwards, and it costs nothing: the value travels with
 * the same pipeline the voice path already uses.
 *
 * [VOICE] is the default everywhere, because voice is the product. [TEXT] is
 * only ever produced by the debug text field.
 */
enum class InputSource {
    /** A final result from the speech recogniser — the normal path. */
    VOICE,

    /** A command typed into the debug field. Never requests the microphone. */
    TEXT
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
    val message: String = "",
    /**
     * PHASE P1.6 — voice or debug text.
     *
     * LAST PARAMETER WITH A DEFAULT ON PURPOSE: it is appended, so every existing
     * positional construction (and every `assertEquals` on a record in older
     * tests) keeps its meaning. Adding it anywhere else would silently re-bind
     * arguments.
     */
    val source: InputSource = InputSource.VOICE
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
     * Defaults to true and is still NOT persisted — Phase 12 persisted the
     * history only, so the mute preference resets on every launch. Toggled from
     * the settings sheet. R2 correction: this comment used to claim "Phase 12
     * will persist it", which Phase 12 as scoped never did.
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
    val pendingSettingsAction: String? = null,
    /**
     * PHASE 6.5 (D2): the command that was refused for lack of a permission.
     *
     * Kept so that returning from Settings with the grant can RETRY the original
     * request instead of asking the user to repeat themselves. Null when nothing
     * is pending. It is deliberately a typed command, not a re-parsed String: the
     * parser is not guaranteed to produce the same command for the same text
     * forever, and retrying must reproduce exactly what was understood.
     */
    val pendingCommand: AssistantCommand? = null,
    /** PHASE 6.5 (D2): the transcript that produced [pendingCommand]. */
    val pendingRawSpeech: String? = null,
    /**
     * PHASE P1.6 — which input produced the CURRENT result card.
     *
     * VOICE for everything the recogniser finishes, TEXT only for the debug
     * field. Defaults to VOICE so a state built before any interaction is
     * honest: voice is the primary path.
     */
    val inputSource: InputSource = InputSource.VOICE
) {
    /**
     * True when there is at least one recognisable field worth showing.
     *
     * PHASE P1.6: a REJECTED result is included. "Two commands in one sentence"
     * carries neither a transcript nor a spoken reply, so before this the card
     * would silently not appear and a typed (or spoken) compound sentence looked
     * like nothing had happened.
     */
    val hasResult: Boolean
        get() = rawSpeech != null || message != null || lastSpokenText != null ||
            status == AssistantStatus.REJECTED

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
