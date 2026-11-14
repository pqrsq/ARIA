package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * Test double for [ActionExecutor].
 *
 * WHY A FAKE: [com.example.aria.ui.AssistantViewModelTest] must prove the
 * *pipeline* — parse, dedupe, execute off the main thread, publish, speak — with
 * no Android and no system services. The executor is the one collaborator that
 * would need a device, so it is replaced by a recorder that can also be told to
 * answer with any [ExecutionResult] or to throw.
 */
class FakeActionExecutor(
    /** What [execute] returns. Tests overwrite this to script an outcome. */
    var result: ExecutionResult = ExecutionResult.Success("Media volume up to 60%.")
) : ActionExecutor {

    /** Every command the ViewModel asked to execute, in order. */
    val executed: MutableList<AssistantCommand> = mutableListOf()

    /** Set to make [execute] throw, for the boundary tests. */
    var throwing: RuntimeException? = null

    override suspend fun execute(command: AssistantCommand): ExecutionResult {
        executed += command
        throwing?.let { throw it }
        // PHASE 6.5: a Rejected command never reaches a handler, so mirroring the
        // real executor here keeps "the parser refused this" observable in UI
        // tests instead of being masked by whatever outcome a test scripted.
        if (command is AssistantCommand.Rejected) {
            return ExecutionResult.Rejected(RejectionMessages.forReason(command.reason))
        }
        return result
    }
}
