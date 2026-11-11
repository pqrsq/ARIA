package com.example.aria.command

/**
 * PHASE 4 — the audio stream a volume command applies to.
 *
 * WHY THIS ENUM EXISTS
 * Android expresses streams as integer constants on `AudioManager`
 * (`STREAM_MUSIC`, `STREAM_RING`, …). Putting those in the command model would
 * make it untestable on the JVM and would drag `android.*` into logic that must
 * stay pure. The command therefore speaks in these three domain values, and the
 * `ActionExecutor` (Phase 6) does the single mapping to Android constants in one
 * place:
 *
 * ```
 * AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
 * AudioStream.RING  -> AudioManager.STREAM_RING
 * AudioStream.ALARM -> AudioManager.STREAM_ALARM
 * ```
 *
 * Three streams, not all of them: Version 1 controls what a user actually asks
 * for out loud (media, ring, alarm). Call/notification/system streams are
 * deliberately out of scope until someone needs them.
 */
enum class AudioStream(
    /** Human-readable form, used inside command display names. */
    val label: String
) {
    /** Music, podcasts, video — the default for "set the volume". */
    MEDIA("Media"),

    /** Ringer volume, i.e. how loud an incoming call rings. */
    RING("Ring"),

    /** Clock alarms and timers. Kept separate because silencing it is dangerous. */
    ALARM("Alarm")
}
