package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import com.example.aria.permissions.PolicyAccessChecker

/**
 * PHASE 6 — silent, vibrate and normal ringer modes.
 *
 * ── THE THREE RULES THIS CLASS EXISTS TO ENFORCE ────────────────────────────
 *  1. **Ask before changing.** Silencing a ringer needs Do Not Disturb access on
 *     modern Android. If it is missing, ARIA returns
 *     [ExecutionResult.RequiresPermission] carrying the exact settings action and
 *     changes *nothing*. It never auto-grants, never redirects by itself, and
 *     never fakes the outcome.
 *  2. **Verify.** `setRingerMode` is a request the system may decline. The mode is
 *     read back; if the phone is not in the mode ARIA asked for, the result says
 *     so. "Silent mode is on" is only ever said when it is.
 *  3. **Never substitute `setInterruptionFilter`.** Do Not Disturb is a *different*
 *     state from silent, and switching to it would mute alarms the user never
 *     mentioned.
 *
 * ── WHY NORMAL RINGING DOES NOT NEED THE PERMISSION ─────────────────────────
 * Leaving Do Not Disturb and restoring the ringer is permitted without policy
 * access, so asking for it there would be an unnecessary prompt — the project
 * rules forbid exactly that. The check is therefore applied only where the
 * platform applies it.
 */
class SoundModeActionHandler(
    private val ringer: RingerController,
    private val policy: PolicyAccessChecker
) {

    fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.SilentMode -> changeTo(RingerMode.SILENT)
        is AssistantCommand.VibrateMode -> changeTo(RingerMode.VIBRATE)
        is AssistantCommand.NormalRingMode -> changeTo(RingerMode.NORMAL)
        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }

    private fun changeTo(mode: RingerMode): ExecutionResult {
        val needsPolicyAccess = mode != RingerMode.NORMAL
        if (needsPolicyAccess && !policy.canChangeSoundModes()) {
            return ExecutionResult.RequiresPermission(
                message = ActionMessages.DO_NOT_DISTURB_REQUIRED,
                settingsIntentAction = SettingsActions.NOTIFICATION_POLICY_ACCESS
            )
        }

        ringer.setRingerMode(mode)

        // Read back: the system may have ignored the request (a policy, a
        // managed profile, a device that does not support silent mode).
        val actual = ringer.currentRingerMode()
        return if (actual == mode) {
            ExecutionResult.Success(successMessage(mode))
        } else {
            ExecutionResult.Failure(failureMessage(mode))
        }
    }

    private fun successMessage(mode: RingerMode): String = when (mode) {
        RingerMode.SILENT -> "Silent mode is on."
        RingerMode.VIBRATE -> "Vibrate mode is on."
        RingerMode.NORMAL -> "The ringer is back on."
    }

    private fun failureMessage(mode: RingerMode): String = when (mode) {
        RingerMode.SILENT -> "Silent mode could not be turned on."
        RingerMode.VIBRATE -> "Vibrate mode could not be turned on."
        RingerMode.NORMAL -> "The ringer could not be turned back on."
    }
}
