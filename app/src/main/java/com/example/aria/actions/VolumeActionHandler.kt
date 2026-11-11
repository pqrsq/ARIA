package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import com.example.aria.command.AudioStream

/**
 * PHASE 6 — volume commands: up, down, absolute, and mute-media.
 *
 * ── WHY ALL FOUR LIVE TOGETHER ───────────────────────────────────────────────
 * They share one pre-condition (`isVolumeFixed`), one stream mapping and one
 * read-back rule, and splitting them would duplicate those three things four
 * times. `when` stays exhaustive, so a new volume command cannot slip through.
 *
 * ── THE HONESTY RULE BAKED INTO THIS CLASS ──────────────────────────────────
 * `AudioManager` gives no return value for `setStreamVolume` /
 * `adjustStreamVolume`; a change can be silently refused (a DND policy, a fixed
 * volume, an OEM quirk). Every branch therefore **reads the volume back** and
 * reports the value that is actually in effect. If nothing moved, it says so
 * instead of claiming success. That is why the messages say "Media volume is now
 * 40%", never "Volume set to 40%".
 *
 * ── STREAM MAPPING ──────────────────────────────────────────────────────────
 * `MEDIA → STREAM_MUSIC`, `RING → STREAM_RING`, `ALARM → STREAM_ALARM`, via
 * [AudioStreamMapping] — the single translation point promised by [AudioStream].
 */
class VolumeActionHandler(
    private val volume: AudioVolumeController
) {

    /** Executes a volume command; the caller has already matched the type. */
    fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.VolumeUp -> adjust(AudioStream.MEDIA, VolumeDirection.RAISE)
        is AssistantCommand.VolumeDown -> adjust(AudioStream.MEDIA, VolumeDirection.LOWER)
        is AssistantCommand.MuteMedia -> adjust(AudioStream.MEDIA, VolumeDirection.MUTE)
        is AssistantCommand.SetVolume -> setVolume(command.percentage, command.stream)
        // Defensive: the executor routes only these four here. Returning
        // NotSupported rather than throwing keeps a routing slip from crashing.
        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }

    private fun adjust(stream: AudioStream, direction: VolumeDirection): ExecutionResult {
        if (volume.isVolumeFixed()) {
            return ExecutionResult.NotSupported(ActionMessages.VOLUME_FIXED)
        }

        val androidStream = AudioStreamMapping.toAndroidStream(stream)
        val max = volume.maxIndex(androidStream)
        val before = volume.currentIndex(androidStream)

        volume.adjust(androidStream, direction)

        val after = volume.currentIndex(androidStream)
        val label = AudioStreamMapping.label(stream)
        val percent = VolumeLevelCalculator.percentageForIndex(after, max)

        return when (direction) {
            VolumeDirection.MUTE ->
                if (after == 0) {
                    ExecutionResult.Success("$label muted.")
                } else {
                    // The device refused or partially honoured the mute.
                    ExecutionResult.Failure("$label could not be muted.")
                }

            VolumeDirection.RAISE ->
                // At maximum, "volume up" did what it could: report the ceiling
                // instead of pretending it went louder.
                if (after >= max) {
                    ExecutionResult.Success("$label volume is at maximum.")
                } else if (after == before) {
                    ExecutionResult.Failure("$label volume did not change.")
                } else {
                    ExecutionResult.Success("$label volume up to $percent%.")
                }

            VolumeDirection.LOWER ->
                if (after == 0) {
                    ExecutionResult.Success("$label volume is at minimum.")
                } else if (after == before) {
                    ExecutionResult.Failure("$label volume did not change.")
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
        // roundToInt().coerceIn(0, max) — the Phase 6 spec, in one place, pure and
        // tested by VolumeLevelCalculatorTest. The command model already clamps
        // 0..100; this clamps to the *stream*.
        val target = VolumeLevelCalculator.indexForPercentage(percentage, max)

        volume.set(androidStream, target)

        val after = volume.currentIndex(androidStream)
        val label = AudioStreamMapping.label(stream)
        val actualPercent = VolumeLevelCalculator.percentageForIndex(after, max)

        return if (after == target) {
            ExecutionResult.Success("$label volume is now $actualPercent%.")
        } else {
            // Read-back disagreed with the request: the device declined. Say the
            // real level — a user who asked for 20% and got 60% must be told.
            ExecutionResult.Failure(
                "$label volume could not be set to ${percentage.coerceIn(0, 100)}%; " +
                    "it is $actualPercent%."
            )
        }
    }
}
