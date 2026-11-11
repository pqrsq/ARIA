package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — the five media commands.
 *
 * ── TWO KINDS OF "MEDIA" COMMAND, TWO HONESTY LEVELS ────────────────────────
 *
 * **PlayMusic** can only *open a music app*: there is no public API to start
 * playback of a named song without a media session or a provider search. Phase 6
 * therefore launches the user's music app
 * (`Intent.makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_MUSIC)`) and says
 * so — "Opening your music app." — while the requested [AssistantCommand.PlayMusic.query]
 * stays in the command, the history and the debug block. It is deliberately NOT
 * implemented with `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` (deprecated,
 * requires a media provider, and would let ARIA imply it started playback).
 *
 * **Pause/Resume/Next/Previous** dispatch the same media key a headset button
 * sends. That reaches whichever player holds the media session — or nobody —
 * and ARIA cannot tell which. The messages therefore report the *request*:
 * "Sent a pause request to the media app.", never "Music paused."
 *
 * A failed dispatch is a real failure: `dispatchMediaKeyEvent` throwing means no
 * session could be reached, and saying so is more useful than silence.
 */
class MediaActionHandler(
    private val launcher: Launcher,
    private val mediaKeys: MediaKeyController
) {

    suspend fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.PlayMusic -> openMusicApp(command)
        is AssistantCommand.PauseMusic -> press(MediaKey.PAUSE, "pause")
        is AssistantCommand.ResumeMusic -> press(MediaKey.PLAY, "play")
        is AssistantCommand.NextTrack -> press(MediaKey.NEXT, "next track")
        is AssistantCommand.PreviousTrack -> press(MediaKey.PREVIOUS, "previous track")
        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }

    /**
     * Opens a music app.
     *
     * The query is intentionally not part of the intent — see the class KDoc and
     * docs/PHASE6.md's limitations table.
     */
    private suspend fun openMusicApp(command: AssistantCommand.PlayMusic): ExecutionResult {
        val launched = launcher.launch(LaunchSpec.MusicApp(command.query))
        return if (launched) {
            ExecutionResult.Success("Opening your music app.")
        } else {
            ExecutionResult.NotSupported(ActionMessages.NO_MUSIC_APP)
        }
    }

    private fun press(key: MediaKey, spokenName: String): ExecutionResult {
        val dispatched = mediaKeys.press(key)
        return if (dispatched) {
            ExecutionResult.Success("Sent a $spokenName request to the media app.")
        } else {
            ExecutionResult.Failure(ActionMessages.MEDIA_UNAVAILABLE)
        }
    }
}
