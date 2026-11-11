package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — media transport.
 *
 * THE TWO CLAIMS UNDER TEST:
 *  - a pause/resume/skip request is *dispatched* with both halves of one key
 *    press, and the message says "Sent a … request", never "paused";
 *  - "play music" opens a music app and says so, because Phase 6 cannot start
 *    playback of a named track.
 */
class MediaActionHandlerTest {

    private val launcher = FakeLauncher()
    private val keys = FakeMediaKeyController()
    private val handler = MediaActionHandler(launcher, keys)

    // --- Key dispatch ---------------------------------------------------------

    @Test
    fun `pause dispatches the pause key`() = runTest {
        val result = handler.handle(AssistantCommand.PauseMusic)

        assertEquals(listOf(MediaKey.PAUSE), keys.presses)
        assertEquals(ExecutionResult.Success("Sent a pause request to the media app."), result)
    }

    @Test
    fun `resume dispatches the play key`() = runTest {
        val result = handler.handle(AssistantCommand.ResumeMusic)

        assertEquals(listOf(MediaKey.PLAY), keys.presses)
        assertEquals(ExecutionResult.Success("Sent a play request to the media app."), result)
    }

    @Test
    fun `next and previous dispatch their own keys`() = runTest {
        handler.handle(AssistantCommand.NextTrack)
        handler.handle(AssistantCommand.PreviousTrack)

        assertEquals(listOf(MediaKey.NEXT, MediaKey.PREVIOUS), keys.presses)
    }

    @Test
    fun `the message never claims playback actually paused`() = runTest {
        // ARIA cannot observe a player; the wording must stay at "requested".
        val result = handler.handle(AssistantCommand.PauseMusic)

        assertFalse(result.message.contains("paused", ignoreCase = true))
        assertTrue(result.message.contains("request", ignoreCase = true))
    }

    @Test
    fun `a failed dispatch is reported as a failure`() = runTest {
        val deaf = FakeMediaKeyController(accepts = false)

        val result = MediaActionHandler(launcher, deaf).handle(AssistantCommand.PauseMusic)

        assertEquals(ExecutionResult.Failure(ActionMessages.MEDIA_UNAVAILABLE), result)
    }

    @Test
    fun `transport commands never launch an activity`() = runTest {
        handler.handle(AssistantCommand.PauseMusic)
        handler.handle(AssistantCommand.NextTrack)

        assertTrue("Transport is a key press, not a launch", launcher.launched.isEmpty())
    }

    // --- Play music -----------------------------------------------------------

    @Test
    fun `play music opens a music app and keeps the query out of the claim`() = runTest {
        val result = handler.handle(AssistantCommand.PlayMusic("jazz"))

        assertEquals(
            listOf(LaunchSpec.MusicApp("jazz")),
            launcher.launched
        )
        assertEquals(ExecutionResult.Success("Opening your music app."), result)
        // The query travels with the command for the history/debug view, but the
        // message must not imply the song was found or played.
        assertFalse("Must not claim playback", result.message.contains("jazz"))
    }

    @Test
    fun `play music with no query behaves the same`() = runTest {
        val result = handler.handle(AssistantCommand.PlayMusic(""))

        assertEquals(listOf(LaunchSpec.MusicApp("")), launcher.launched)
        assertTrue("$result", result.isSuccess)
    }

    @Test
    fun `no music app installed is NotSupported, not a crash`() = runTest {
        val blocked = FakeLauncher(accepts = false)

        val result = MediaActionHandler(blocked, keys).handle(AssistantCommand.PlayMusic("jazz"))

        assertEquals(ExecutionResult.NotSupported(ActionMessages.NO_MUSIC_APP), result)
    }

    // --- The key press pair (pure) -------------------------------------------

    @Test
    fun `a key press is a down and an up with the same keycode`() {
        val events = MediaKeyPresses.eventsFor(MediaKey.PAUSE)

        assertEquals(2, events.size)
        assertEquals(events[0].keyCode, events[1].keyCode)
        assertTrue("DOWN must come first", events[0].isDown)
        assertFalse("UP must follow", events[1].isDown)
    }

    @Test
    fun `each media key maps to its own platform keycode`() {
        val codes = MediaKey.entries.associateWith { MediaKeyPresses.keyCodeFor(it) }

        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, codes[MediaKey.PAUSE])
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PLAY, codes[MediaKey.PLAY])
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_NEXT, codes[MediaKey.NEXT])
        assertEquals(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, codes[MediaKey.PREVIOUS])
        assertEquals("Two keys share a code", 4, codes.values.toSet().size)
        // MEDIA_PLAY_PAUSE would be the lazy shortcut; it must not be used, because
        // "pause" and "resume" are separate spoken intents.
        assertEquals(
            "Toggle key must not be used",
            false,
            codes.values.contains(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
    }
}
