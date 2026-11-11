package com.example.aria.actions

import com.example.aria.command.AssistantCommand
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
    fun `every command gets a distinct non-blank id`() {
        val ids = allCommands().map { CommandTypeNaming.of(it) }

        assertTrue("A command produced a blank id", ids.all { it.isNotBlank() })
        assertEquals("Two commands share an id", ids.size, ids.toSet().size)
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
        AssistantCommand.SilentMode,
        AssistantCommand.VibrateMode,
        AssistantCommand.NormalRingMode,
        AssistantCommand.PlayMusic("jazz"),
        AssistantCommand.PlayMusic(""),
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
        AssistantCommand.Unknown("blah")
    )
}
