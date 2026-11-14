package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import com.example.aria.command.RejectionReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — [ExecutionResult] itself and the routing/naming tables.
 *
 * WHY THIS FILE EXISTS: the result type is the contract every handler and the
 * ViewModel share. If `isSuccess` were optimistic (true for "not supported"), a
 * failed action would be spoken as a success and no other test would catch it.
 */
class ExecutionResultTest {

    // --- The type's own behaviour -------------------------------------------

    @Test
    fun `only Success reports isSuccess`() {
        assertTrue(ExecutionResult.Success("done").isSuccess)
    }

    @Test
    fun `a failure never reports isSuccess`() {
        assertFalse(ExecutionResult.Failure("nope").isSuccess)
    }

    @Test
    fun `not-understood and not-supported are never successes`() {
        // "Nothing was attempted" and "this device cannot" are both non-successes;
        // conflating either with success is the bug this asserts against.
        assertFalse(ExecutionResult.NotUnderstood("what?").isSuccess)
        assertFalse(ExecutionResult.NotSupported("no map app").isSuccess)
    }

    @Test
    fun `a missing permission is not a success`() {
        val result = ExecutionResult.RequiresPermission("need DND", SettingsActions.NOTIFICATION_POLICY_ACCESS)

        assertFalse(result.isSuccess)
        assertEquals(SettingsActions.NOTIFICATION_POLICY_ACCESS, result.settingsIntentAction)
    }

    // --- A1 — typed failure reasons -----------------------------------------

    @Test
    fun `a failure without a reason is UNKNOWN, so old call sites keep working`() {
        // A1 is deliberately non-breaking: the default is the whole point.
        assertEquals(FailureReason.UNKNOWN, ExecutionResult.Failure("nope").reason)
    }

    @Test
    fun `a typed reason is carried verbatim`() {
        val result = ExecutionResult.Failure("no app", FailureReason.APP_NOT_FOUND)
        assertEquals(FailureReason.APP_NOT_FOUND, result.reason)
    }

    @Test
    fun `the reason does not change the message or isSuccess`() {
        // Metadata only: annotation must never be able to alter what is said.
        val plain = ExecutionResult.Failure("same words")
        val typed = ExecutionResult.Failure("same words", FailureReason.TIMEOUT)

        assertEquals(plain.message, typed.message)
        assertFalse(typed.isSuccess)
    }

    @Test
    fun `the reason enum covers the annotated causes`() {
        val causes = FailureReason.entries.toSet()
        listOf(
            FailureReason.APP_NOT_FOUND,
            FailureReason.LAUNCH_FAILED,
            FailureReason.PERMISSION_DENIED,
            FailureReason.NOT_SUPPORTED,
            FailureReason.INVALID_PARAMETER,
            FailureReason.TIMEOUT,
            FailureReason.UNKNOWN
        ).forEach { assertTrue("$it missing", it in causes) }
    }

    @Test
    fun `a missing permission may carry no settings screen`() {
        // The field is nullable on purpose: not every permission has a screen.
        assertNull(ExecutionResult.RequiresPermission("need something", null).settingsIntentAction)
    }

    @Test
    fun `every outcome carries a non-blank message`() {
        val results = listOf(
            ExecutionResult.Success("ok"),
            ExecutionResult.Failure("failed"),
            ExecutionResult.NotUnderstood(ActionMessages.NOT_UNDERSTOOD),
            ExecutionResult.RequiresPermission(ActionMessages.DO_NOT_DISTURB_REQUIRED, null),
            ExecutionResult.NotSupported(ActionMessages.NO_MAP_APP)
        )

        results.forEach { assertTrue("Blank message on $it", it.message.isNotBlank()) }
    }

    @Test
    fun `results compare by value so a test can assert one exactly`() {
        // data classes all the way down: assertEquals on a whole result is the
        // clearest possible assertion in a handler test.
        assertEquals(
            ExecutionResult.Success("Silent mode is on."),
            ExecutionResult.Success("Silent mode is on.")
        )
    }

    // --- Command → history id -----------------------------------------------

    @Test
    fun `camel case class names become upper snake case ids`() {
        assertEquals("VOLUME_DOWN", CommandTypeNaming.toUpperSnakeCase("VolumeDown"))
        assertEquals("SET_VOLUME", CommandTypeNaming.toUpperSnakeCase("SetVolume"))
        assertEquals("OPEN_WIFI_SETTINGS", CommandTypeNaming.toUpperSnakeCase("OpenWifiSettings"))
        assertEquals("UNKNOWN", CommandTypeNaming.toUpperSnakeCase("Unknown"))
    }

    @Test
    fun `an empty class name falls back instead of producing a blank id`() {
        assertEquals(CommandTypeNaming.FALLBACK, CommandTypeNaming.toUpperSnakeCase(""))
    }

    @Test
    fun `every command gets a non-blank id from its own type`() {
        // PHASE 6.5: ids come from the command's CLASS, not from its value, so
        // two values of one type (SetVolume(10) vs SetVolume(90), the two
        // Rejected reasons) deliberately share an id — they are one command type
        // in the history and the debug log. The assertion is therefore that every
        // id is non-blank and equals its own simple name, not that all ids differ.
        val ids = allCommands().map { CommandTypeNaming.of(it) }

        assertTrue("A command produced a blank id", ids.all { it.isNotBlank() })
        assertEquals(
            "Two different command TYPES share an id",
            ids.distinct().size,
            allCommands().map { it::class.simpleName }.distinct().size
        )
    }

    @Test
    fun `the id is stable and independent of the display name`() {
        // The display name is prose; the id must not change when the wording
        // does. Two different SetVolume values share one type id.
        val quiet = CommandTypeNaming.of(AssistantCommand.SetVolume(10))
        val loud = CommandTypeNaming.of(AssistantCommand.SetVolume(90))

        assertEquals("SET_VOLUME", quiet)
        assertEquals(quiet, loud)
    }

    // --- Command → handler name ---------------------------------------------

    @Test
    fun `every command names an existing handler`() {
        allCommands().forEach { command ->
            val handler = HandlerNames.forCommand(command)
            assertTrue(
                "$command mapped to unknown handler '$handler'",
                handler in HandlerNames.ALL
            )
        }
    }

    @Test
    fun `handler names follow the project convention`() {
        HandlerNames.ALL.forEach { name ->
            assertTrue("'$name' should end in ActionHandler", name.endsWith("ActionHandler"))
        }
    }

    @Test
    fun `each area routes to its own handler`() {
        assertEquals("VolumeActionHandler", HandlerNames.forCommand(AssistantCommand.VolumeUp))
        assertEquals("SoundModeActionHandler", HandlerNames.forCommand(AssistantCommand.SilentMode))
        assertEquals("MediaActionHandler", HandlerNames.forCommand(AssistantCommand.PauseMusic))
        assertEquals("MapsActionHandler", HandlerNames.forCommand(AssistantCommand.OpenMaps))
        assertEquals("AppActionHandler", HandlerNames.forCommand(AssistantCommand.OpenApp("Maps")))
        assertEquals("SettingsActionHandler", HandlerNames.forCommand(AssistantCommand.OpenSettings))
        assertEquals("UnknownActionHandler", HandlerNames.forCommand(AssistantCommand.Unknown("blah")))
    }

    @Test
    fun `the settings actions carry the platform values exactly`() {
        // These strings are what makes the screen open; a typo is silent at
        // runtime, so they are pinned here.
        assertEquals("android.settings.SETTINGS", SettingsActions.SETTINGS)
        assertEquals("android.settings.WIFI_SETTINGS", SettingsActions.WIFI)
        assertEquals("android.settings.BLUETOOTH_SETTINGS", SettingsActions.BLUETOOTH)
        assertEquals("android.settings.POWER_USAGE_SUMMARY", SettingsActions.BATTERY)
        assertEquals(
            "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
            SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
    }

    @Test
    fun `the shared message table is used rather than duplicated literals`() {
        // A regression guard: the spec fixes this sentence, so it is asserted once
        // here and referenced by the handler tests.
        assertEquals("I didn't understand that command.", ActionMessages.NOT_UNDERSTOOD)
        assertEquals("No map app is installed", ActionMessages.NO_MAP_APP)
        assertEquals(
            "ARIA needs Do Not Disturb access to change sound modes.",
            ActionMessages.DO_NOT_DISTURB_REQUIRED
        )
        assertEquals(
            "Could not find an app named \"Spotify\".",
            ActionMessages.appNotFound("Spotify")
        )
        assertEquals(
            "Opening navigation to Milan in your map app.",
            ActionMessages.navigation("Milan")
        )
    }

    @Test
    fun `an unexpected handler result is a NotSupported rather than a silent success`() = runTest {
        // Each handler is fed a command it does not own. The defensive branch must
        // never claim success, because that would be an invented answer.
        val volume = VolumeActionHandler(FakeVolumeController()).handle(AssistantCommand.OpenMaps)
        val sound = SoundModeActionHandler(FakeRingerController(), FakePolicyAccessChecker())
            .handle(AssistantCommand.VolumeUp)
        val unknown = UnknownActionHandler().handle(AssistantCommand.OpenMaps)
        val settings = SettingsActionHandler(FakeLauncher())
            .handle(AssistantCommand.VolumeUp) as ExecutionResult

        listOf(volume, sound, unknown, settings).forEach {
            assertTrue("$it should not claim success", it is ExecutionResult.NotSupported)
            assertEquals(ActionMessages.UNEXPECTED, it.message)
        }
    }

    /** One instance of every command, so the tables can be checked exhaustively. */
    private fun allCommands(): List<AssistantCommand> = listOf(
        AssistantCommand.VolumeUp,
        AssistantCommand.VolumeDown,
        AssistantCommand.SetVolume(50),
        AssistantCommand.MuteMedia,
        AssistantCommand.UnmuteMedia,
        AssistantCommand.SilentMode,
        AssistantCommand.VibrateMode,
        AssistantCommand.NormalRingMode,
        AssistantCommand.PlayMusic("jazz"),
        AssistantCommand.PauseMusic,
        AssistantCommand.ResumeMusic,
        AssistantCommand.NextTrack,
        AssistantCommand.PreviousTrack,
        AssistantCommand.OpenMaps,
        AssistantCommand.SearchLocation("cafe"),
        AssistantCommand.NavigateTo("Milan"),
        AssistantCommand.OpenApp("Maps"),
        AssistantCommand.OpenSettings,
        AssistantCommand.OpenWifiSettings,
        AssistantCommand.OpenBluetoothSettings,
        AssistantCommand.OpenBatterySettings,
        AssistantCommand.Unknown("blah"),
        AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE),
        AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE)
    )
}
