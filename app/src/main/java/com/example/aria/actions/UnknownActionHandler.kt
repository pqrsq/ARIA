package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — the fallback: the parser heard speech but recognised no command.
 *
 * ── WHY [ExecutionResult.NotUnderstood] AND NOT `Failure` ───────────────────
 * Nothing was attempted, so nothing failed. "I didn't understand that command."
 * is the ordinary, expected response of a machine that listens — treating it as
 * an error would paint the result card red for a normal miss and would make the
 * history lie about reliability.
 *
 * ── WHAT HAPPENS TO THE TEXT ────────────────────────────────────────────────
 * [AssistantCommand.Unknown.rawText] is kept by the caller for diagnostics (the
 * debug block and the history row), and this handler deliberately does not echo
 * it back inside the spoken sentence: repeating someone's words incorrectly is
 * more confusing than a neutral "I didn't understand that.".
 */
class UnknownActionHandler {

    fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.Unknown -> ExecutionResult.NotUnderstood(ActionMessages.NOT_UNDERSTOOD)
        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }
}
