package com.example.aria.actions

import com.example.aria.command.RejectionReason

/**
 * PHASE 6.5 — the sentence that explains each [RejectionReason].
 *
 * WHY THIS IS SEPARATE FROM [ActionMessages]: a rejection's *reason* lives in the
 * command model (which must stay free of user-facing prose), while its *sentence*
 * belongs with the other things ARIA says. This object is the one bridge between
 * them, and tests assert both halves, so the wording cannot drift.
 *
 * TONE MATTERS: every sentence here states a limit ARIA has, in plain language.
 * None of them implies the user was at fault.
 */
object RejectionMessages {

    /** The sentence for [reason]. Never blank. */
    fun forReason(reason: RejectionReason): String = when (reason) {
        RejectionReason.COMPOUND_SENTENCE -> ActionMessages.ONE_COMMAND_AT_A_TIME
        RejectionReason.VOLUME_OUT_OF_RANGE -> ActionMessages.VOLUME_OUT_OF_RANGE
    }
}
