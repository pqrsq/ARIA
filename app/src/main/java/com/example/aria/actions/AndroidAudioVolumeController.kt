package com.example.aria.actions

import android.content.Context
import android.media.AudioManager

/**
 * PHASE 6 — the real [AudioVolumeController], backed by `AudioManager`.
 *
 * Deliberately mechanical: every method is one `AudioManager` call and no
 * decisions are made here. All clamping, rounding, verification and wording live
 * in [VolumeActionHandler] / [VolumeLevelCalculator], which are unit-tested.
 */
class AndroidAudioVolumeController(
    context: Context
) : AudioVolumeController {

    private val applicationContext = context.applicationContext

    private val audioManager: AudioManager
        get() = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun isVolumeFixed(): Boolean = audioManager.isVolumeFixed

    override fun maxIndex(stream: Int): Int = audioManager.getStreamMaxVolume(stream)

    override fun currentIndex(stream: Int): Int = audioManager.getStreamVolume(stream)

    override fun adjust(stream: Int, direction: VolumeDirection) {
        val androidDirection = when (direction) {
            VolumeDirection.RAISE -> AudioManager.ADJUST_RAISE
            VolumeDirection.LOWER -> AudioManager.ADJUST_LOWER
            VolumeDirection.MUTE -> AudioManager.ADJUST_MUTE
            VolumeDirection.UNMUTE -> AudioManager.ADJUST_UNMUTE
        }
        // FLAG_SHOW_UI: a voice command that silently changes the volume looks
        // broken; the system OSD is the only feedback the user gets.
        //
        // A SecurityException (ring volume under Do Not Disturb) is deliberately
        // NOT caught here — see AudioVolumeController.adjust.
        audioManager.adjustStreamVolume(stream, androidDirection, AudioManager.FLAG_SHOW_UI)
    }

    override fun set(stream: Int, index: Int) {
        // Flag 0: no OSD for an absolute set. The result card already says the
        // exact level, and a volume slider popping up over the app is noise.
        audioManager.setStreamVolume(stream, index, 0)
    }
}
