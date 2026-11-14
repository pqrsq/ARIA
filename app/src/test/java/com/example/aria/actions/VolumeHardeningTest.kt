package com.example.aria.actions

import android.media.AudioManager
import com.example.aria.command.AssistantCommand
import com.example.aria.command.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6.5 — volume hardening: D9 (read-back retry), D8 (SecurityException on
 * the ring stream) and A3 (unmute).
 *
 * The fake controller's `honoursChanges`/`applyAfterReads` knobs exist precisely
 * so "the device is slow" and "the device refuses" can be told apart — the two
 * cases that a naive implementation confuses.
 */
class VolumeHardeningTest {

    /**
     * A controller whose changes only become visible after [applyAfterReads]
     * reads — the asynchronous-apply behaviour of some vendor volume services.
     */
    private class SlowVolumeController(
        private var currentValue: Int,
        private val applyAfterReads: Int
    ) : AudioVolumeController {

        private var pending: Int? = null
        private var readsSinceRequest = 0

        override fun isVolumeFixed(): Boolean = false
        override fun maxIndex(stream: Int): Int = 15

        override fun currentIndex(stream: Int): Int {
            val queued = pending
            if (queued != null && readsSinceRequest >= applyAfterReads) {
                currentValue = queued
                pending = null
            }
            readsSinceRequest++
            return currentValue
        }

        override fun adjust(stream: Int, direction: VolumeDirection) {
            pending = when (direction) {
                VolumeDirection.RAISE -> currentValue + 1
                VolumeDirection.LOWER -> currentValue - 1
                VolumeDirection.MUTE -> 0
                VolumeDirection.UNMUTE -> if (currentValue == 0) 1 else currentValue
            }
            readsSinceRequest = 0
        }

        override fun set(stream: Int, index: Int) {
            pending = index
            readsSinceRequest = 0
        }
    }

    /** Records how many times the retry loop slept. */
    private class SleepRecorder {
        var sleeps: Int = 0
        var lastDelay: Long = 0
        val sleeper: (Long) -> Unit = { millis ->
            sleeps++
            lastDelay = millis
        }
    }

    // ── D9 — read-back retry ─────────────────────────────────────────────────

    @Test
    fun `a slow device that applies the change late is reported as success`() {
        // THE REGRESSION: with a single immediate read this was a false failure.
        val slow = SlowVolumeController(currentValue = 8, applyAfterReads = 2)
        val handler = VolumeActionHandler(slow, sleeper = { })

        val result = handler.handle(AssistantCommand.VolumeUp)

        assertTrue("$result should be a success", result.isSuccess)
        assertEquals(ExecutionResult.Success("Media volume up to 60%."), result)
    }

    @Test
    fun `a device that never applies the change still fails`() {
        // THE HONESTY LIMIT: retrying must not invent a success where the device
        // genuinely refused (EU safe-volume dialog, policy, fixed volume).
        val deaf = FakeVolumeController(currentIndexValue = 8, honoursChanges = false)
        val handler = VolumeActionHandler(deaf, sleeper = { })

        val result = handler.handle(AssistantCommand.VolumeUp)

        assertEquals(ExecutionResult.Failure("Media volume did not change.", FailureReason.NOT_SUPPORTED), result)
    }

    @Test
    fun `the retry loop is bounded and uses the configured delay`() {
        val recorder = SleepRecorder()
        val deaf = FakeVolumeController(currentIndexValue = 8, honoursChanges = false)

        VolumeActionHandler(
            deaf,
            readBackAttempts = 3,
            readBackDelayMillis = 50L,
            sleeper = recorder.sleeper
        ).handle(AssistantCommand.VolumeUp)

        // 3 attempts = 2 waits. A loop that slept after the last attempt would be
        // wasting 50 ms on every genuine failure.
        assertEquals(2, recorder.sleeps)
        assertEquals(50L, recorder.lastDelay)
    }

    @Test
    fun `a value that matches immediately costs no delay`() {
        val recorder = SleepRecorder()
        val quick = FakeVolumeController(currentIndexValue = 8)

        VolumeActionHandler(quick, sleeper = recorder.sleeper)
            .handle(AssistantCommand.VolumeUp)

        assertEquals("No retry when it worked first time", 0, recorder.sleeps)
    }

    @Test
    fun `a slow read-back also rescues an absolute set`() {
        val slow = SlowVolumeController(currentValue = 8, applyAfterReads = 2)
        val handler = VolumeActionHandler(slow, sleeper = { })

        val result = handler.handle(AssistantCommand.SetVolume(40, AudioStream.MEDIA))

        // 40% of a 15-step stream is index 6 (40% * 15 = 6.0).
        assertEquals(ExecutionResult.Success("Media volume is now 40%."), result)
    }

    // ── D8 — ring volume under Do Not Disturb ────────────────────────────────

    /**
     * Throws the way the platform does for a DND-protected stream, while leaving
     * every other stream fully working — that asymmetry is the point of D8.
     */
    private class DndBlockingController(
        private val blockingStream: Int
    ) : AudioVolumeController {

        private var index = 4

        override fun isVolumeFixed(): Boolean = false
        override fun maxIndex(stream: Int): Int = 7
        override fun currentIndex(stream: Int): Int = index

        override fun adjust(stream: Int, direction: VolumeDirection) {
            if (stream == blockingStream) throw SecurityException("policy access required")
            index = when (direction) {
                VolumeDirection.RAISE -> (index + 1).coerceAtMost(7)
                VolumeDirection.LOWER -> (index - 1).coerceAtLeast(0)
                VolumeDirection.MUTE -> 0
                VolumeDirection.UNMUTE -> if (index == 0) 1 else index
            }
        }

        override fun set(stream: Int, index: Int) {
            if (stream == blockingStream) throw SecurityException("policy access required")
            this.index = index
        }
    }

    @Test
    fun `a refused ring adjustment becomes a permission request`() {
        // BEFORE: the SecurityException reached the executor and became a generic
        // "Something went wrong", which tells the user nothing actionable.
        val handler = VolumeActionHandler(
            DndBlockingController(AudioManager.STREAM_RING),
            sleeper = { }
        )

        val result = handler.handle(AssistantCommand.SetVolume(40, AudioStream.RING))

        assertTrue("$result", result is ExecutionResult.RequiresPermission)
        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            (result as ExecutionResult.RequiresPermission).settingsIntentAction
        )
        assertEquals(ActionMessages.RING_VOLUME_BLOCKED, result.message)
    }

    @Test
    fun `only the blocked stream produces a permission request`() {
        // The mapping must be per-stream, not a blanket refusal: media still works
        // on a device whose ring stream is locked by DND.
        val handler = VolumeActionHandler(DndBlockingController(AudioManager.STREAM_RING), sleeper = { })

        val mediaResult = handler.handle(AssistantCommand.VolumeUp)

        assertFalse("$mediaResult", mediaResult is ExecutionResult.RequiresPermission)
        assertTrue("$mediaResult", mediaResult.isSuccess)
    }

    // ── A3 — unmute ──────────────────────────────────────────────────────────

    @Test
    fun `unmute uses the unmute direction and reports the level read back`() {
        val fake = FakeVolumeController(currentIndexValue = 0)

        val result = VolumeActionHandler(fake, sleeper = { })
            .handle(AssistantCommand.UnmuteMedia)

        assertTrue(fake.calls.contains("adjust(${AudioManager.STREAM_MUSIC},UNMUTE)"))
        assertTrue("$result", result.isSuccess)
    }

    @Test
    fun `unmuting an already-audible stream does not claim the level rose`() {
        // LIMITATION, stated in the message: ADJUST_UNMUTE alone does not raise an
        // unmuted stream, so the honest wording is "already at N%".
        val fake = FakeVolumeController(currentIndexValue = 8)

        val result = VolumeActionHandler(fake, sleeper = { })
            .handle(AssistantCommand.UnmuteMedia)

        assertEquals(ExecutionResult.Success("Media is already at 53%."), result)
    }

    @Test
    fun `a refused unmute is a failure, never a success`() {
        val deaf = FakeVolumeController(currentIndexValue = 0, honoursChanges = false)

        val result = VolumeActionHandler(deaf, sleeper = { })
            .handle(AssistantCommand.UnmuteMedia)

        assertEquals(
            ExecutionResult.Failure("Media could not be unmuted.", FailureReason.INVALID_PARAMETER),
            result
        )
    }

    @Test
    fun `a slow unmute is confirmed by the retry`() {
        val slow = SlowVolumeController(currentValue = 0, applyAfterReads = 2)
        val handler = VolumeActionHandler(slow, sleeper = { })

        val result = handler.handle(AssistantCommand.UnmuteMedia)

        assertTrue("$result", result.isSuccess)
    }
}
