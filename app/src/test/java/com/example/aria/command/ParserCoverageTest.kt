package com.example.aria.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE P1 — the last parser coverage/precision gaps from the original audit.
 *
 * EVERY TEST HERE FAILED BEFORE ITS FIX. One item in the brief turned out to be
 * already satisfied and has no "fix" test, only the regression guard that proves
 * it: P1.5's `go back` (see the note on that test).
 *
 * Sections follow the brief's numbering (P1.1 … P1.5) so a reader can trace a
 * behaviour back to the request that demanded it.
 *
 * The parser is pure Kotlin, so this runs on the JVM in milliseconds.
 */
class ParserCoverageTest {

    private val parser = RuleBasedCommandParser()

    private fun parse(text: String): AssistantCommand = parser.parse(text)

    // ── P1.1 — volume word order ─────────────────────────────────────────────

    @Test
    fun `turn up the volume puts the direction before the noun`() {
        // BEFORE: only "turn the volume up" was known, so this was Unknown.
        assertEquals(AssistantCommand.VolumeUp, parse("turn up the volume"))
        assertEquals(AssistantCommand.VolumeDown, parse("turn down the volume"))
    }

    @Test
    fun `volume up and volume down are commands`() {
        assertEquals(AssistantCommand.VolumeUp, parse("volume up"))
        assertEquals(AssistantCommand.VolumeDown, parse("volume down"))
    }

    @Test
    fun `the direction can lead without a verb`() {
        // BEFORE: "up the volume" had no rule at all.
        assertEquals(AssistantCommand.VolumeUp, parse("up the volume"))
        assertEquals(AssistantCommand.VolumeDown, parse("down the volume"))
    }

    @Test
    fun `the other word orders also work inside a sentence`() {
        // The new patterns are ANCHORED (containsMatchIn), not whole-utterance,
        // because the verb already carries the intent.
        assertEquals(AssistantCommand.VolumeUp, parse("could you turn up the volume in here"))
    }

    @Test
    fun `D4 regression - a bare comparative inside a sentence is still not a command`() {
        // THE INVARIANT THIS PHASE MUST NOT BREAK: P1.1 adds "up"/"down"
        // phrasings, but a bare comparative anywhere in a sentence stays Unknown.
        assertTrue(parse("the movie was louder than I expected") is AssistantCommand.Unknown)
        assertTrue(parse("this restaurant is quieter than the last one") is AssistantCommand.Unknown)
    }

    // ── P1.2 — trailing politeness ───────────────────────────────────────────

    @Test
    fun `a trailing please no longer hides a transport command`() {
        // BEFORE: only LEADING fillers were stripped, so the trailing word made
        // the whole utterance unmatchable.
        assertEquals(AssistantCommand.PauseMusic, parse("pause the music please"))
    }

    @Test
    fun `a trailing please works for sound modes too`() {
        // "vibrate please" needs BOTH fixes to work: P1.2 removes the trailing
        // word and P1.3 makes the bare "vibrate" a mode.
        assertEquals(AssistantCommand.VibrateMode, parse("vibrate please"))
    }

    @Test
    fun `thanks and thank you are stripped as well`() {
        assertEquals(AssistantCommand.PauseMusic, parse("pause the music thanks"))
        assertEquals(AssistantCommand.PauseMusic, parse("pause the music thank you"))
    }

    @Test
    fun `politeness at both ends is stripped`() {
        assertEquals(AssistantCommand.PauseMusic, parse("please pause the music please"))
    }

    @Test
    fun `a song title made of the word please is not renamed`() {
        // THE REASON ENTITIES ARE NOT RUN THROUGH THE TRAILING STRIP: the title
        // and the filler are the same word.
        assertEquals(
            AssistantCommand.PlayMusic("Please Please Please"),
            parse("play Please Please Please")
        )
    }

    @Test
    fun `a comma-anchored please is stripped from a song title`() {
        assertEquals(AssistantCommand.PlayMusic("Hello"), parse("play Hello, please"))
    }

    @Test
    fun `a comma-anchored please is stripped even when the title contains please`() {
        // The comma is the speaker's own boundary, so it wins over the "please"
        // that belongs to the title.
        assertEquals(
            AssistantCommand.PlayMusic("Please Please Please"),
            parse("play Please Please Please, please")
        )
    }

    @Test
    fun `a trailing please is stripped from an app name`() {
        // App names are the one entity where no comma is needed: the launcher list
        // is a closed vocabulary, so "please" is never part of a real name.
        assertEquals(AssistantCommand.OpenApp("chrome"), parse("open chrome please"))
        assertEquals(AssistantCommand.OpenApp("YouTube"), parse("open the YouTube app please"))
    }

    @Test
    fun `D11 regression - a refusal still happens with a trailing please`() {
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE),
            parse("volume 120 please")
        )
    }

    @Test
    fun `D10 regression - a compound sentence is still refused with politeness`() {
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE),
            parse("set the volume to 30 percent and turn off wifi")
        )
        assertEquals(
            AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE),
            parse("set the volume to 30 percent and turn off wifi please")
        )
    }

    @Test
    fun `blank and politeness-only input keeps the contracted answer`() {
        assertEquals(AssistantCommand.Unknown(""), parse(""))
        assertEquals(AssistantCommand.Unknown(""), parse("   "))
        assertEquals(AssistantCommand.Unknown(""), parse("please"))
        assertEquals(AssistantCommand.Unknown(""), parse("please please"))
        assertEquals(AssistantCommand.Unknown(""), parse("thanks"))
    }

    @Test
    fun `the documented limitation - a comma-less please stays in the title`() {
        // DOCUMENTED, NOT A BUG: without the comma this is indistinguishable from
        // a title, and renaming a song is worse than an odd query.
        assertEquals(AssistantCommand.PlayMusic("hello please"), parse("play hello please"))
    }

    // ── P1.3 — sound-mode phrases ────────────────────────────────────────────

    @Test
    fun `turn on silent mode`() {
        assertEquals(AssistantCommand.SilentMode, parse("turn on silent mode"))
    }

    @Test
    fun `mute or silence MY phone`() {
        // BEFORE: the stage-1 pattern only allowed "the", so the possessive form
        // fell through to the media-mute rules and missed.
        assertEquals(AssistantCommand.SilentMode, parse("mute my phone"))
        assertEquals(AssistantCommand.SilentMode, parse("silence my phone"))
    }

    @Test
    fun `turn my phone silent`() {
        assertEquals(AssistantCommand.SilentMode, parse("turn my phone silent"))
    }

    @Test
    fun `bare vibrate is a sound mode`() {
        assertEquals(AssistantCommand.VibrateMode, parse("vibrate"))
    }

    @Test
    fun `regression - turning a mode OFF is never read as turning it on`() {
        // THE ORDER INVARIANT: NORMAL_RING is checked before the new "turn on
        // silent mode" pattern, so the negation cannot be inverted.
        assertEquals(AssistantCommand.NormalRingMode, parse("turn off silent mode"))
        assertEquals(AssistantCommand.NormalRingMode, parse("disable silent mode"))
    }

    @Test
    fun `regression - media muting is not stolen by the phone rule`() {
        assertEquals(AssistantCommand.MuteMedia, parse("mute the media"))
        assertEquals(AssistantCommand.MuteMedia, parse("mute"))
    }

    @Test
    fun `regression - unmuting the phone is still a ringer action`() {
        assertEquals(AssistantCommand.NormalRingMode, parse("unmute the phone"))
    }

    @Test
    fun `regression - a volume phrase never becomes a sound mode`() {
        // The `!contains("volume")` guard in the sound-mode stage: when the
        // sentence mentions a volume, that stage declines ENTIRELY, so no ringer
        // mode can be returned from a sentence that is really about a level.
        val command = parse("silent mode but volume 30")

        assertTrue(
            "A volume sentence must not change the ringer mode: $command",
            command !is AssistantCommand.SilentMode &&
                command !is AssistantCommand.VibrateMode &&
                command !is AssistantCommand.NormalRingMode
        )
    }

    // ── P1.4 — "find my X" ───────────────────────────────────────────────────

    @Test
    fun `find my thing is not a map search`() {
        // BEFORE: "find my phone" searched the map for the literal words "my
        // phone" — a confidently wrong answer. An honest refusal is better.
        assertTrue(parse("find my phone") is AssistantCommand.Unknown)
        assertTrue(parse("find my keys") is AssistantCommand.Unknown)
        assertTrue(parse("look for my wallet") is AssistantCommand.Unknown)
        assertTrue(parse("search for my glasses") is AssistantCommand.Unknown)
    }

    @Test
    fun `regression - real places are still searched`() {
        assertEquals(AssistantCommand.SearchLocation("Starbucks"), parse("find Starbucks"))
        assertEquals(AssistantCommand.SearchLocation("the airport"), parse("find the airport"))
        assertEquals(AssistantCommand.SearchLocation("cafes"), parse("search for cafes"))
        // "my" must be the FIRST word to trigger the refusal.
        assertEquals(
            AssistantCommand.SearchLocation("cafe my way"),
            parse("find cafe my way")
        )
    }

    @Test
    fun `regression - navigating to a place called mine still works`() {
        // The guard is on the SEARCH stage only; navigation is a different intent.
        assertEquals(
            AssistantCommand.NavigateTo("Myrtle Avenue"),
            parse("navigate to Myrtle Avenue")
        )
    }

    // ── P1.5 — bare skip and go back ─────────────────────────────────────────

    @Test
    fun `bare skip is the next track`() {
        // BEFORE: only the bare word was missing — "skip track" and "skip song"
        // already matched the `(skip|play) (this|the)? (song|track)` pattern, so
        // this test pins the one form that changed.
        assertEquals(AssistantCommand.NextTrack, parse("skip"))
        assertEquals(AssistantCommand.NextTrack, parse("skip track"))
        assertEquals(AssistantCommand.NextTrack, parse("skip song"))
    }

    @Test
    fun `go back was already a command - guard only`() {
        // ALREADY SATISFIED, NO CODE CHANGE: PREVIOUS_PATTERNS has contained
        // Regex("go\\s+back") since Phase 5. This test exists so the behaviour the
        // brief asked for cannot regress.
        assertEquals(AssistantCommand.PreviousTrack, parse("go back"))
    }

    @Test
    fun `transport stays full-utterance so a sentence is not a command`() {
        // THE INVARIANT: adding a bare "skip" must not make "I want to skip" act.
        assertTrue(parse("I want to skip") is AssistantCommand.Unknown)
        assertTrue(parse("I want to go back") is AssistantCommand.Unknown)
    }

    @Test
    fun `existing transport phrasings are unchanged`() {
        assertEquals(AssistantCommand.NextTrack, parse("skip this song"))
        assertEquals(AssistantCommand.NextTrack, parse("play next song"))
        assertEquals(AssistantCommand.PreviousTrack, parse("play previous track"))
    }
}
