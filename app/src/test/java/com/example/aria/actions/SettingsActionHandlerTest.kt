package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — settings screens.
 *
 * WHAT MATTERS HERE: the exact action string for each command (a typo opens
 * nothing, silently), and that a device without the screen produces a plain
 * "not supported" rather than an exception.
 */
class SettingsActionHandlerTest {

    private val launcher = FakeLauncher()
    private val handler = SettingsActionHandler(launcher)

    @Test
    fun `open settings launches the settings action`() = runTest {
        val result = handler.handle(AssistantCommand.OpenSettings)

        assertEquals(
            listOf(LaunchSpec.SettingsScreen("android.settings.SETTINGS")),
            launcher.launched
        )
        assertEquals(ExecutionResult.Success("Opening settings."), result)
    }

    @Test
    fun `wifi, bluetooth and battery each map to their own screen`() = runTest {
        handler.handle(AssistantCommand.OpenWifiSettings)
        handler.handle(AssistantCommand.OpenBluetoothSettings)
        handler.handle(AssistantCommand.OpenBatterySettings)

        assertEquals(
            listOf(
                LaunchSpec.SettingsScreen("android.settings.WIFI_SETTINGS"),
                LaunchSpec.SettingsScreen("android.settings.BLUETOOTH_SETTINGS"),
                LaunchSpec.SettingsScreen("android.settings.POWER_USAGE_SUMMARY")
            ),
            launcher.launched
        )
    }

    @Test
    fun `a missing settings screen is NotSupported, never a crash`() = runTest {
        // Real case: some OEM builds ship without the Bluetooth screen.
        val blocked = FakeLauncher(accepts = false)

        val result = SettingsActionHandler(blocked).handle(AssistantCommand.OpenBluetoothSettings)

        assertTrue("$result", result is ExecutionResult.NotSupported)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `every settings command produces a distinct screen`() = runTest {
        // Four commands, four different actions: a copy-paste slip would make two
        // commands open the same screen, which is invisible in a manual test.
        listOf(
            AssistantCommand.OpenSettings,
            AssistantCommand.OpenWifiSettings,
            AssistantCommand.OpenBluetoothSettings,
            AssistantCommand.OpenBatterySettings
        ).forEach { handler.handle(it) }

        val actions = launcher.launched.map { (it as LaunchSpec.SettingsScreen).action }
        assertEquals(4, actions.toSet().size)
    }
}
