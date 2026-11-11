package com.example.aria.actions

import com.example.aria.command.AudioStream
import com.example.aria.permissions.PolicyAccessChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — the fakes every action test shares, plus the arithmetic that keeps
 * the volume handler honest.
 *
 * WHY REAL FAKES (not mockito): the project has no mocking dependency and these
 * seams are tiny. A fake also lets a test script the *awkward* device behaviours
 * that matter — a volume change the system ignores, a ringer mode that refuses to
 * become silent — which is exactly what the honesty rules are about.
 */

/** Records every volume call and lets a test decide how the device responds. */
class FakeVolumeController(
    var fixed: Boolean = false,
    var maxIndexValue: Int = 15,
    var currentIndexValue: Int = 8,
    /**
     * When false, `set`/`adjust` are accepted but change nothing — the silent
     * refusal real devices exhibit under a policy.
     */
    var honoursChanges: Boolean = true
) : AudioVolumeController {

    /** One entry per call, in order, for asserting the exact request sequence. */
    val calls: MutableList<String> = mutableListOf()

    override fun isVolumeFixed(): Boolean {
        calls += "isVolumeFixed"
        return fixed
    }

    override fun maxIndex(stream: Int): Int {
        calls += "maxIndex($stream)"
        return maxIndexValue
    }

    override fun currentIndex(stream: Int): Int {
        calls += "currentIndex($stream)"
        return currentIndexValue
    }

    override fun adjust(stream: Int, direction: VolumeDirection) {
        calls += "adjust($stream,${direction.name})"
        if (!honoursChanges) return
        when (direction) {
            VolumeDirection.RAISE -> currentIndexValue =
                (currentIndexValue + 1).coerceAtMost(maxIndexValue)
            VolumeDirection.LOWER -> currentIndexValue =
                (currentIndexValue - 1).coerceAtLeast(0)
            VolumeDirection.MUTE -> currentIndexValue = 0
        }
    }

    override fun set(stream: Int, index: Int) {
        calls += "set($stream,$index)"
        if (honoursChanges) currentIndexValue = index.coerceIn(0, maxIndexValue)
    }
}

/** Records the requested mode and reports whatever the test says is in effect. */
class FakeRingerController(
    var mode: RingerMode = RingerMode.NORMAL,
    /** When false, `setRingerMode` is a no-op — a policy refusing the change. */
    var honoursChanges: Boolean = true
) : RingerController {

    val requestedModes: MutableList<RingerMode> = mutableListOf()

    override fun setRingerMode(mode: RingerMode) {
        requestedModes += mode
        if (honoursChanges) this.mode = mode
    }

    override fun currentRingerMode(): RingerMode = mode
}

/** Answers the Do-Not-Disturb question however the test wants. */
class FakePolicyAccessChecker(var granted: Boolean = true) : PolicyAccessChecker {
    override fun canChangeSoundModes(): Boolean = granted
}

/** Records launches; `accepts` decides whether anything opens. */
class FakeLauncher(
    var accepts: Boolean = true
) : Launcher {

    val launched: MutableList<LaunchSpec> = mutableListOf()

    override suspend fun launch(spec: LaunchSpec): Boolean {
        launched += spec
        return accepts
    }
}

/** Records media key presses; `accepts` decides whether the dispatch works. */
class FakeMediaKeyController(
    var accepts: Boolean = true
) : MediaKeyController {

    val presses: MutableList<MediaKey> = mutableListOf()

    override fun press(key: MediaKey): Boolean {
        presses += key
        return accepts
    }
}

/** Serves a fixed app list. */
class FakeInstalledAppsProvider(
    var apps: List<AppEntry> = emptyList()
) : InstalledAppsProvider {
    /** Counts queries, so the caching wrapper can be proven to query once. */
    var queryCount: Int = 0
        private set

    override suspend fun launcherApps(): List<AppEntry> {
        queryCount++
        return apps
    }
}

/**
 * The pure percentage ↔ index conversion.
 *
 * VALUES CHOSEN FROM REAL DEVICES: 15 steps (Pixel media), 7 steps (many ringer
 * streams), 16 and 100 (unusual but legal). These are the cases where naive
 * integer division silently lands one step off.
 */
class VolumeLevelCalculatorTest {

    @Test
    fun `zero percent is index zero on every stream`() {
        listOf(7, 15, 16, 100).forEach { max ->
            assertEquals("max $max", 0, VolumeLevelCalculator.indexForPercentage(0, max))
        }
    }

    @Test
    fun `one hundred percent is the maximum index on every stream`() {
        listOf(7, 15, 16, 100).forEach { max ->
            assertEquals("max $max", max, VolumeLevelCalculator.indexForPercentage(100, max))
        }
    }

    @Test
    fun `fifty percent rounds up rather than truncating`() {
        // 50% of 15 is 7.5: truncation would give 7 (46%), rounding gives 8.
        assertEquals(8, VolumeLevelCalculator.indexForPercentage(50, 15))
        // 50% of 7 is 3.5 -> 4, not 3.
        assertEquals(4, VolumeLevelCalculator.indexForPercentage(50, 7))
    }

    @Test
    fun `a percentage above one hundred is clamped to the maximum`() {
        assertEquals(15, VolumeLevelCalculator.indexForPercentage(150, 15))
    }

    @Test
    fun `a negative percentage is clamped to zero`() {
        assertEquals(0, VolumeLevelCalculator.indexForPercentage(-40, 15))
    }

    @Test
    fun `a stream with no steps yields zero instead of dividing by zero`() {
        assertEquals(0, VolumeLevelCalculator.indexForPercentage(50, 0))
        assertEquals(0, VolumeLevelCalculator.indexForPercentage(50, -3))
    }

    @Test
    fun `the index always stays inside the stream range`() {
        // Exhaustive over the domain the command model allows: no percentage may
        // ever produce an out-of-range index, which would throw at runtime.
        val max = 13
        (0..100).forEach { percent ->
            val index = VolumeLevelCalculator.indexForPercentage(percent, max)
            assertTrue("$percent% -> $index", index in 0..max)
        }
    }

    @Test
    fun `percentage read-back inverts the conversion`() {
        assertEquals(0, VolumeLevelCalculator.percentageForIndex(0, 15))
        assertEquals(100, VolumeLevelCalculator.percentageForIndex(15, 15))
        // Index 8 of 15 is 53.3% -> 53.
        assertEquals(53, VolumeLevelCalculator.percentageForIndex(8, 15))
    }

    @Test
    fun `read-back clamps a nonsensical index from a vendor service`() {
        assertEquals(100, VolumeLevelCalculator.percentageForIndex(99, 15))
        assertEquals(0, VolumeLevelCalculator.percentageForIndex(-5, 15))
        assertEquals(0, VolumeLevelCalculator.percentageForIndex(3, 0))
    }

    @Test
    fun `round-tripping a percentage stays within one step of the request`() {
        // The inverse cannot be exact on a 15-step stream; it must still be close
        // enough that "set volume to 40%" reads back as ~40%, not 33% or 47%.
        val max = 15
        (0..100 step 5).forEach { percent ->
            val index = VolumeLevelCalculator.indexForPercentage(percent, max)
            val back = VolumeLevelCalculator.percentageForIndex(index, max)
            assertTrue(
                "$percent% came back as $back%",
                kotlin.math.abs(back - percent) <= 100 / max + 1
            )
        }
    }

    @Test
    fun `the audio stream mapping covers all three domain values`() {
        // The mapping is the one place domain values meet Android constants.
        val media = AudioStreamMapping.toAndroidStream(AudioStream.MEDIA)
        val ring = AudioStreamMapping.toAndroidStream(AudioStream.RING)
        val alarm = AudioStreamMapping.toAndroidStream(AudioStream.ALARM)

        assertEquals(android.media.AudioManager.STREAM_MUSIC, media)
        assertEquals(android.media.AudioManager.STREAM_RING, ring)
        assertEquals(android.media.AudioManager.STREAM_ALARM, alarm)
        // Three distinct streams: a copy-paste slip would make two equal.
        assertEquals(3, setOf(media, ring, alarm).size)
    }

    @Test
    fun `the volume controller records the exact call it was asked for`() {
        // Guards the fake itself: handler tests trust this recording.
        val fake = FakeVolumeController()
        fake.adjust(android.media.AudioManager.STREAM_MUSIC, VolumeDirection.RAISE)

        assertEquals(listOf("adjust(3,RAISE)"), fake.calls)
        assertEquals(9, fake.currentIndexValue)
    }

    @Test
    fun `a fake that ignores changes really reports the unchanged value`() {
        // The other half of the contract: this is how "the device refused" is
        // simulated in the handler tests.
        val fake = FakeVolumeController(currentIndexValue = 8, honoursChanges = false)
        fake.adjust(3, VolumeDirection.RAISE)

        assertEquals(8, fake.currentIndexValue)
        assertFalse(fake.fixed)
    }
}
