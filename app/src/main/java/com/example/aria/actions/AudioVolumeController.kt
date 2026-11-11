package com.example.aria.actions

/**
 * PHASE 6 — the four things ARIA ever asks of the volume system.
 *
 * ── WHY AN INTERFACE ─────────────────────────────────────────────────────────
 * `AudioManager` is a system service: in a plain JVM unit test its methods are
 * stubs that return 0, so a handler wired straight to it could never be proven to
 * (a) read back the volume it claims to have set, or (b) refuse to report success
 * when the device ignores the change. Both are honesty requirements, so both must
 * be testable. [AndroidAudioVolumeController] is the only implementation that
 * touches Android, and it is deliberately thinner than the handler.
 *
 * ── WHAT IT IS NOT ───────────────────────────────────────────────────────────
 * It performs no arithmetic (that is [VolumeLevelCalculator]) and decides no
 * wording (that is the handler). It is a recorder-shaped seam.
 */
interface AudioVolumeController {

    /** True when the device keeps the volume fixed (some TVs, kiosk builds). */
    fun isVolumeFixed(): Boolean

    /** The stream's maximum index as reported by the device (often 0..15). */
    fun maxIndex(stream: Int): Int

    /** The stream's current index, for the read-back message. */
    fun currentIndex(stream: Int): Int

    /** One step up, one step down, or mute — the `ADJUST_*` directions. */
    fun adjust(stream: Int, direction: VolumeDirection)

    /** Absolute set, clamped by the caller, with no UI shown by ARIA. */
    fun set(stream: Int, index: Int)
}

/** The `AudioManager.ADJUST_*` directions ARIA uses. */
enum class VolumeDirection {
    RAISE,
    LOWER,
    MUTE
}
