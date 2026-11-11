package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — turns a parsed [AssistantCommand] into a real Android action.
 *
 * ── WHY AN INTERFACE ─────────────────────────────────────────────────────────
 * [com.example.aria.ui.AssistantViewModel] must stay Android-free so its whole
 * flow can be unit-tested on the JVM. It only knows "somebody can execute this
 * command"; [AndroidActionExecutor] is the one implementation that touches
 * `AudioManager`, `PackageManager` and `Intent`s.
 *
 * ── CONTRACT ─────────────────────────────────────────────────────────────────
 *  - **Suspending:** execution is I/O against system services, so callers must
 *    not be forced onto the main thread.
 *  - **Total:** every command returns a result. A handler that throws is a bug;
 *    the ViewModel still guards the boundary (see `AssistantViewModel`), but an
 *    implementation should not rely on that.
 *  - **Never invents success:** the returned [ExecutionResult] describes what
 *    really happened, including "I could not do this".
 *  - **No state:** one call must not depend on the previous one, apart from what
 *    the device itself remembers.
 */
interface ActionExecutor {

    /** Executes [command] and reports honestly what happened. */
    suspend fun execute(command: AssistantCommand): ExecutionResult
}
