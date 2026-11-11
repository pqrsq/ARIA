package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — sound modes, and the permission gate in front of them.
 *
 * THE RULE THIS FILE PROTECTS: ARIA must never claim Silent or Vibrate mode is on
 * when it is not. Two independent things can go wrong — the permission can be
 * missing, or the system can refuse the change — and each must produce a
 * different, truthful answer.
 */
class SoundModeActionHandlerTest {

    private val ringer = FakeRingerController()
    private val policy = FakePolicyAccessChecker(granted = true)

    private fun handler() = SoundModeActionHandler(ringer, policy)

    // --- Happy path -----------------------------------------------------------

    @Test
    fun `silent mode is requested and confirmed by read-back`() {
        val result = handler().handle(AssistantCommand.SilentMode)

        assertEquals(listOf(RingerMode.SILENT), ringer.requestedModes)
        assertEquals(ExecutionResult.Success("Silent mode is on."), result)
    }

    @Test
    fun `vibrate mode is requested and confirmed`() {
        val result = handler().handle(AssistantCommand.VibrateMode)

        assertEquals(listOf(RingerMode.VIBRATE), ringer.requestedModes)
        assertEquals(ExecutionResult.Success("Vibrate mode is on."), result)
    }

    @Test
    fun `normal ring mode is requested and confirmed`() {
        val ringer = FakeRingerController(mode = RingerMode.SILENT)

        val result = SoundModeActionHandler(ringer, policy).handle(AssistantCommand.NormalRingMode)

        assertEquals(listOf(RingerMode.NORMAL), ringer.requestedModes)
        assertEquals(ExecutionResult.Success("The ringer is back on."), result)
    }

    // --- The permission gate --------------------------------------------------

    @Test
    fun `silent mode without DND access asks for the permission and changes nothing`() {
        policy.granted = false

        val result = handler().handle(AssistantCommand.SilentMode)

        assertTrue("$result", result is ExecutionResult.RequiresPermission)
        assertEquals(ActionMessages.DO_NOT_DISTURB_REQUIRED, result.message)
        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            (result as ExecutionResult.RequiresPermission).settingsIntentAction
        )
        // The critical part: nothing was attempted.
        assertTrue("The ringer must not be touched", ringer.requestedModes.isEmpty())
    }

    @Test
    fun `vibrate mode is gated by the same permission`() {
        policy.granted = false

        val result = handler().handle(AssistantCommand.VibrateMode)

        assertTrue("$result", result is ExecutionResult.RequiresPermission)
        assertTrue(ringer.requestedModes.isEmpty())
    }

    @Test
    fun `restoring normal ringing does not ask for the permission`() {
        // Asking here would be an unnecessary prompt: leaving DND needs no access.
        policy.granted = false
        val ringer = FakeRingerController(mode = RingerMode.SILENT)

        val result = SoundModeActionHandler(ringer, policy).handle(AssistantCommand.NormalRingMode)

        assertTrue("$result", result.isSuccess)
        assertEquals(listOf(RingerMode.NORMAL), ringer.requestedModes)
    }

    @Test
    fun `the permission result never carries a null action here`() {
        // The UI needs a screen to offer; a null would leave the user stuck.
        policy.granted = false

        val result = handler().handle(AssistantCommand.SilentMode) as ExecutionResult.RequiresPermission

        // A null here would leave the user with no way forward.
        assertEquals(
            "A settings action must be offered",
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            result.settingsIntentAction
        )
    }

    // --- Refusals -------------------------------------------------------------

    @Test
    fun `a system that refuses to go silent is reported as a failure`() {
        val deaf = FakeRingerController(mode = RingerMode.NORMAL, honoursChanges = false)

        val result = SoundModeActionHandler(deaf, policy).handle(AssistantCommand.SilentMode)

        assertEquals(ExecutionResult.Failure("Silent mode could not be turned on."), result)
    }

    @Test
    fun `a system that lands on the wrong mode is not reported as success`() {
        // A vendor that silently converts SILENT into VIBRATE: read-back catches
        // it, whereas trusting the call would have said "Silent mode is on".
        val sneaky = object : RingerController {
            override fun setRingerMode(mode: RingerMode) = Unit
            override fun currentRingerMode(): RingerMode = RingerMode.VIBRATE
        }

        val result = SoundModeActionHandler(sneaky, policy).handle(AssistantCommand.SilentMode)

        assertEquals(ExecutionResult.Failure("Silent mode could not be turned on."), result)
    }

    @Test
    fun `failing to restore ringing is reported honestly`() {
        val stuck = FakeRingerController(mode = RingerMode.SILENT, honoursChanges = false)

        val result = SoundModeActionHandler(stuck, policy).handle(AssistantCommand.NormalRingMode)

        assertEquals(ExecutionResult.Failure("The ringer could not be turned back on."), result)
    }
}
