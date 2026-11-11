package com.example.aria.actions

import kotlin.math.roundToInt

/**
 * PHASE 6 — the arithmetic between "a percentage a human said" and "the integer
 * index `AudioManager` wants".
 *
 * ── WHY THIS IS ITS OWN PURE OBJECT ──────────────────────────────────────────
 * `AudioManager` reports volume as a stream-dependent index (often 0..15, 0..7
 * or 0..100 — it differs per device and per stream), while the command model
 * speaks in percentages. That conversion is the only part of volume handling
 * worth getting exactly right, and it must be provable without a device: a
 * `volume up` that lands one step off is very hard to notice by hand and trivial
 * to assert here.
 *
 * Rounding is `roundToInt` (half up), then clamped, exactly as the Phase 6 spec
 * requires: 50% of a 15-step stream is 7.5 → 8, never 7 and never an
 * out-of-range index.
 */
object VolumeLevelCalculator {

    /**
     * Converts a 0..100 percentage to a stream index.
     *
     * @param maxIndex the stream's maximum index, as reported by the device.
     *   A value of 0 or less (a device that reports nonsense, or a stream with no
     *   steps at all) yields 0 rather than a division by zero.
     */
    fun indexForPercentage(percentage: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        val clamped = percentage.coerceIn(0, 100)
        return (clamped / 100.0 * maxIndex).roundToInt().coerceIn(0, maxIndex)
    }

    /**
     * Converts a stream index back to a 0..100 percentage, for the read-back
     * message ("Media volume up to 40%.").
     *
     * The inverse is deliberately *not* guaranteed to be exact — devices with a
     * 15-step stream cannot express every percentage — which is why handlers
     * always report the value they read back instead of echoing the request.
     */
    fun percentageForIndex(index: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        return (index.coerceIn(0, maxIndex) * 100.0 / maxIndex).roundToInt().coerceIn(0, 100)
    }
}
