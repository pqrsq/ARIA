package com.example.aria.actions

/**
 * PHASE 6 — what ARIA is about to hand to another app, described without any
 * Android type.
 *
 * ── WHY NOT BUILD `Intent`S IN THE HANDLERS ──────────────────────────────────
 * The interesting part of "navigate to X" is the *URI* — `google.navigation:q=…`
 * versus `geo:0,0?q=…` — and getting it wrong is a silent bug: the map app opens
 * at the wrong screen and nothing throws. Constructing a real `Intent` in a unit
 * test is not possible on a plain JVM (the framework classes are stubs), so the
 * URI would go untested. Handlers therefore emit a [LaunchSpec] — plain data —
 * and tests assert the exact URI string. The single translation to `Intent`
 * lives in [AndroidLauncher], where it can be read in one screenful.
 *
 * ── HONESTY ─────────────────────────────────────────────────────────────────
 * A spec is a *request to open something*. [Launcher.launch] returning true
 * means "an activity accepted this", never "the action happened": ARIA cannot
 * see whether the map app actually started navigating. Handler messages are
 * worded accordingly ("Opening …", never "Navigated").
 */
sealed interface LaunchSpec {

    /** `ACTION_VIEW` on an explicit URI. The workhorse. */
    data class ViewUri(val uri: String) : LaunchSpec

    /**
     * Open *some* music app via `Intent.makeMainSelectorActivity(ACTION_MAIN,
     * CATEGORY_APP_MUSIC)`.
     *
     * WHY NOT A SEARCH INTENT: `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`
     * is deprecated, needs a media provider, and would let ARIA claim it started
     * playback it cannot observe. Selecting the music *app* is the honest
     * ceiling for Phase 6; [query] is carried for the day a real media session
     * can honour it.
     */
    data class MusicApp(val query: String) : LaunchSpec

    /**
     * A system settings screen, identified by its exact `Settings.ACTION_*`
     * value. Kept as a `String` so no `android.provider.Settings` import leaks
     * into testable code — the constants are compile-time strings.
     */
    data class SettingsScreen(val action: String) : LaunchSpec

    /** Launch an installed app by package, resolved through `PackageManager`. */
    data class AppPackage(val packageName: String) : LaunchSpec
}

/**
 * The one Android touchpoint for [LaunchSpec]s.
 *
 * Returns `false` when nothing accepted the request (an
 * `ActivityNotFoundException` in the real implementation), so callers can say
 * "no map app is installed" instead of claiming success.
 */
fun interface Launcher {
    suspend fun launch(spec: LaunchSpec): Boolean
}
