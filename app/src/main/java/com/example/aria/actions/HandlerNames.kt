package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — the handler name shown in the history row and the debug block.
 *
 * ── WHY A SEPARATE PURE MAP ──────────────────────────────────────────────────
 * The user-facing record wants `handler = "VolumeActionHandler"`, but
 * [com.example.aria.ui.AssistantViewModel] must not touch the Android classes
 * that own those handlers: referencing `AndroidActionExecutor` from the ViewModel
 * would load `android.media.AudioManager` into a JVM unit test. This tiny pure
 * `when` is the compromise.
 *
 * ── THE COST, STATED HONESTLY ────────────────────────────────────────────────
 * This is a second exhaustive `when` over [AssistantCommand], parallel to the
 * routing in [AndroidActionExecutor]. (Phase 6.5 added `UnmuteMedia` → volume and
 * `Rejected` → the fallback handler.) The compiler keeps *this* one complete, but
 * it cannot prove the two agree; [HandlerNamesTest] asserts that every command
 * maps to a name and that every name belongs to a handler that exists. Keep the
 * two files in sync when a command is added.
 */
object HandlerNames {

    private const val VOLUME = "VolumeActionHandler"
    private const val SOUND_MODE = "SoundModeActionHandler"
    private const val MEDIA = "MediaActionHandler"
    private const val MAPS = "MapsActionHandler"
    private const val APP = "AppActionHandler"
    private const val SETTINGS = "SettingsActionHandler"
    private const val UNKNOWN = "UnknownActionHandler"

    /** The handler that will serve [command]. */
    fun forCommand(command: AssistantCommand): String = when (command) {
        is AssistantCommand.VolumeUp,
        is AssistantCommand.VolumeDown,
        is AssistantCommand.SetVolume,
        is AssistantCommand.MuteMedia,
        is AssistantCommand.UnmuteMedia -> VOLUME

        is AssistantCommand.SilentMode,
        is AssistantCommand.VibrateMode,
        is AssistantCommand.NormalRingMode -> SOUND_MODE

        is AssistantCommand.PlayMusic,
        is AssistantCommand.PauseMusic,
        is AssistantCommand.ResumeMusic,
        is AssistantCommand.NextTrack,
        is AssistantCommand.PreviousTrack -> MEDIA

        is AssistantCommand.OpenMaps,
        is AssistantCommand.SearchLocation,
        is AssistantCommand.NavigateTo -> MAPS

        is AssistantCommand.OpenApp -> APP

        is AssistantCommand.OpenSettings,
        is AssistantCommand.OpenWifiSettings,
        is AssistantCommand.OpenBluetoothSettings,
        is AssistantCommand.OpenBatterySettings -> SETTINGS

        is AssistantCommand.Unknown,
        is AssistantCommand.Rejected -> UNKNOWN
    }

    /** Every label this object can produce; used by tests and the docs. */
    val ALL: List<String> = listOf(
        VOLUME, SOUND_MODE, MEDIA, MAPS, APP, SETTINGS, UNKNOWN
    )
}
