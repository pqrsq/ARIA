package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — the stable identifier a command gets in the history and debug views.
 *
 * ── WHY NOT `displayName` ────────────────────────────────────────────────────
 * [AssistantCommand.displayName] is prose meant for a human ("Set Volume to
 * 40%"); it changes whenever the wording is polished. The history row wants a
 * short, greppable id that survives rewording and can later be persisted or
 * filtered on — "VOLUME_DOWN", exactly the shape the Phase 2 documentation
 * promised.
 *
 * ── WHY DERIVED FROM THE CLASS NAME INSTEAD OF A `when` ─────────────────────
 * A hand-written mapping would be a *third* exhaustive switch over the command
 * model (after the executor router and [HandlerNames]), and the compiler could
 * not prove the three agree. Deriving from `simpleName` removes that
 * duplication entirely: no command can be added without getting an id, because
 * every command is a class. The class names are part of the sealed interface's
 * published shape, so renaming one is already a deliberate, visible change.
 *
 * `VolumeDown` → `VOLUME_DOWN`, `SetVolume` → `SET_VOLUME`,
 * `OpenWifiSettings` → `OPEN_WIFI_SETTINGS`.
 */
object CommandTypeNaming {

    /** Fallback for a JVM where `simpleName` is unavailable (obfuscation). */
    const val FALLBACK = "UNKNOWN_COMMAND"

    /** The history/debug id for [command]. */
    fun of(command: AssistantCommand): String {
        val simpleName = command::class.simpleName ?: return FALLBACK
        return toUpperSnakeCase(simpleName)
    }

    /**
     * `CamelCase` → `UPPER_SNAKE_CASE`.
     *
     * Pure and tested separately, because the `simpleName` half cannot be
     * exercised with an arbitrary string.
     */
    fun toUpperSnakeCase(name: String): String {
        if (name.isEmpty()) return FALLBACK
        val out = StringBuilder(name.length + 4)
        name.forEachIndexed { index, char ->
            if (char.isUpperCase() && index > 0) out.append('_')
            out.append(char.uppercaseChar())
        }
        return out.toString()
    }
}
