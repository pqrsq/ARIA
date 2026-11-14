package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import com.example.aria.command.AudioStream

/**
 * PHASE 6 — volume commands: up, down, absolute, mute and (6.5) unmute.
 *
 * ── WHY ALL FIVE LIVE TOGETHER ───────────────────────────────────────────────
 * They share one pre-condition (`isVolumeFixed`), one stream mapping, one
 * read-back rule and (since 6.5) one security-exception rule. Splitting them
 * would duplicate those four things five times. `when` stays exhaustive, so a new
 * volume command cannot slip through.
 *
 * ── THE HONESTY RULE BAKED INTO THIS CLASS ──────────────────────────────────
 * `AudioManager` gives no return value for `setStreamVolume` /
 * `adjustStreamVolume`; a change can be silently refused (a policy, a fixed
 * volume, an OEM quirk). Every branch therefore **reads the volume back** and
 * reports the value that is actually in effect. If nothing moved, it says so
 * instead of claiming success.
 *
 * ── PHASE 6.5 HARDENING ─────────────────────────────────────────────────────
 *  - **D9 — read-back retry.** Some devices apply a volume change asynchronously,
 *    so an immediate read can report the *old* level and turn a real success into
 *    a false failure. The read-back is retried (default 3 attempts, ~50 ms apart)
 *    and the first value that matches the request wins. The sleep and the retry
 *    count are injected, so the tests drive them without wall-clock waiting.
 *  - **HONESTY LIMIT (D9).** Retrying fixes *asynchronous apply*, not *refusal*.
 *    The EU safe-volume dialog, a DND policy or a fixed volume mean the level
 *    genuinely has not changed yet — those must keep failing, and they do.
 *  - **D8 — ring volume under Do Not Disturb.** The platform throws
 *    `SecurityException` for `STREAM_RING` adjustments when DND is active. Rather
 *    than pre-checking policy access (which would over-restrict the many devices
 *    where DND is off), the exception is caught here and mapped to
 *    [ExecutionResult.RequiresPermission] with the exact settings screen.
 *  - **A3 — unmute.** `ADJUST_UNMUTE` restores the level the stream was muted
 *    from, and the result is verified by read-back like everything else.
 */
class VolumeActionHandler(
    private val volume: AudioVolumeController,
    /**
     * How many times a read-back is attempted before concluding the change did
     * not happen. Phase 6.5 (D9): 1 immediate read + 2 retries = 3 attempts.
     */
    private val readBackAttempts: Int = 3,
    /** Delay between attempts, in milliseconds (D9: ~50 ms). */
    private val readBackDelayMillis: Long = 50L,
    /**
     * Suspends for [millis]. Injected so unit tests do not sleep: a JVM test
     * passes `{}` and the retry loop runs instantly while still exercising the
     * same code path.
     */
    private val sleeper: (Long) -> Unit = { millis -> Thread.sleep(millis) }
) {

    /** Executes a volume command; the caller has already matched the type. */
    fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.VolumeUp -> adjust(AudioStream.MEDIA, VolumeDirection.RAISE)
        is AssistantCommand.VolumeDown -> adjust(AudioStream.MEDIA, VolumeDirection.LOWER)
        is AssistantCommand.MuteMedia -> adjust(AudioStream.MEDIA, VolumeDirection.MUTE)
        is AssistantCommand.UnmuteMedia -> adjust(AudioStream.MEDIA, VolumeDirection.UNMUTE)
        is AssistantCommand.SetVolume -> setVolume(command.percentage, command.stream)
        // Defensive: the executor routes only these here. Returning NotSupported
        // rather than throwing keeps a routing slip from crashing.
        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }

    private fun adjust(stream: AudioStream, direction: VolumeDirection): ExecutionResult {
        if (volume.isVolumeFixed()) {
            return ExecutionResult.NotSupported(ActionMessages.VOLUME_FIXED)
        }

        val androidStream = AudioStreamMapping.toAndroidStream(stream)
        val max = volume.maxIndex(androidStream)
        val before = volume.currentIndex(androidStream)

        // PHASE 6.5 (D8): a refused ring-volume change is a permission problem,
        // not a mystery failure. Caught here because only this layer knows which
        // settings screen would let the user fix it.
        try {
            volume.adjust(androidStream, direction)
        } catch (security: SecurityException) {
            return ExecutionResult.RequiresPermission(
                message = ActionMessages.RING_VOLUME_BLOCKED,
                settingsIntentAction = SettingsActions.NOTIFICATION_POLICY_ACCESS
            )
        }

        val label = AudioStreamMapping.label(stream)

        // Each direction has a different definition of "it worked".
        val expected: (Int) -> Boolean = when (direction) {
            VolumeDirection.MUTE -> { index -> index == 0 }
            VolumeDirection.UNMUTE -> { index -> index > 0 }
            VolumeDirection.RAISE -> { index -> index != before }
            VolumeDirection.LOWER -> { index -> index != before }
        }

        val after = readBack(androidStream, expected)
        val percent = VolumeLevelCalculator.percentageForIndex(after, max)

        return when (direction) {
            VolumeDirection.MUTE ->
                if (after == 0) {
                    ExecutionResult.Success("$label muted.")
                } else {
                    ExecutionResult.Failure(
                        "$label could not be muted.",
                        FailureReason.INVALID_PARAMETER
                    )
                }

            VolumeDirection.UNMUTE ->
                // LIMITATION (documented): if the stream was not muted, the index
                // is already > 0 and ADJUST_UNMUTE alone does not raise it. That
                // is reported as a success only because "not muted" *is* the state
                // the user asked for — the message never claims the level rose.
                if (after > 0) {
                    ExecutionResult.Success(
                        if (after == before) "$label is already at $percent%."
                        else "$label unmuted at $percent%."
                    )
                } else {
                    ExecutionResult.Failure(
                        "$label could not be unmuted.",
                        FailureReason.INVALID_PARAMETER
                    )
                }

            VolumeDirection.RAISE ->
                // At maximum, "volume up" did what it could: report the ceiling
                // instead of pretending it went louder.
                if (after >= max) {
                    ExecutionResult.Success("$label volume is at maximum.")
                } else if (after == before) {
                    ExecutionResult.Failure(
                        "$label volume did not change.",
                        FailureReason.NOT_SUPPORTED
                    )
                } else {
                    ExecutionResult.Success("$label volume up to $percent%.")
                }

            VolumeDirection.LOWER ->
                if (after == 0) {
                    ExecutionResult.Success("$label volume is at minimum.")
                } else if (after == before) {
                    ExecutionResult.Failure(
                        "$label volume did not change.",
                        FailureReason.NOT_SUPPORTED
                    )
                } else {
                    ExecutionResult.Success("$label volume down to $percent%.")
                }
        }
    }

    private fun setVolume(percentage: Int, stream: AudioStream): ExecutionResult {
        if (volume.isVolumeFixed()) {
            return ExecutionResult.NotSupported(ActionMessages.VOLUME_FIXED)
        }

        val androidStream = AudioStreamMapping.toAndroidStream(stream)
        val max = volume.maxIndex(androidStream)
        // roundToInt().coerceIn(0, max) — in one pure, tested place. The command
        // model already guarantees 0..100 (Phase 6.5 D11 refuses rather than
        // clamps); this clamps to the *stream*.
        val target = VolumeLevelCalculator.indexForPercentage(percentage, max)

        try {
            volume.set(androidStream, target)
        } catch (security: SecurityException) {
            return ExecutionResult.RequiresPermission(
                message = ActionMessages.RING_VOLUME_BLOCKED,
                settingsIntentAction = SettingsActions.NOTIFICATION_POLICY_ACCESS
            )
        }

        val label = AudioStreamMapping.label(stream)
        // D9: an asynchronous device may still report the old index for a moment.
        val after = readBack(androidStream) { index -> index == target }
        val actualPercent = VolumeLevelCalculator.percentageForIndex(after, max)

        return if (after == target) {
            ExecutionResult.Success("$label volume is now $actualPercent%.")
        } else {
            // Read-back disagreed with the request: the device declined. Say the
            // real level — a user who asked for 20% and got 60% must be told.
            ExecutionResult.Failure(
                "$label volume could not be set to $percentage%; it is $actualPercent%.",
                FailureReason.INVALID_PARAMETER
            )
        }
    }

    /**
     * Reads the stream's index, retrying while [matches] is false (D9).
     *
     * Returns the **last** value read, so a caller that gives up reports the real
     * state rather than the value it hoped for. The final attempt is not followed
     * by a sleep, so a failing read costs at most `attempts - 1` delays.
     */
    private fun readBack(stream: Int, matches: (Int) -> Boolean): Int {
        var latest = volume.currentIndex(stream)
        if (matches(latest)) return latest

        var attempt = 1
        while (attempt < readBackAttempts) {
            sleeper(readBackDelayMillis)
            latest = volume.currentIndex(stream)
            if (matches(latest)) return latest
            attempt++
        }
        return latest
    }

}
