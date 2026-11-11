package com.example.aria.actions

/**
 * PHASE 6 — the exact `Settings.ACTION_*` values ARIA opens, in one place.
 *
 * ── WHY CONSTANTS HERE AND NOT `Settings.ACTION_*` AT THE CALL SITE ──────────
 * Two reasons. First, `android.provider.Settings.ACTION_*` are compile-time
 * `String`s, so keeping them in a tiny object still lets a JVM test assert the
 * exact value that will be launched (a typo in a settings action is otherwise
 * invisible until someone taps it). Second, [ExecutionResult.RequiresPermission]
 * needs to *carry* one of these to the UI, and a `String` is the only shape that
 * crosses the Android-free boundary without dragging the framework along.
 *
 * ── VERIFIED VALUES ─────────────────────────────────────────────────────────
 * `ACTION_POWER_USAGE_SUMMARY` is used for the battery screen, not the older
 * `ACTION_BATTERY_SAVER_SETTINGS`: the former is present on every API level ARIA
 * supports (minSdk 26) and shows real per-app usage, while the latter opens a
 * toggle that does not exist on all devices.
 */
object SettingsActions {

    /** Top-level system settings. */
    const val SETTINGS: String = "android.settings.SETTINGS"

    /** Wi-Fi list. */
    const val WIFI: String = "android.settings.WIFI_SETTINGS"

    /** Bluetooth screen. */
    const val BLUETOOTH: String = "android.settings.BLUETOOTH_SETTINGS"

    /** Battery usage. */
    const val BATTERY: String = "android.settings.POWER_USAGE_SUMMARY"

    /**
     * The screen where the user grants Do Not Disturb access.
     *
     * Offered by [ExecutionResult.RequiresPermission] — never launched
     * automatically, so the user always chooses to go there.
     */
    const val NOTIFICATION_POLICY_ACCESS: String =
        "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"
}
