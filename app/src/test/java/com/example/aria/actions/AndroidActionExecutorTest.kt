package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import com.example.aria.permissions.PolicyAccessChecker
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — the router, exhaustively, plus the outermost error boundary.
 *
 * WHY THE ROUTER NEEDS ITS OWN TESTS: it is the one place that knows every
 * command. A missing branch would be caught by the compiler, but a *wrong* branch
 * (volume command sent to the sound-mode handler) is perfectly legal Kotlin and
 * silently wrong — so every command is routed here and the effect observed
 * through the shared fakes.
 */
class AndroidActionExecutorTest {

    private val volume = FakeVolumeController()
    private val ringer = FakeRingerController()
    private val policy = FakePolicyAccessChecker(granted = true)
    private val launcher = FakeLauncher()
    private val keys = FakeMediaKeyController()
    private val apps = FakeInstalledAppsProvider(listOf(AppEntry("com.maps", "Maps")))

    private fun executor() = AndroidActionExecutor(
        volumeHandler = VolumeActionHandler(volume),
        soundModeHandler = SoundModeActionHandler(ringer, policy),
        mediaHandler = MediaActionHandler(launcher, keys),
        mapsHandler = MapsActionHandler(launcher),
        appHandler = AppActionHandler(apps, launcher),
        settingsHandler = SettingsActionHandler(launcher),
        unknownHandler = UnknownActionHandler()
    )

    @Test
    fun `volume commands reach the volume controller`() = runTest {
        executor().execute(AssistantCommand.VolumeUp)

        assertTrue(volume.calls.any { it.startsWith("adjust(") })
        assertTrue("No other area should react", ringer.requestedModes.isEmpty())
    }

    @Test
    fun `sound mode commands reach the ringer`() = runTest {
        executor().execute(AssistantCommand.VibrateMode)

        assertEquals(listOf(RingerMode.VIBRATE), ringer.requestedModes)
        assertTrue(volume.calls.isEmpty() || volume.calls.none { it.startsWith("adjust(") })
    }

    @Test
    fun `transport commands reach the media keys`() = runTest {
        executor().execute(AssistantCommand.NextTrack)

        assertEquals(listOf(MediaKey.NEXT), keys.presses)
    }

    @Test
    fun `maps commands reach the launcher`() = runTest {
        executor().execute(AssistantCommand.OpenMaps)

        assertEquals(listOf(LaunchSpec.ViewUri("geo:0,0")), launcher.launched)
    }

    @Test
    fun `app commands reach the package launcher`() = runTest {
        executor().execute(AssistantCommand.OpenApp("Maps"))

        assertEquals(listOf(LaunchSpec.AppPackage("com.maps")), launcher.launched)
    }

    @Test
    fun `settings commands reach the settings launcher`() = runTest {
        executor().execute(AssistantCommand.OpenWifiSettings)

        assertEquals(
            listOf(LaunchSpec.SettingsScreen(SettingsActions.WIFI)),
            launcher.launched
        )
    }

    @Test
    fun `an unknown command is NotUnderstood and touches nothing`() = runTest {
        val result = executor().execute(AssistantCommand.Unknown("make me a sandwich"))

        assertEquals(ExecutionResult.NotUnderstood(ActionMessages.NOT_UNDERSTOOD), result)
        assertTrue(volume.calls.isEmpty())
        assertTrue(ringer.requestedModes.isEmpty())
        assertTrue(keys.presses.isEmpty())
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `the routing table covers every command without throwing`() = runTest {
        // The whole sealed hierarchy, one at a time. Any command that fell through
        // to a default would surface here as an Unexpected NotSupported.
        val dispatcher = executor()

        everyCommand().forEach { command ->
            val result = dispatcher.execute(command)
            assertFalse(
                "$command was not routed to a real handler: ${result.message}",
                result.message == ActionMessages.UNEXPECTED
            )
        }
    }

    @Test
    fun `an exception from a handler becomes a failure, not a crash`() = runTest {
        // A vendor PackageManager throwing mid-execution is the realistic case.
        val exploding = AndroidActionExecutor(
            volumeHandler = VolumeActionHandler(object : AudioVolumeController {
                override fun isVolumeFixed(): Boolean = throw IllegalStateException("boom")
                override fun maxIndex(stream: Int): Int = 15
                override fun currentIndex(stream: Int): Int = 0
                override fun adjust(stream: Int, direction: VolumeDirection) = Unit
                override fun set(stream: Int, index: Int) = Unit
            }),
            soundModeHandler = SoundModeActionHandler(ringer, policy),
            mediaHandler = MediaActionHandler(launcher, keys),
            mapsHandler = MapsActionHandler(launcher),
            appHandler = AppActionHandler(apps, launcher),
            settingsHandler = SettingsActionHandler(launcher),
            unknownHandler = UnknownActionHandler()
        )

        val result = exploding.execute(AssistantCommand.VolumeUp)

        assertEquals(ExecutionResult.Failure(ActionMessages.UNEXPECTED), result)
    }

    @Test
    fun `a permission result keeps its settings action through the router`() = runTest {
        policy.granted = false

        val result = executor().execute(AssistantCommand.SilentMode)

        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            (result as ExecutionResult.RequiresPermission).settingsIntentAction
        )
    }

    @Test
    fun `the real factory is not needed by tests, which is the point of the ports`() {
        // Documents the design: production wiring is a Context-bound function, and
        // every branch above was exercised with fakes instead.
        val policyPort: PolicyAccessChecker = FakePolicyAccessChecker()
        assertTrue(policyPort.canChangeSoundModes())
    }

    private fun everyCommand(): List<AssistantCommand> = listOf(
        AssistantCommand.VolumeUp,
        AssistantCommand.VolumeDown,
        AssistantCommand.SetVolume(40),
        AssistantCommand.SetVolume(40, com.example.aria.command.AudioStream.RING),
        AssistantCommand.MuteMedia,
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
        AssistantCommand.Unknown("blah")
    )
}
