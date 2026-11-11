package com.example.aria.actions

/**
 * PHASE 6 — the ringer profile, in domain terms.
 *
 * Mirrors `AudioManager.RINGER_MODE_*` but lives here so
 * [SoundModeActionHandler] can be tested without `android.media.AudioManager`.
 * The mapping is one `when` in [AndroidRingerController].
 */
enum class RingerMode {
    /** `RINGER_MODE_SILENT` — no ringtone, no vibration. */
    SILENT,

    /** `RINGER_MODE_VIBRATE` — no ringtone, haptics on. */
    VIBRATE,

    /** `RINGER_MODE_NORMAL` — rings out loud. */
    NORMAL
}

/**
 * PHASE 6 — the two operations a sound-mode change needs.
 *
 * ── WHY READ-BACK IS PART OF THIS SEAM ──────────────────────────────────────
 * `setRingerMode` is a *request*, and the system silently ignores it when a Do Not
 * Disturb policy forbids the transition. Without [currentRingerMode] ARIA could
 * say "Silent mode is on" while the phone still rings — precisely what the Phase 6
 * rules forbid. The *permission* question is a separate port
 * ([com.example.aria.permissions.PolicyAccessChecker]) because it is a different
 * system service and must never be answered optimistically.
 */
interface RingerController {

    /** Requests a profile; the system may decline (see [currentRingerMode]). */
    fun setRingerMode(mode: RingerMode)

    /** The profile the device is actually in, read back after the request. */
    fun currentRingerMode(): RingerMode
}
