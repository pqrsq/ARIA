package com.example.aria.actions

/**
 * PHASE 6 — the only thing an action handler is allowed to return.
 *
 * ── WHY A SEALED RESULT AND NOT EXCEPTIONS OR A BOOLEAN ──────────────────────
 * A `Boolean` cannot explain itself, and an `Exception` makes the normal
 * agreement of "this did not work, and here is why" look like a crash. Every
 * outcome below is a *sentence the user can be told out loud*, which is the
 * whole point of a voice assistant. The sealed hierarchy also forces the
 * ViewModel to handle every case explicitly — adding a new outcome later breaks
 * the build instead of silently falling through to "success".
 *
 * ── THE FIVE OUTCOMES, AND WHY THEY ARE NOT MERELY "OK / NOT OK" ─────────────
 *  - [Success]           the action was performed (verified where Android lets
 *                        us verify it) and [message] says what changed.
 *  - [Failure]           we tried, and it did not work.
 *  - [NotUnderstood]     we never tried: the parser found no command. Kept
 *                        separate from [Failure] because "I did not understand
 *                        that command." is an ordinary outcome of talking to a
 *                        machine, not a malfunction — and because the two will
 *                        want different wording (and, later, different UI) as the
 *                        project grows.
 *  - [RequiresPermission] a permission the user must grant by hand stands in the
 *                        way. [settingsIntentAction] is the *exact* Settings
 *                        action to offer — it is carried, never launched
 *                        automatically (see the Phase 6 security rules).
 *  - [NotSupported]      this device cannot do it at all (fixed volume, no map
 *                        app, missing settings screen). Not an error either: it
 *                        is a fact about the hardware, not about the attempt.
 *  - [Rejected]          the words were understood, and ARIA declined on purpose
 *                        ("I can only handle one command at a time."). Phase 6.5.
 *
 * HONESTY RULE: a handler returns [Success] only for the part of the work it
 * actually did. Launching an activity returns [Success] for *launching*, and the
 * message says "opening", never "opened" or "playing" or "navigating".
 */
sealed interface ExecutionResult {

    /** One short, speakable sentence. Never null, never a stack trace. */
    val message: String

    /** True only for a verified [Success]. Convenience for the ViewModel. */
    val isSuccess: Boolean
        get() = this is Success

    /** The action was carried out; [message] describes the visible effect. */
    data class Success(override val message: String) : ExecutionResult

    /**
     * The action was attempted and did not take effect.
     *
     * [reason] is METADATA (Phase 6.5, A1): it exists so callers can react to a
     * *class* of failure later (analytics, retry, a different hint) without
     * parsing the sentence. It has no effect on behaviour today, and it defaults
     * to [FailureReason.UNKNOWN] so every existing call site still compiles.
     */
    data class Failure(
        override val message: String,
        val reason: FailureReason = FailureReason.UNKNOWN
    ) : ExecutionResult

    /**
     * Speech was understood but deliberately not carried out.
     *
     * Distinct from [Failure] (nothing was attempted, so nothing failed) and from
     * [NotUnderstood] (the words *were* understood). The UI gives it its own
     * neutral colour: neither the green of success nor the red of failure.
     */
    data class Rejected(override val message: String) : ExecutionResult

    /** No command was recognised, so nothing was attempted. */
    data class NotUnderstood(override val message: String) : ExecutionResult

    /**
     * A user-granted permission is missing.
     *
     * @param settingsIntentAction the `Settings.ACTION_*` constant for the screen
     *   the user needs, or null when there is no such screen. Carried for the UI
     *   layer; never launched by the executor.
     */
    data class RequiresPermission(
        override val message: String,
        val settingsIntentAction: String?
    ) : ExecutionResult

    /** This device cannot perform the action. */
    data class NotSupported(override val message: String) : ExecutionResult
}

/**
 * PHASE 6.5 (A1) — the *kind* of failure, for callers that want more than prose.
 *
 * WHY THIS IS METADATA ONLY: the user-facing sentence stays in
 * [com.example.aria.actions.ActionMessages] and remains the source of truth for
 * what is said. This enum is for code: a future retry policy, a permission hint,
 * or a bug report that groups failures by cause instead of by wording.
 *
 * [UNKNOWN] is the default so that adding a reason is never a breaking change.
 */
enum class FailureReason {
    /** No better classification was supplied. */
    UNKNOWN,

    /** No installed app matched the spoken name. */
    APP_NOT_FOUND,

    /** Something accepted the name but nothing opened. */
    LAUNCH_FAILED,

    /** A permission the user must grant stands in the way. */
    PERMISSION_DENIED,

    /** The device cannot do this. */
    NOT_SUPPORTED,

    /** The request was well-formed but invalid (range, empty entity…). */
    INVALID_PARAMETER,

    /** A system service did not answer in time. */
    TIMEOUT
}
