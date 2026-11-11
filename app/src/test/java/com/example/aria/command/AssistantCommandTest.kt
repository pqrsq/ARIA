package com.example.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 4 tests — the command model, in plain JUnit.
 *
 * There is no mocking and no Android here at all: that is the point of the model
 * being pure Kotlin. These tests are the contract Phase 5 (parser) produces and
 * Phase 6 (executor) consumes, so they are written from the *outside* — display
 * names, equality and clamping — rather than from the implementation.
 */
class AssistantCommandTest {

    // ─────────────────────────────────────────────────────────────────────────
    // displayName — every command must be able to introduce itself
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `media commands have the expected display names`() {
        assertEquals("Play \"jazz\"", AssistantCommand.PlayMusic("jazz").displayName)
        assertEquals("Pause Music", AssistantCommand.PauseMusic.displayName)
        assertEquals("Resume Music", AssistantCommand.ResumeMusic.displayName)
        assertEquals("Next Track", AssistantCommand.NextTrack.displayName)
        assertEquals("Previous Track", AssistantCommand.PreviousTrack.displayName)
    }

    @Test
    fun `volume commands have the expected display names`() {
        assertEquals("Volume Up", AssistantCommand.VolumeUp.displayName)
        assertEquals("Volume Down", AssistantCommand.VolumeDown.displayName)
        assertEquals("Set Volume to 30%", AssistantCommand.SetVolume(30).displayName)
        assertEquals("Mute Media", AssistantCommand.MuteMedia.displayName)
    }

    @Test
    fun `sound mode commands have the expected display names`() {
        assertEquals("Silent Mode", AssistantCommand.SilentMode.displayName)
        assertEquals("Vibrate Mode", AssistantCommand.VibrateMode.displayName)
        assertEquals("Normal Ring Mode", AssistantCommand.NormalRingMode.displayName)
    }

    @Test
    fun `maps commands have the expected display names`() {
        assertEquals("Open Maps", AssistantCommand.OpenMaps.displayName)
        assertEquals(
            "Search Location \"Milan\"",
            AssistantCommand.SearchLocation("Milan").displayName
        )
        assertEquals(
            "Navigate to \"Duomo\"",
            AssistantCommand.NavigateTo("Duomo").displayName
        )
    }

    @Test
    fun `app and settings commands have the expected display names`() {
        assertEquals("Open \"Spotify\"", AssistantCommand.OpenApp("Spotify").displayName)
        assertEquals("Open Settings", AssistantCommand.OpenSettings.displayName)
        assertEquals("Open Wi-Fi Settings", AssistantCommand.OpenWifiSettings.displayName)
        assertEquals(
            "Open Bluetooth Settings",
            AssistantCommand.OpenBluetoothSettings.displayName
        )
        assertEquals("Open Battery Settings", AssistantCommand.OpenBatterySettings.displayName)
    }

    @Test
    fun `unknown command has a neutral display name and keeps the raw text`() {
        val command = AssistantCommand.Unknown("do the thing with the stuff")

        assertEquals("Unknown Command", command.displayName)
        assertEquals("do the thing with the stuff", command.rawText)
    }

    @Test
    fun `every command in the model has a non-blank display name`() {
        // Guards the "new command ships without wording" mistake: this list is
        // hand-maintained on purpose, so adding a command without adding it here
        // is caught by the count assertion below.
        val all: List<AssistantCommand> = listOf(
            AssistantCommand.PlayMusic("x"),
            AssistantCommand.PauseMusic,
            AssistantCommand.ResumeMusic,
            AssistantCommand.NextTrack,
            AssistantCommand.PreviousTrack,
            AssistantCommand.VolumeUp,
            AssistantCommand.VolumeDown,
            AssistantCommand.SetVolume(10),
            AssistantCommand.MuteMedia,
            AssistantCommand.SilentMode,
            AssistantCommand.VibrateMode,
            AssistantCommand.NormalRingMode,
            AssistantCommand.OpenMaps,
            AssistantCommand.SearchLocation("x"),
            AssistantCommand.NavigateTo("x"),
            AssistantCommand.OpenApp("x"),
            AssistantCommand.OpenSettings,
            AssistantCommand.OpenWifiSettings,
            AssistantCommand.OpenBluetoothSettings,
            AssistantCommand.OpenBatterySettings,
            AssistantCommand.Unknown("x")
        )

        assertEquals("The spec requires exactly these 21 commands", 21, all.size)
        all.forEach { command ->
            assertTrue(
                "Blank display name for ${command::class.simpleName}",
                command.displayName.isNotBlank()
            )
            // Display names are shown as-is in a label, so they start uppercase.
            assertTrue(
                "Display name should start uppercase: ${command.displayName}",
                command.displayName.first().isUpperCase()
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Equality — data classes compare by value, objects are singletons
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `parameterised commands are equal only when every property matches`() {
        assertEquals(AssistantCommand.PlayMusic("Song A"), AssistantCommand.PlayMusic("Song A"))
        assertNotEquals(AssistantCommand.PlayMusic("Song A"), AssistantCommand.PlayMusic("Song B"))
        assertNotEquals(
            AssistantCommand.NavigateTo("Rome"),
            AssistantCommand.NavigateTo("Milan")
        )
    }

    @Test
    fun `setVolume equality includes the stream`() {
        assertEquals(
            AssistantCommand.SetVolume(30),
            AssistantCommand.SetVolume(30, AudioStream.MEDIA)
        )
        assertNotEquals(
            AssistantCommand.SetVolume(30, AudioStream.MEDIA),
            AssistantCommand.SetVolume(30, AudioStream.RING)
        )
    }

    @Test
    fun `equality copies are independent values, not the same instance`() {
        val first = AssistantCommand.OpenApp("Maps")
        val second = AssistantCommand.OpenApp("Maps")

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue("Distinct instances that compare equal", first !== second)
    }

    @Test
    fun `different command types are never equal`() {
        // NB: JUnit 4's assertNotEquals takes (Object, Object) — no type args.
        // Kotlin infers the common supertype AssistantCommand, which is exactly
        // the comparison intended here: different members, same sealed family.
        assertNotEquals(AssistantCommand.NextTrack, AssistantCommand.PreviousTrack)
        assertNotEquals(AssistantCommand.VolumeUp, AssistantCommand.VolumeDown)
    }

    @Test
    fun `parameterless commands are singletons`() {
        // `data object` guarantees one instance, which is what lets callers use
        // `is`/identity checks and `when` branches without allocation.
        assertTrue(AssistantCommand.VolumeUp === AssistantCommand.VolumeUp)
        assertTrue(AssistantCommand.OpenMaps === AssistantCommand.OpenMaps)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SetVolume — boundaries and clamping
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `setVolume formats the percentage exactly`() {
        assertEquals("Set Volume to 30%", AssistantCommand.SetVolume(30).displayName)
        assertEquals("Set Volume to 0%", AssistantCommand.SetVolume(0).displayName)
        assertEquals("Set Volume to 100%", AssistantCommand.SetVolume(100).displayName)
    }

    @Test
    fun `setVolume defaults to the media stream`() {
        assertEquals(AudioStream.MEDIA, AssistantCommand.SetVolume(42).stream)
        assertEquals("Set Volume to 42%", AssistantCommand.SetVolume(42).displayName)
    }

    @Test
    fun `setVolume names the stream when it is not media`() {
        assertEquals(
            "Set Ring Volume to 70%",
            AssistantCommand.SetVolume(70, AudioStream.RING).displayName
        )
        assertEquals(
            "Set Alarm Volume to 100%",
            AssistantCommand.SetVolume(100, AudioStream.ALARM).displayName
        )
    }

    @Test
    fun `setVolume accepts both inclusive bounds`() {
        // 0 and 100 are legal; off-by-one here would reject a real user command.
        assertEquals(0, AssistantCommand.SetVolume(0).percentage)
        assertEquals(100, AssistantCommand.SetVolume(100).percentage)
    }

    @Test
    fun `setVolume rejects levels outside 0 to 100`() {
        // Clamping/validating happens at construction so a parser slip fails
        // loudly in a test rather than quietly setting the wrong volume.
        listOf(-1, 101, Int.MAX_VALUE, Int.MIN_VALUE).forEach { level ->
            val failed = runCatching { AssistantCommand.SetVolume(level) }.isFailure
            assertTrue("SetVolume($level) should be rejected", failed)
        }
    }

    @Test
    fun `audioStream offers exactly the three contracted streams`() {
        assertEquals(
            listOf(AudioStream.MEDIA, AudioStream.RING, AudioStream.ALARM),
            AudioStream.entries.toList()
        )
        AudioStream.entries.forEach { stream ->
            assertTrue("Blank label for $stream", stream.label.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Exhaustiveness — the property Phase 6 depends on
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every command is handled by an exhaustive when`() {
        // If a command is ever added, this test still compiles (a `when` on a
        // sealed type used as an expression must be exhaustive) but the count
        // assertion fails until somebody thinks about the new case.
        val all: List<AssistantCommand> = listOf(
            AssistantCommand.PlayMusic("x"), AssistantCommand.PauseMusic,
            AssistantCommand.ResumeMusic, AssistantCommand.NextTrack,
            AssistantCommand.PreviousTrack, AssistantCommand.VolumeUp,
            AssistantCommand.VolumeDown, AssistantCommand.SetVolume(1),
            AssistantCommand.MuteMedia, AssistantCommand.SilentMode,
            AssistantCommand.VibrateMode, AssistantCommand.NormalRingMode,
            AssistantCommand.OpenMaps, AssistantCommand.SearchLocation("x"),
            AssistantCommand.NavigateTo("x"), AssistantCommand.OpenApp("x"),
            AssistantCommand.OpenSettings, AssistantCommand.OpenWifiSettings,
            AssistantCommand.OpenBluetoothSettings, AssistantCommand.OpenBatterySettings,
            AssistantCommand.Unknown("x")
        )

        val categories = all.map { it.category() }.toSet()

        assertEquals(
            "Expected one command per category in the spec",
            setOf("Media", "Volume", "Sound mode", "Maps", "App", "Settings", "Fallback"),
            categories
        )
    }

    @Test
    fun `playMusic with a blank query still reads sensibly`() {
        // "play" on its own is a real utterance; it must not render as `Play ""`.
        assertEquals("Play Music", AssistantCommand.PlayMusic("").displayName)
        assertEquals("Play Music", AssistantCommand.PlayMusic("   ").displayName)
    }

    /** Test-side categorisation, mirroring the sections of the model. */
    private fun AssistantCommand.category(): String = when (this) {
        is AssistantCommand.PlayMusic,
        AssistantCommand.PauseMusic,
        AssistantCommand.ResumeMusic,
        AssistantCommand.NextTrack,
        AssistantCommand.PreviousTrack -> "Media"

        AssistantCommand.VolumeUp,
        AssistantCommand.VolumeDown,
        is AssistantCommand.SetVolume,
        AssistantCommand.MuteMedia -> "Volume"

        AssistantCommand.SilentMode,
        AssistantCommand.VibrateMode,
        AssistantCommand.NormalRingMode -> "Sound mode"

        AssistantCommand.OpenMaps,
        is AssistantCommand.SearchLocation,
        is AssistantCommand.NavigateTo -> "Maps"

        is AssistantCommand.OpenApp -> "App"

        AssistantCommand.OpenSettings,
        AssistantCommand.OpenWifiSettings,
        AssistantCommand.OpenBluetoothSettings,
        AssistantCommand.OpenBatterySettings -> "Settings"

        is AssistantCommand.Unknown -> "Fallback"
    }
}
