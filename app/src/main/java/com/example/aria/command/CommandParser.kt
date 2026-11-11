package com.example.aria.command

/**
 * PHASE 5 — turns what the user said into what ARIA should do.
 *
 * ── WHY THIS IS AN INTERFACE ─────────────────────────────────────────────────
 * The rest of the app must never know *how* text becomes a command. Today it is
 * [RuleBasedCommandParser]: deterministic, offline, explainable. A later version
 * may swap in an `LlmCommandParser` — and because the ViewModel only ever needs a
 * `CommandParser`, that swap touches the factory and nothing else. Nothing
 * downstream (Phase 6's executor, the history, the UI) needs to change.
 *
 * ── WHAT AN IMPLEMENTATION MUST GUARANTEE ────────────────────────────────────
 *  - **Total function:** every string returns a command; failures are
 *    [AssistantCommand.Unknown], never exceptions.
 *  - **Pure:** no Android, no I/O, no randomness, no logging, no state.
 *  - **Deterministic:** the same text always yields the same command.
 *  - **Never executes anything.** Parsing describes intent; acting is Phase 6.
 *  - **Conservative:** when the text could sensibly mean two things, return
 *    [AssistantCommand.Unknown] instead of guessing.
 */
interface CommandParser {

    /**
     * Parses one utterance.
     *
     * @param rawText exactly what the speech recogniser returned, original
     *   capitalisation and punctuation included. Blank input must return
     *   `Unknown("")`, never an exception and never a whitespace-only payload.
     */
    fun parse(rawText: String): AssistantCommand
}
