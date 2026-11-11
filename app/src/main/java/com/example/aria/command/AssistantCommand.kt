package com.example.aria.command

/**
 * PHASE 4 — the strongly-typed vocabulary of everything ARIA will ever do.
 *
 * ── WHY THIS MODEL EXISTS ────────────────────────────────────────────────────
 * The parser (Phase 5) turns a recognised `String` into one of these values; the
 * `ActionExecutor` (Phase 6) turns one of these values into an Android call. The
 * middle of the pipeline is therefore *typed*, which buys three things:
 *
 *  1. **Impossible states are unrepresentable.** `SetVolume` always carries a
 *     percentage; `Unknown` always carries the text that failed. There is no
 *     "volume command with no amount" bug to write.
 *  2. **The compiler enforces completeness.** Every `when` over this type must
 *     handle every category — adding `BluetoothOff` later breaks the build until
 *     the executor handles it, instead of silently falling through.
 *  3. **Everything is testable without a device.** This file imports no
 *     `android.*` at all, so every command can be constructed and asserted in
 *     plain JUnit.
 *
 * ── WHY A `sealed interface` AND NOT A `sealed class` ────────────────────────
 * The members share no implementation: no state, no behaviour, no constructor
 * chain. A sealed *class* would imply a common base with something in it, and
 * would also invite subclassing games that add nothing. An interface says
 * exactly what is true — "these are the shapes a command can take, and they are
 * all in this file" — and leaves each member free to be a `data class` (with
 * parameters) or a `data object` (without).
 *
 * `displayName` is the only member, because every command must be able to
 * introduce itself to the UI and the debug log.
 *
 * ── WHAT THIS MODEL IS NOT ───────────────────────────────────────────────────
 * It holds no Android types, performs no side effects, and knows nothing about
 * how or when a command is executed. `SetVolume(30)` is a *description* of a
 * wish; Phase 6 is what grants it.
 */
sealed interface AssistantCommand {

    /**
     * Short, human-readable label for the UI and the debug block.
     *
     * WHY A PROPERTY AND NOT A `when` IN THE UI: the wording then lives next to
     * the data it describes, so a new command cannot ship without one, and the
     * UI stays free of a second exhaustive switch.
     */
    val displayName: String

    // ─────────────────────────────────────────────────────────────────────────
    // MEDIA — playback control. Phase 8 will implement it.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start playing something. [query] is whatever the user said, not a resolved
     * track: resolving it (or handing it to a provider) is Phase 8's problem.
     * An empty query means "just play", not "play nothing" — the parser is
     * responsible for deciding that before constructing the command.
     */
    data class PlayMusic(val query: String) : AssistantCommand {
        override val displayName: String
            get() = if (query.isBlank()) "Play Music" else "Play \"$query\""
    }

    /** Stop playback, keeping the queue so [ResumeMusic] can continue it. */
    data object PauseMusic : AssistantCommand {
        override val displayName: String get() = "Pause Music"
    }

    /** Continue a paused queue. */
    data object ResumeMusic : AssistantCommand {
        override val displayName: String get() = "Resume Music"
    }

    /** Skip to the next track. */
    data object NextTrack : AssistantCommand {
        override val displayName: String get() = "Next Track"
    }

    /** Go back to the previous track. */
    data object PreviousTrack : AssistantCommand {
        override val displayName: String get() = "Previous Track"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VOLUME — level control on a specific stream. Phase 7 will implement it.
    // ─────────────────────────────────────────────────────────────────────────

    /** One step louder on the media stream. */
    data object VolumeUp : AssistantCommand {
        override val displayName: String get() = "Volume Up"
    }

    /** One step quieter on the media stream. */
    data object VolumeDown : AssistantCommand {
        override val displayName: String get() = "Volume Down"
    }

    /**
     * Set an absolute level.
     *
     * [percentage] is normalised to 0..100 by [init] rather than trusted, so a
     * parser slip (`SetVolume(150)`) becomes a clamped command instead of an
     * `IllegalArgumentException` triggered by user speech. Clamping is a product
     * decision: shouting "volume 150" should give maximum volume, not a crash.
     */
    data class SetVolume(
        val percentage: Int,
        val stream: AudioStream = AudioStream.MEDIA
    ) : AssistantCommand {

        init {
            require(percentage in 0..100) {
                "Volume must be 0..100, was $percentage"
            }
        }

        override val displayName: String
            get() = if (stream == AudioStream.MEDIA) {
                // The common case deserves the short wording.
                "Set Volume to $percentage%"
            } else {
                "Set ${stream.label} Volume to $percentage%"
            }
    }

    /**
     * Silence the media stream (volume to zero) without touching the ringer.
     *
     * WHY NOT `SetVolume(0)`: they will map to the same Android call in Phase 6,
     * but "mute the music" and "set volume to zero" are different *intents*, and
     * the history should show what the user meant. Explicit beats inferred.
     */
    data object MuteMedia : AssistantCommand {
        override val displayName: String get() = "Mute Media"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SOUND MODES — the ringer profile. Phase 11 will implement it (and will
    // need Do Not Disturb access on Samsung devices).
    // ─────────────────────────────────────────────────────────────────────────

    /** Silent: no ringtone, no vibration. */
    data object SilentMode : AssistantCommand {
        override val displayName: String get() = "Silent Mode"
    }

    /** Vibrate only: no ringtone, haptics on. */
    data object VibrateMode : AssistantCommand {
        override val displayName: String get() = "Vibrate Mode"
    }

    /** Back to ringing out loud with vibration allowed. */
    data object NormalRingMode : AssistantCommand {
        override val displayName: String get() = "Normal Ring Mode"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MAPS — navigation intents. Phase 9 will implement it.
    // ─────────────────────────────────────────────────────────────────────────

    /** Open the maps app with no particular destination. */
    data object OpenMaps : AssistantCommand {
        override val displayName: String get() = "Open Maps"
    }

    /** Search maps for a place without starting turn-by-turn navigation. */
    data class SearchLocation(val query: String) : AssistantCommand {
        override val displayName: String get() = "Search Location \"$query\""
    }

    /** Start navigation to [destination]. */
    data class NavigateTo(val destination: String) : AssistantCommand {
        override val displayName: String get() = "Navigate to \"$destination\""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APPS — launching. Phase 10 will implement it, including the "app not
    // installed" case this model cannot express yet.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launch an installed app by the name the user said.
     *
     * [appName] is raw speech, not a package id: Phase 10 resolves it against
     * the installed-app list and reports back if nothing matches.
     */
    data class OpenApp(val appName: String) : AssistantCommand {
        override val displayName: String get() = "Open \"$appName\""
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SETTINGS — system screens. Cheap to implement, so these land early.
    // ─────────────────────────────────────────────────────────────────────────

    /** The top-level system settings screen. */
    data object OpenSettings : AssistantCommand {
        override val displayName: String get() = "Open Settings"
    }

    data object OpenWifiSettings : AssistantCommand {
        override val displayName: String get() = "Open Wi-Fi Settings"
    }

    data object OpenBluetoothSettings : AssistantCommand {
        override val displayName: String get() = "Open Bluetooth Settings"
    }

    data object OpenBatterySettings : AssistantCommand {
        override val displayName: String get() = "Open Battery Settings"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FALLBACK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The parser recognised speech but no command it trusts.
     *
     * WHY THIS IS A COMMAND AND NOT AN EXCEPTION: "I did not understand that" is
     * a normal, expected outcome of talking to a machine — not an error state.
     * Keeping it in the model means the executor and the history have exactly one
     * path to handle it, and [rawText] preserves what was actually said so the
     * debug view can explain the miss.
     */
    data class Unknown(val rawText: String) : AssistantCommand {
        override val displayName: String get() = "Unknown Command"
    }
}
