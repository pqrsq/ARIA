package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — the four settings screens ARIA can open.
 *
 * ── WHY `ActivityNotFoundException` IS NORMAL HERE ──────────────────────────
 * Settings actions are not guaranteed: OEM builds rename, remove or gate them
 * (battery screens are a common casualty, and some regional builds ship without
 * Bluetooth). A missing screen therefore becomes
 * [ExecutionResult.NotSupported] with a plain sentence — never a crash, never a
 * silent no-op, and never "opened" when nothing opened.
 *
 * ── WHAT THESE COMMANDS DO NOT DO ───────────────────────────────────────────
 * They open a screen; they do **not** toggle the radio. Turning Wi-Fi off would
 * be a completely different permission set and a far more invasive action, and
 * the Phase 6 spec asks only for navigation to the screen. The messages say
 * "Opening Wi-Fi settings", which is precisely what happens.
 */
class SettingsActionHandler(
    private val launcher: Launcher
) {

    suspend fun handle(command: AssistantCommand): ExecutionResult {
        val (action, message) = when (command) {
            is AssistantCommand.OpenSettings ->
                SettingsActions.SETTINGS to "Opening settings."

            is AssistantCommand.OpenWifiSettings ->
                SettingsActions.WIFI to "Opening Wi-Fi settings."

            is AssistantCommand.OpenBluetoothSettings ->
                SettingsActions.BLUETOOTH to "Opening Bluetooth settings."

            is AssistantCommand.OpenBatterySettings ->
                SettingsActions.BATTERY to "Opening battery settings."

            else -> return ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
        }

        return if (launcher.launch(LaunchSpec.SettingsScreen(action))) {
            ExecutionResult.Success(message)
        } else {
            ExecutionResult.NotSupported("This device does not offer that settings screen.")
        }
    }
}
