package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — the router: one exhaustive `when` from command to handler.
 *
 * ── WHY A ROUTER INSTEAD OF PUTTING `when` IN EACH HANDLER ──────────────────
 * Each handler owns one *area* (volume, sound modes, media, maps, apps,
 * settings, fallback) and should not know the other areas exist. The router is
 * the only class that knows the whole map, so adding a command means: add it to
 * [AssistantCommand], add a branch here (the compiler demands it), and add a
 * branch to [HandlerNames] (asserted by test).
 *
 * ── THREADING ───────────────────────────────────────────────────────────────
 * [execute] is suspending and does no thread switching of its own: the caller
 * decides. [com.example.aria.ui.AssistantViewModel] runs it on `Dispatchers.IO`,
 * which keeps system-service calls and `startActivity` off the main thread —
 * required because a slow `PackageManager` query on the main thread is an ANR,
 * and `dispatchMediaKeyEvent` can block behind a busy player.
 *
 * ── ERROR CONTAINMENT ───────────────────────────────────────────────────────
 * A vendor `AudioManager`/`PackageManager` throwing must not crash the app from a
 * background thread. Every dispatch is wrapped, and an unexpected exception
 * becomes a truthful [ExecutionResult.Failure] rather than a crash. The specific
 * exceptions handlers already expect (`ActivityNotFoundException`,
 * `SecurityException`) are caught closer to where they happen, so they get
 * better wording.
 */
class AndroidActionExecutor(
    private val volumeHandler: VolumeActionHandler,
    private val soundModeHandler: SoundModeActionHandler,
    private val mediaHandler: MediaActionHandler,
    private val mapsHandler: MapsActionHandler,
    private val appHandler: AppActionHandler,
    private val settingsHandler: SettingsActionHandler,
    private val unknownHandler: UnknownActionHandler
) : ActionExecutor {

    override suspend fun execute(command: AssistantCommand): ExecutionResult =
        try {
            route(command)
        } catch (error: RuntimeException) {
            // Deliberately broad: this is the outermost boundary of the action
            // layer, and no failure here may reach the user as a crash. The
            // exception is not swallowed silently — the result says something
            // went wrong, and the message is specific enough to act on.
            ExecutionResult.Failure(ActionMessages.UNEXPECTED)
        }

    /** The exhaustive routing table. Kept separate so [execute] stays readable. */
    internal suspend fun route(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.VolumeUp,
        is AssistantCommand.VolumeDown,
        is AssistantCommand.SetVolume,
        is AssistantCommand.MuteMedia -> volumeHandler.handle(command)

        is AssistantCommand.SilentMode,
        is AssistantCommand.VibrateMode,
        is AssistantCommand.NormalRingMode -> soundModeHandler.handle(command)

        is AssistantCommand.PlayMusic,
        is AssistantCommand.PauseMusic,
        is AssistantCommand.ResumeMusic,
        is AssistantCommand.NextTrack,
        is AssistantCommand.PreviousTrack -> mediaHandler.handle(command)

        is AssistantCommand.OpenMaps,
        is AssistantCommand.SearchLocation,
        is AssistantCommand.NavigateTo -> mapsHandler.handle(command)

        is AssistantCommand.OpenApp -> appHandler.handle(command)

        is AssistantCommand.OpenSettings,
        is AssistantCommand.OpenWifiSettings,
        is AssistantCommand.OpenBluetoothSettings,
        is AssistantCommand.OpenBatterySettings -> settingsHandler.handle(command)

        is AssistantCommand.Unknown -> unknownHandler.handle(command)
    }
}
