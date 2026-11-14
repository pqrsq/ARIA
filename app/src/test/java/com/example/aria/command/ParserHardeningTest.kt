package com.example.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6.5 — regression tests for the parser defects found by the audit.
 *
 * EVERY TEST HERE FAILED BEFORE ITS FIX. They are grouped by defect id (D3, D4,
 * D5, D6, D10, D11, A3) so a future reader can trace the behaviour back to the
 * report that demanded it.
 *
 * The parser is pure Kotlin, so all of this runs on the JVM in milliseconds.
 */
class ParserHardeningTest {

    private val parser = RuleBasedCommandParser()

    private fun parse(text: String): AssistantCommand = parser.parse(text)

    // ── D3 — lower-case app names ────────────────────────────────────────────

    @Test
    fun `a lowercase app name is still an app`() {
        // BEFORE: looksLikeAppName demanded a capital, so real recogniser output
        // (which is often all lowercase) produced Unknown on an actual phone.
        assertEquals(AssistantCommand.OpenApp("chrome"), parse("open chrome"))
    }

    @Test
    fun `a leading article is stripped from the app name`() {
        assertEquals(AssistantCommand.OpenApp("calculator"), parse("open the calculator"))
    }

    @Test
    fun `a trailing 'app' is stripped from the app name`() {
        assertEquals(AssistantCommand.OpenApp("YouTube"), parse("open the YouTube app"))
    }

    @Test
    fun `the app guards still reject non-names`() {
        // Preserved behaviour: the fix must not turn every sentence into an app.
        assertTrue(parse("open something") is AssistantCommand.Unknown)
        assertTrue(parse("open it") is AssistantCommand.Unknown)
    }

    @Test
    fun `settings still wins over app launching`() {
        // "open settings" must never become OpenApp("settings").
        assertEquals(AssistantCommand.OpenSettings, parse("open settings"))
        assertEquals(AssistantCommand.OpenSettings, parse("open the settings app"))
    }

    // ── D4 — comparatives ────────────────────────────────────────────────────

    @Test
    fun `a sentence containing 'louder' is not a volume command`() {
        // BEFORE: \blouder\b used containsMatchIn, so this changed the volume.
        assertTrue(parse("the movie was louder than I expected") is AssistantCommand.Unknown)
    }

    @Test
    fun `a sentence containing 'quieter' is not a volume command`() {
        assertTrue(parse("this restaurant is quieter than the last one") is AssistantCommand.Unknown)
    }

    @Test
    fun `a bare comparative is still a command`() {
        // PRESERVED: the whole utterance is a legitimate request.
        assertEquals(AssistantCommand.VolumeUp, parse("louder"))
        assertEquals(AssistantCommand.VolumeDown, parse("quieter"))
    }

    @Test
    fun `an imperative with a comparative still works`() {
        assertEquals(AssistantCommand.VolumeUp, parse("make it louder"))
    }

    @Test
    fun `politeness after a bare comparative is tolerated`() {
        assertEquals(AssistantCommand.VolumeUp, parse("louder please"))
    }

    @Test
    fun `an anchored volume phrasing still works inside a sentence`() {
        // The counterpart of the D4 fix: only the BARE comparative is restricted
        // to the whole utterance; "turn the volume down" carries its own verb.
        assertEquals(AssistantCommand.VolumeDown, parse("turn the volume down"))
        assertEquals(AssistantCommand.VolumeUp, parse("please turn the volume up"))
    }

    // ── D5 — transport vs music search ───────────────────────────────────────

    @Test
    fun `play next song is transport, not a search`() {
        // BEFORE: transport rules were full-match only, so this fell through to
        // PlayMusic("next song") and opened the music app.
        assertEquals(AssistantCommand.NextTrack, parse("play next song"))
    }

    @Test
    fun `play previous track is transport`() {
        assertEquals(AssistantCommand.PreviousTrack, parse("play previous track"))
    }

    @Test
    fun `a real song title is still a music search`() {
        // The fix must not capture ordinary "play X" phrases.
        assertEquals(AssistantCommand.PlayMusic("Shape of You"), parse("play Shape of You"))
    }

    // ── D6 — unsupported streams ─────────────────────────────────────────────

    @Test
    fun `an unsupported stream is refused rather than redirected to media`() {
        // BEFORE: "notification" was ignored and the MEDIA stream changed — a
        // silent wrong action.
        assertTrue(parse("set the notification volume to 50") is AssistantCommand.Unknown)
    }

    @Test
    fun `other unsupported stream qualifiers are refused too`() {
        listOf(
            "set the call volume to 50",
            "set the system volume to 30",
            "set the voice volume to 20"
        ).forEach { phrase ->
            assertTrue(phrase, parse(phrase) is AssistantCommand.Unknown)
        }
    }

    @Test
    fun `media is still the default when no stream is named`() {
        assertEquals(
            AssistantCommand.SetVolume(50, AudioStream.MEDIA),
            parse("set the volume to 50")
        )
    }

    @Test
    fun `supported streams still work`() {
        assertEquals(AssistantCommand.SetVolume(40, AudioStream.RING), parse("set ring volume to 40"))
        assertEquals(AssistantCommand.SetVolume(70, AudioStream.ALARM), parse("set alarm volume to 70"))
    }

    // ── D10 — compound sentences ─────────────────────────────────────────────

    @Test
    fun `two commands in one sentence are refused, not half-executed`() {
        // BEFORE: SetVolume(30) executed and "turn off wifi" was silently dropped,
        // while the UI reported complete success.
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE),
            parse("set the volume to 30 percent and turn off wifi")
        )
    }

    @Test
    fun `a second command joined by 'then' is refused`() {
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE),
            parse("open chrome then play music")
        )
    }

    @Test
    fun `entities containing 'and' are not treated as compounds`() {
        // THE REASON THE DETECTION IS TAIL-ANALYSIS: "and" is part of the title.
        assertEquals(
            AssistantCommand.PlayMusic("Rock and Roll"),
            parse("play Rock and Roll")
        )
    }

    @Test
    fun `a comma inside a destination is not a compound`() {
        assertEquals(
            AssistantCommand.NavigateTo("Berlin, Germany"),
            parse("navigate to Berlin, Germany")
        )
    }

    @Test
    fun `an 'and' inside a place name is not a compound`() {
        assertEquals(
            AssistantCommand.NavigateTo("Bath and Bristol"),
            parse("navigate to Bath and Bristol")
        )
    }

    // ── D11 — out-of-range volume ────────────────────────────────────────────

    @Test
    fun `a volume above one hundred is refused, not clamped`() {
        // BEFORE: silently became SetVolume(100).
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE),
            parse("set the volume to 120 percent")
        )
    }

    @Test
    fun `a negative volume is refused, and never becomes a mute`() {
        // BEFORE: clamped to 0, i.e. a typo muted the phone.
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE),
            parse("volume -20")
        )
    }

    @Test
    fun `a valid volume is unchanged by the refusal rule`() {
        assertEquals(AssistantCommand.SetVolume(30), parse("set the volume to 30"))
        assertEquals(AssistantCommand.SetVolume(0), parse("set the volume to 0"))
        assertEquals(AssistantCommand.SetVolume(100), parse("set the volume to 100"))
    }

    // ── A3 — unmute ──────────────────────────────────────────────────────────

    @Test
    fun `bare unmute targets the media stream`() {
        assertEquals(AssistantCommand.UnmuteMedia, parse("unmute"))
        assertEquals(AssistantCommand.UnmuteMedia, parse("unmute the media"))
    }

    @Test
    fun `unmuting the phone is a ringer action, not a media action`() {
        // DOCUMENTED DECISION: the object of the verb decides, exactly as it does
        // for "mute the phone".
        assertEquals(AssistantCommand.NormalRingMode, parse("unmute the phone"))
    }

    @Test
    fun `an explicit media unmute phrasing still works`() {
        // The mirror of the ambiguous phrase: naming the stream removes the
        // ambiguity, so this must NOT be refused.
        assertEquals(AssistantCommand.UnmuteMedia, parse("turn the media back on"))
    }

    @Test
    fun `an ambiguous 'sound back on' is left unknown rather than guessed`() {
        // Media or ringer? ARIA declines instead of changing the wrong stream.
        assertTrue(parse("turn the sound back on") is AssistantCommand.Unknown)
    }

    @Test
    fun `mute the phone still means silent, not media mute`() {
        // Guarded so the unmute mirror cannot have inverted the existing rule.
        assertEquals(AssistantCommand.SilentMode, parse("mute the phone"))
    }
}
