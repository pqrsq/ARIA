package com.example.aria.actions

import android.content.Context
import com.example.aria.permissions.DndAccessChecker

/**
 * PHASE 6 — builds the real executor from an Application context.
 *
 * ── WHY A FACTORY FUNCTION AND NOT MORE CONSTRUCTOR ARGUMENTS ───────────────
 * The graph is: one Application context → six thin Android adapters → seven
 * handlers → one executor. Writing that by hand at the call site would put a page
 * of wiring inside `AriaApplication`; keeping it here means the wiring lives next
 * to the classes it wires and can be read once.
 *
 * ── WHEN IT RUNS ────────────────────────────────────────────────────────────
 * Called lazily by [com.example.aria.AriaApplication] the first time a command is
 * executed, so a cold start that never speaks pays nothing. Every adapter holds
 * the **application** context, so nothing here can keep an Activity alive.
 *
 * ── TESTABILITY ─────────────────────────────────────────────────────────────
 * Unit tests never call this function: they build the handlers they need with
 * fakes (see `actions/AndroidActionExecutorTest` and the per-handler tests). That is the whole
 * reason the handlers take ports instead of a `Context`.
 */
fun createAndroidActionExecutor(context: Context): AndroidActionExecutor {
    val launcher = AndroidLauncher(context)
    // Cached: "open <app>" is repeated often and the PackageManager query walks
    // every installed package. See CachingInstalledAppsProvider for the staleness
    // trade-off.
    val apps = CachingInstalledAppsProvider(AndroidInstalledAppsProvider(context))
    val mediaKeys = AndroidMediaKeyController(context)

    return AndroidActionExecutor(
        volumeHandler = VolumeActionHandler(AndroidAudioVolumeController(context)),
        soundModeHandler = SoundModeActionHandler(
            ringer = AndroidRingerController(context),
            policy = DndAccessChecker.create(context)
        ),
        mediaHandler = MediaActionHandler(launcher = launcher, mediaKeys = mediaKeys),
        mapsHandler = MapsActionHandler(launcher = launcher),
        appHandler = AppActionHandler(apps = apps, launcher = launcher),
        settingsHandler = SettingsActionHandler(launcher = launcher),
        unknownHandler = UnknownActionHandler()
    )
}
