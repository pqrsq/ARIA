package com.example.aria.actions

import android.media.AudioManager
import com.example.aria.command.AssistantCommand
import com.example.aria.command.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — volume, mute, and the three honesty rules around them.
 *
 * WHAT IS BEING PROVEN (not merely exercised):
 *  1. the right stream and the right `ADJUST_*` direction are requested,
 *  2. a fixed-volume device is refused instead of lied about,
 *  3. every message reports the level that was **read back**, so a device that
 *     ignores the change produces a failure, not a false success,
 *  4. the stream mapping honours RING and ALARM as well as MEDIA.
 */
class VolumeActionHandlerTest {

    private val volume = FakeVolumeController()
    private val handler = VolumeActionHandler(volume)

    // --- Direction and stream -------------------------------------------------

    @Test
    fun `volume up raises the media stream by one step`() {
        val result = handler.handle(AssistantCommand.VolumeUp)

        assertTrue("$result", result.isSuccess)
        assertTrue(volume.calls.contains("adjust(${AudioManager.STREAM_MUSIC},RAISE)"))
        // 8 -> 9 of 15 is 60%.
        assertEquals(ExecutionResult.Success("Media volume up to 60%."), result)
    }

    @Test
    fun `volume down lowers the media stream by one step`() {
        val result = handler.handle(AssistantCommand.VolumeDown)

        assertTrue("$result", result.isSuccess)
        assertTrue(volume.calls.contains("adjust(${AudioManager.STREAM_MUSIC},LOWER)"))
        assertEquals(ExecutionResult.Success("Media volume down to 47%."), result)
    }

    @Test
    fun `volume up at the ceiling reports the maximum instead of pretending to climb`() {
        val atMax = FakeVolumeController(currentIndexValue = 15)

        val result = VolumeActionHandler(atMax).handle(AssistantCommand.VolumeUp)

        assertEquals(ExecutionResult.Success("Media volume is at maximum."), result)
        assertEquals("Nothing louder exists", 15, atMax.currentIndexValue)
    }

    @Test
    fun `volume down at zero reports the minimum`() {
        val atZero = FakeVolumeController(currentIndexValue = 0)

        val result = VolumeActionHandler(atZero).handle(AssistantCommand.VolumeDown)

        assertEquals(ExecutionResult.Success("Media volume is at minimum."), result)
    }

    @Test
    fun `a device that ignores the change produces a failure, never a success`() {
        // The single most important assertion in this file: `AudioManager` gives
        // no return value, so optimism is the natural bug.
        val deaf = FakeVolumeController(currentIndexValue = 8, honoursChanges = false)

        val result = VolumeActionHandler(deaf).handle(AssistantCommand.VolumeUp)

        assertFalse("$result", result.isSuccess)
        // PHASE 6.5 (A1): the sentence is unchanged; the reason is now typed.
        assertEquals(
            ExecutionResult.Failure("Media volume did not change.", FailureReason.NOT_SUPPORTED),
            result
        )
    }

    // --- Fixed volume ---------------------------------------------------------

    @Test
    fun `a fixed-volume device is refused before anything is changed`() {
        val fixed = FakeVolumeController(fixed = true)

        val result = VolumeActionHandler(fixed).handle(AssistantCommand.VolumeUp)

        assertTrue("$result", result is ExecutionResult.NotSupported)
        assertEquals(ActionMessages.VOLUME_FIXED, result.message)
        // Not one mutating call was made — the refusal comes first.
        assertFalse(
            "A fixed volume must not be adjusted anyway",
            fixed.calls.any { it.startsWith("adjust(") || it.startsWith("set(") }
        )
    }

    @Test
    fun `a fixed volume also refuses an absolute set`() {
        val fixed = FakeVolumeController(fixed = true)

        val result = VolumeActionHandler(fixed).handle(
            AssistantCommand.SetVolume(30, AudioStream.MEDIA)
        )

        assertTrue("$result", result is ExecutionResult.NotSupported)
        assertFalse(fixed.calls.any { it.startsWith("set(") })
    }

    // --- Absolute set ---------------------------------------------------------

    @Test
    fun `setting a percentage converts to a stream index and reads back`() {
        val result = handler.handle(AssistantCommand.SetVolume(40, AudioStream.MEDIA))

        // 40% of 15 = 6 exactly.
        assertTrue(volume.calls.contains("set(${AudioManager.STREAM_MUSIC},6)"))
        assertEquals(ExecutionResult.Success("Media volume is now 40%."), result)
    }

    @Test
    fun `setting zero percent really silences the stream`() {
        val result = handler.handle(AssistantCommand.SetVolume(0, AudioStream.MEDIA))

        assertTrue(volume.calls.contains("set(${AudioManager.STREAM_MUSIC},0)"))
        assertEquals(ExecutionResult.Success("Media volume is now 0%."), result)
    }

    @Test
    fun `setting one hundred percent reaches the maximum index`() {
        val result = handler.handle(AssistantCommand.SetVolume(100, AudioStream.MEDIA))

        assertTrue(volume.calls.contains("set(${AudioManager.STREAM_MUSIC},15)"))
        assertEquals(ExecutionResult.Success("Media volume is now 100%."), result)
    }

    @Test
    fun `a refused absolute set reports the level actually in effect`() {
        val deaf = FakeVolumeController(currentIndexValue = 9, honoursChanges = false)

        val result = VolumeActionHandler(deaf).handle(
            AssistantCommand.SetVolume(20, AudioStream.MEDIA)
        )

        // Requested 20%, still at 60%: the user must be told the truth.
        assertEquals(
            ExecutionResult.Failure(
                "Media volume could not be set to 20%; it is 60%.",
                FailureReason.INVALID_PARAMETER
            ),
            result
        )
    }

    // --- Streams --------------------------------------------------------------

    @Test
    fun `the ring stream is targeted for ring volume`() {
        val result = handler.handle(AssistantCommand.SetVolume(50, AudioStream.RING))

        assertTrue(volume.calls.contains("set(${AudioManager.STREAM_RING},8)"))
        assertEquals(ExecutionResult.Success("Ring volume is now 53%."), result)
    }

    @Test
    fun `the alarm stream is targeted for alarm volume`() {
        val result = handler.handle(AssistantCommand.SetVolume(50, AudioStream.ALARM))

        assertTrue(volume.calls.contains("set(${AudioManager.STREAM_ALARM},8)"))
        assertTrue("$result", result.message.startsWith("Alarm volume"))
    }

    @Test
    fun `the default stream is media, not ring`() {
        // SetVolume's default parameter is MEDIA; a handler that guessed RING
        // would silence calls when the user asked for music.
        handler.handle(AssistantCommand.SetVolume(10))

        assertTrue(volume.calls.contains("set(${AudioManager.STREAM_MUSIC},2)"))
    }

    // --- Mute -----------------------------------------------------------------

    @Test
    fun `mute media uses the mute direction and confirms silence`() {
        val result = handler.handle(AssistantCommand.MuteMedia)

        assertTrue(volume.calls.contains("adjust(${AudioManager.STREAM_MUSIC},MUTE)"))
        assertEquals(ExecutionResult.Success("Media muted."), result)
    }

    @Test
    fun `a refused mute is a failure`() {
        val deaf = FakeVolumeController(currentIndexValue = 6, honoursChanges = false)

        val result = VolumeActionHandler(deaf).handle(AssistantCommand.MuteMedia)

        assertEquals(
            ExecutionResult.Failure("Media could not be muted.", FailureReason.INVALID_PARAMETER),
            result
        )
    }

    @Test
    fun `muting never touches the ringer stream`() {
        handler.handle(AssistantCommand.MuteMedia)

        assertFalse(
            "Mute media must not silence calls",
            volume.calls.any { it.contains("${AudioManager.STREAM_RING},") }
        )
    }
}
