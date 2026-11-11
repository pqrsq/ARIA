package com.example.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 5 tests — the rule-based parser, in plain JUnit.
 *
 * Grouped by the contract they pin: what must match, what must NOT match, and
 * what must reach the command untouched (entities, capitalisation, numbers).
 * The negative tests are the important half: this parser would rather return
 * [AssistantCommand.Unknown] than guess, so "must be Unknown" is a real
 * requirement, not an absence of testing.
 *
 * No device, no Android, no mocking — the parser is pure.
 */
class RuleBasedCommandParserTest {

    private val parser: CommandParser = RuleBasedCommandParser()

    /** Asserts the parser returns [expected] exactly. */
    private fun assertParses(input: String, expected: AssistantCommand) {
        assertEquals("Parsing \"$input\"", expected, parser.parse(input))
    }

    /** Asserts the parser declines to interpret [input], keeping the raw text. */
    private fun assertUnknown(input: String) {
        val result = parser.parse(input)
        assertTrue("\"$input\" should be Unknown, was $result", result is AssistantCommand.Unknown)
        assertEquals("Unknown must keep the raw text", input, result.rawText)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // A. POSITIVE MATCHING — at least three variations per command family
    // ═════════════════════════════════════════════════════════════════════════

    // --- Volume direction ----------------------------------------------------

    @Test
    fun `turn the volume down variants all give VolumeDown`() {
        listOf(
            "turn the volume down",
            "lower the volume",
            "make it quieter",
            "turn it down",
            "volume down",
            "reduce the volume",
            "drop the volume"
        ).forEach { assertParses(it, AssistantCommand.VolumeDown) }
    }

    @Test
    fun `turn the volume up variants all give VolumeUp`() {
        listOf(
            "turn the volume up",
            "increase the volume",
            "make it louder",
            "turn it up",
            "volume up",
            "raise the volume",
            "boost the volume"
        ).forEach { assertParses(it, AssistantCommand.VolumeUp) }
    }

    @Test
    fun `make it quieter and louder are not confused`() {
        assertParses("make the phone quieter", AssistantCommand.VolumeDown)
        assertParses("make the phone louder", AssistantCommand.VolumeUp)
    }

    // --- Mute media ----------------------------------------------------------

    @Test
    fun `mute media variants`() {
        listOf(
            "mute",
            "mute the media",
            "silence the media",
            "mute the music",
            "mute the volume"
        ).forEach { assertParses(it, AssistantCommand.MuteMedia) }
    }

    // --- Set volume ----------------------------------------------------------

    @Test
    fun `set volume recognises the documented phrasings`() {
        assertParses("set the volume to 30 percent", AssistantCommand.SetVolume(30))
        assertParses("set volume to 30", AssistantCommand.SetVolume(30))
        assertParses("volume 30", AssistantCommand.SetVolume(30))
        assertParses("set media volume to 50", AssistantCommand.SetVolume(50))
    }

    @Test
    fun `volume level of zero and one hundred are accepted exactly`() {
        assertParses("volume 0", AssistantCommand.SetVolume(0))
        assertParses("volume 100", AssistantCommand.SetVolume(100))
    }

    // --- Music transport -----------------------------------------------------

    @Test
    fun `pause music variants`() {
        listOf("pause", "pause the music", "pause the song", "stop the music")
            .forEach { assertParses(it, AssistantCommand.PauseMusic) }
    }

    @Test
    fun `resume music variants`() {
        listOf("resume", "resume the music", "continue playing", "continue the music")
            .forEach { assertParses(it, AssistantCommand.ResumeMusic) }
    }

    @Test
    fun `next track variants`() {
        listOf("next song", "next track", "skip this song", "skip the track")
            .forEach { assertParses(it, AssistantCommand.NextTrack) }
    }

    @Test
    fun `previous track variants`() {
        listOf("previous song", "previous track", "go back", "last song")
            .forEach { assertParses(it, AssistantCommand.PreviousTrack) }
    }

    // --- Sound modes ---------------------------------------------------------

    @Test
    fun `silent mode variants`() {
        listOf(
            "put my phone on silent",
            "silent mode",
            "turn my phone silent",
            "mute the phone",
            "silence the phone"
        ).forEach { assertParses(it, AssistantCommand.SilentMode) }
    }

    @Test
    fun `vibrate mode variants`() {
        listOf("turn on vibrate", "vibrate mode", "put my phone on vibrate", "switch to vibrate")
            .forEach { assertParses(it, AssistantCommand.VibrateMode) }
    }

    @Test
    fun `normal ring mode variants`() {
        listOf(
            "turn the ringtone back on",
            "normal ring mode",
            "turn off silent mode",
            "disable silent mode"
        ).forEach { assertParses(it, AssistantCommand.NormalRingMode) }
    }

    // --- Maps ----------------------------------------------------------------

    @Test
    fun `open maps variants`() {
        listOf("open maps", "open google maps", "launch maps", "open the maps")
            .forEach { assertParses(it, AssistantCommand.OpenMaps) }
    }

    @Test
    fun `location search variants`() {
        assertParses("find Starbucks", AssistantCommand.SearchLocation("Starbucks"))
        assertParses("search for Starbucks", AssistantCommand.SearchLocation("Starbucks"))
        assertParses("look for a coffee shop", AssistantCommand.SearchLocation("a coffee shop"))
        assertParses(
            "find the nearest gas station",
            AssistantCommand.SearchLocation("the nearest gas station")
        )
    }

    @Test
    fun `navigation variants`() {
        assertParses(
            "navigate to Berlin Central Station",
            AssistantCommand.NavigateTo("Berlin Central Station")
        )
        assertParses("take me to university", AssistantCommand.NavigateTo("university"))
        assertParses("directions to the airport", AssistantCommand.NavigateTo("the airport"))
        assertParses("drive me to the office", AssistantCommand.NavigateTo("the office"))
    }

    // --- Apps ----------------------------------------------------------------

    @Test
    fun `open app variants`() {
        assertParses("open YouTube", AssistantCommand.OpenApp("YouTube"))
        assertParses("launch YouTube", AssistantCommand.OpenApp("YouTube"))
        assertParses("start Instagram", AssistantCommand.OpenApp("Instagram"))
        assertParses("open Spotify", AssistantCommand.OpenApp("Spotify"))
    }

    @Test
    fun `open app strips the literal word app`() {
        assertParses("open app YouTube", AssistantCommand.OpenApp("YouTube"))
    }

    @Test
    fun `multi-word app names are accepted`() {
        assertParses("open Google Maps", AssistantCommand.OpenApp("Google Maps"))
    }

    // --- Settings ------------------------------------------------------------

    @Test
    fun `settings variants`() {
        assertParses("open settings", AssistantCommand.OpenSettings)
        assertParses("open the settings", AssistantCommand.OpenSettings)
        assertParses("open system settings", AssistantCommand.OpenSettings)
    }

    @Test
    fun `specific settings screens beat generic settings`() {
        assertParses("open Wi-Fi settings", AssistantCommand.OpenWifiSettings)
        assertParses("open wifi settings", AssistantCommand.OpenWifiSettings)
        assertParses("open wireless settings", AssistantCommand.OpenWifiSettings)
        assertParses("open Bluetooth settings", AssistantCommand.OpenBluetoothSettings)
        assertParses("open bluetooth settings", AssistantCommand.OpenBluetoothSettings)
        assertParses("open battery settings", AssistantCommand.OpenBatterySettings)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // B. NEGATIVE MATCHING — precision over recall
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `unrelated sentences are not commands`() {
        listOf(
            "tell me something interesting",
            "tell me about music",
            "turn it off",
            "do something",
            "make it better",
            "open something",
            "what is the weather",
            "i like this song"
        ).forEach { assertUnknown(it) }
    }

    @Test
    fun `bare verbs are not commands`() {
        listOf("play", "navigate", "search", "open", "turn", "volume").forEach {
            assertUnknown(it)
        }
    }

    @Test
    fun `blank input returns Unknown with an empty payload`() {
        // Contracted behaviour: Unknown("") for both, never " ".
        assertEquals(AssistantCommand.Unknown(""), parser.parse(""))
        assertEquals(AssistantCommand.Unknown(""), parser.parse("   "))
        assertEquals("", parser.parse("   ").let { (it as AssistantCommand.Unknown).rawText })
    }

    @Test
    fun `input that is only politeness has no command to find`() {
        assertEquals(AssistantCommand.Unknown(""), parser.parse("please"))
        assertEquals(AssistantCommand.Unknown(""), parser.parse("Hey Aria,"))
    }

    @Test
    fun `missing entities fall back to Unknown`() {
        listOf("navigate to", "take me to", "search for", "find", "look for").forEach {
            assertUnknown(it)
        }
    }

    @Test
    fun `go to alone is not navigation`() {
        // "go to X" is explicitly out of scope in the brief; a bare "go to" is
        // even less confident, so Unknown.
        assertUnknown("go to")
    }

    @Test
    fun `an entity made only of filler words is rejected`() {
        assertUnknown("find it")
        assertUnknown("navigate to something")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // C. ENTITY PRESERVATION — capitalisation and internal whitespace survive
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `song titles keep their capitalisation`() {
        assertParses("play Shape of You", AssistantCommand.PlayMusic("Shape of You"))
        assertParses("Play Shape of You", AssistantCommand.PlayMusic("Shape of You"))
    }

    @Test
    fun `the song prefix is removed from a title`() {
        assertParses(
            "play the song Blinding Lights",
            AssistantCommand.PlayMusic("Blinding Lights")
        )
        assertParses("play song Blinding Lights", AssistantCommand.PlayMusic("Blinding Lights"))
    }

    @Test
    fun `app names keep their capitalisation after a wake word`() {
        assertParses("Hey Aria, open YouTube.", AssistantCommand.OpenApp("YouTube"))
        assertParses("Could you open Spotify?", AssistantCommand.OpenApp("Spotify"))
    }

    @Test
    fun `search queries keep their capitalisation`() {
        assertParses("find Starbucks", AssistantCommand.SearchLocation("Starbucks"))
        assertParses("search for Pizza Hut", AssistantCommand.SearchLocation("Pizza Hut"))
    }

    @Test
    fun `destinations keep their capitalisation and spacing`() {
        assertParses(
            "Navigate to Berlin Central Station",
            AssistantCommand.NavigateTo("Berlin Central Station")
        )
        assertParses(
            "Please take me to Milan Cathedral",
            AssistantCommand.NavigateTo("Milan Cathedral")
        )
    }

    @Test
    fun `multi-word song queries keep the filler words that belong to them`() {
        // "my favorite song" is a query, not noise: only leading politeness goes.
        assertParses("play my favorite song", AssistantCommand.PlayMusic("my favorite song"))
        assertParses("play some music", AssistantCommand.PlayMusic("music"))
        assertParses("play music", AssistantCommand.PlayMusic("music"))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // D. NUMERIC VOLUME AND STREAM SELECTION
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `volume above one hundred is clamped, not rejected`() {
        // The Phase 4 model throws outside 0..100, so the parser must clamp first.
        assertParses("volume 150", AssistantCommand.SetVolume(100))
        assertParses("set volume to 9999", AssistantCommand.SetVolume(100))
    }

    @Test
    fun `negative volume is clamped to zero`() {
        assertParses("volume -10", AssistantCommand.SetVolume(0))
    }

    @Test
    fun `malformed volume values are Unknown`() {
        listOf("volume abc", "volume", "volume to", "set volume to", "volume !").forEach {
            assertUnknown(it)
        }
    }

    @Test
    fun `word-based numbers are Out of scope and give Unknown`() {
        // Phase 5 is digits only; "thirty" must NOT become 30.
        listOf("volume thirty", "set volume to fifty", "volume one hundred").forEach {
            assertUnknown(it)
        }
    }

    @Test
    fun `stream defaults to MEDIA`() {
        assertParses("set volume to 30", AssistantCommand.SetVolume(30, AudioStream.MEDIA))
        assertParses("volume 45", AssistantCommand.SetVolume(45, AudioStream.MEDIA))
        assertParses("set media volume to 50", AssistantCommand.SetVolume(50, AudioStream.MEDIA))
    }

    @Test
    fun `ring volume selects the RING stream`() {
        assertParses("set ring volume to 40", AssistantCommand.SetVolume(40, AudioStream.RING))
        assertParses("ringer volume 20", AssistantCommand.SetVolume(20, AudioStream.RING))
    }

    @Test
    fun `alarm volume selects the ALARM stream`() {
        assertParses("set alarm volume to 20", AssistantCommand.SetVolume(20, AudioStream.ALARM))
    }

    @Test
    fun `every parsed volume level is inside the model's valid range`() {
        // Guards against any future clamping regression producing an exception.
        listOf("volume 0", "volume 100", "volume 150", "volume -5", "volume 777")
            .forEach { input ->
                val command = parser.parse(input)
                assertTrue("Expected SetVolume for \"$input\"", command is AssistantCommand.SetVolume)
                val level = (command as AssistantCommand.SetVolume).percentage
                assertTrue("Level $level out of range for \"$input\"", level in 0..100)
            }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // E. NORMALISATION — case, spacing, punctuation, fillers, wake words
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `uppercase input matches`() {
        assertParses("PLEASE TURN THE VOLUME DOWN!!!", AssistantCommand.VolumeDown)
        assertParses("OPEN YOUTUBE", AssistantCommand.OpenApp("YOUTUBE"))
    }

    @Test
    fun `extra whitespace is irrelevant`() {
        assertParses("  turn   it   up  ", AssistantCommand.VolumeUp)
        assertParses("\tturn\nthe volume down\t", AssistantCommand.VolumeDown)
    }

    @Test
    fun `trailing and internal punctuation does not block a match`() {
        assertParses("turn the volume down.", AssistantCommand.VolumeDown)
        assertParses("Open maps!", AssistantCommand.OpenMaps)
        assertParses("pause the music?", AssistantCommand.PauseMusic)
    }

    @Test
    fun `politeness prefixes are ignored`() {
        listOf(
            "please turn the volume down",
            "could you turn the volume down",
            "can you please turn the volume down",
            "would you turn the volume down"
        ).forEach { assertParses(it, AssistantCommand.VolumeDown) }
    }

    @Test
    fun `wake words are ignored`() {
        assertParses("hey aria open settings", AssistantCommand.OpenSettings)
        assertParses("Aria, pause the music", AssistantCommand.PauseMusic)
        assertParses("Could you please open settings?", AssistantCommand.OpenSettings)
    }

    @Test
    fun `repeated fillers are all stripped`() {
        assertParses("please could you open settings", AssistantCommand.OpenSettings)
    }

    @Test
    fun `normalisation does not lowercase the extracted entity`() {
        val command = parser.parse("Please play Shape of You") as AssistantCommand.PlayMusic
        assertEquals("Shape of You", command.query)
        // Proof it is not merely equal ignoring case: the S is capital.
        assertTrue(command.query.first().isUpperCase())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // F. PRIORITY AND CONFLICTS
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `open maps is never an app launch`() {
        assertParses("open maps", AssistantCommand.OpenMaps)
        assertParses("open google maps", AssistantCommand.OpenMaps)
        // A multi-word app name that is not the Maps phrase still launches the app.
        assertParses("open Google Play", AssistantCommand.OpenApp("Google Play"))
    }

    @Test
    fun `mute the phone versus mute the media`() {
        assertParses("mute the phone", AssistantCommand.SilentMode)
        assertParses("mute the media", AssistantCommand.MuteMedia)
        assertParses("mute the music", AssistantCommand.MuteMedia)
    }

    @Test
    fun `specific settings always win over generic settings`() {
        assertParses("open Wi-Fi settings", AssistantCommand.OpenWifiSettings)
        assertParses("open settings", AssistantCommand.OpenSettings)
    }

    @Test
    fun `specific settings are never app launches`() {
        listOf(
            "open battery settings",
            "open bluetooth settings",
            "open wifi settings"
        ).forEach { input ->
            val command = parser.parse(input)
            assertTrue("$input must not be OpenApp, was $command", command !is AssistantCommand.OpenApp)
        }
    }

    @Test
    fun `navigate to a place is navigation, find a place is search`() {
        assertParses("navigate to Starbucks", AssistantCommand.NavigateTo("Starbucks"))
        assertParses("find Starbucks", AssistantCommand.SearchLocation("Starbucks"))
        assertParses("find the airport", AssistantCommand.SearchLocation("the airport"))
        assertParses("directions to the airport", AssistantCommand.NavigateTo("the airport"))
    }

    @Test
    fun `silent mode is not stolen by volume or media rules`() {
        assertParses("silent mode", AssistantCommand.SilentMode)
        assertParses("turn the volume down", AssistantCommand.VolumeDown)
    }

    @Test
    fun `ringtone and volume commands stay separate`() {
        // A ring-volume request must reach SetVolume(RING), never a sound mode.
        assertParses("set ring volume to 40", AssistantCommand.SetVolume(40, AudioStream.RING))
        assertParses("turn the ringtone back on", AssistantCommand.NormalRingMode)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // G. PURITY AND FULL PIPELINE
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `parsing is deterministic`() {
        val first = parser.parse("turn the volume down")
        repeat(20) {
            assertEquals(first, parser.parse("turn the volume down"))
        }
    }

    @Test
    fun `the parser is stateless across different inputs`() {
        // Order must not matter: a previous parse cannot influence the next one.
        val a = parser.parse("open YouTube")
        parser.parse("volume 40")
        parser.parse("navigate to Rome")
        assertEquals(a, parser.parse("open YouTube"))
    }

    @Test
    fun `unknown echoes the original text including its case and punctuation`() {
        val input = "Tell me Something Interesting!"
        val command = parser.parse(input)
        assertEquals(AssistantCommand.Unknown(input), command)
    }

    @Test
    fun `full pipeline - speech to command`() {
        // The integration-style check the phase asks for: recogniser text in,
        // typed command out. The real ViewModel is deliberately NOT involved.
        val parser = RuleBasedCommandParser()

        assertEquals(
            AssistantCommand.VolumeDown,
            parser.parse("Turn the volume down")
        )
        assertEquals(
            AssistantCommand.OpenApp("YouTube"),
            parser.parse("Hey Aria, open YouTube.")
        )
        assertEquals(
            AssistantCommand.SetVolume(40, AudioStream.RING),
            parser.parse("set ring volume to 40")
        )
    }
}
