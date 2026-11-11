package com.example.aria.actions

import android.content.Context
import android.media.AudioManager

/**
 * PHASE 6 — the real [RingerController].
 *
 * ── ONE SYSTEM SERVICE, ONE JOB ─────────────────────────────────────────────
 * Only `AudioManager`: it holds the ringer mode and reports the mode actually in
 * effect. Whether the change is *permitted* is a different service and a
 * different port (see [com.example.aria.permissions.PolicyAccessChecker]).
 *
 * ── WHY NOT `setInterruptionFilter()` ────────────────────────────────────────
 * The Phase 6 rules forbid it, and the reason is worth recording: setting an
 * interruption filter demands the same DND policy access, buys nothing extra,
 * and *changes the meaning of the command* — it would put the phone into Do Not
 * Disturb instead of silencing the ringer, muting alarms and calls the user never
 * asked to mute. `setRingerMode` is the narrow, correct API here.
 */
class AndroidRingerController(
    context: Context
) : RingerController {

    private val applicationContext = context.applicationContext

    private val audioManager: AudioManager
        get() = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun setRingerMode(mode: RingerMode) {
        audioManager.ringerMode = when (mode) {
            RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
            RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        }
    }

    override fun currentRingerMode(): RingerMode = when (audioManager.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }
}
