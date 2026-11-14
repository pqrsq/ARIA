package com.example.aria.command

/**
 * PHASE 5 — deterministic, offline command parsing.
 *
 * ── THE SHAPE OF THIS CLASS ──────────────────────────────────────────────────
 * Rules are grouped into small, named matchers, tried in a fixed order. Each
 * matcher either returns a command or null, and the first non-null answer wins.
 * There is deliberately **no single giant `when`**: each stage can be read,
 * tested and reordered on its own.
 *
 * ── PRIORITY (and why) ───────────────────────────────────────────────────────
 *  1. **Sound-mode with an explicit phone target** ("mute the phone") — must beat
 *     media muting, which shares the word "mute".
 *  2. **Specific settings** ("open wifi settings") — must beat generic settings
 *     and must beat app launching ("open …").
 *  3. **Maps** — "open maps" must beat `OpenApp("maps")`.
 *  4. **Navigation** ("navigate to X", "take me to X") — before search, so
 *     "navigate to Starbucks" is not a search.
 *  5. **Location search** ("find X") — after navigation, so "find the airport"
 *     stays a search.
 *  6. **App launching** — after maps/settings so those are never an app name.
 *  7. **Sound modes** (generic) — after volume, so "silent mode" style phrases
 *     resolve here while "volume" phrases never do.
 *  8. **Volume** — level-changing phrases.
 *  9. **Music** (transport, then play).
 * 10. **Fallback** — [AssistantCommand.Unknown].
 *
 * Priority is not used to paper over sloppy patterns: every rule additionally
 * demands a strict shape (anchor + required tail), so an unrelated sentence that
 * merely contains "play", "open" or "turn" cannot match. Precision over recall:
 * unclear text becomes [AssistantCommand.Unknown].
 *
 * ── LANGUAGE ─────────────────────────────────────────────────────────────────
 * English only, by design for Phase 5. Persian command phrases and Persian app
 * names are explicitly out of scope.
 *
 * ── PURITY ───────────────────────────────────────────────────────────────────
 * No state, no Android, no logging, no I/O. [parse] is a total, deterministic
 * function from `String` to [AssistantCommand].
 */
class RuleBasedCommandParser : CommandParser {

    override fun parse(rawText: String): AssistantCommand {
        val text = TextNormalizer.normalize(rawText)

        // Blank, or nothing but politeness ("please") → the contracted answer.
        if (text.isEmpty) return AssistantCommand.Unknown("")

        // Each stage: first definitive answer wins.
        val command = parsPhoneSoundMode(text)
            ?: parseSpecificSettings(text)
            ?: parseMapsOpen(text)
            ?: parseNavigate(text)
            ?: parseLocationSearch(text)
            ?: parseOpenApp(text)
            ?: parseGenericSettings(text)
            ?: parseSoundMode(text)
            ?: parseVolume(text)
            ?: parseMusicTransport(text)
            ?: parsePlayMusic(text)
            ?: return AssistantCommand.Unknown(rawText)

        // PHASE 6.5 (D10): a recognised command whose sentence also asks for a
        // second one must be refused, not silently half-executed.
        return parseCompoundTail(text) ?: command
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Sound mode with an explicit "phone" target
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "mute the phone" is a ringer command, not a media command.
     *
     * WHY IT IS FIRST: it shares the word "mute" with [AssistantCommand.MuteMedia],
     * and the difference is only the object of the verb. Resolving it before any
     * media rule makes that distinction impossible to get wrong later.
     */
    private fun parsPhoneSoundMode(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized

        // NOTE: a bare "silent mode" is NOT handled here — it belongs to the
        // sound-mode stage, which runs *after* the ring-restoring rules. Catching
        // it here turned "turn off silent mode" into SilentMode.
        val silentPatterns = listOf(
            Regex("\\b(mute|silence)\\s+(the\\s+)?(phone|device|ringer)\\b"),
            Regex("\\bput\\s+(the\\s+|my\\s+)?phone\\s+on\\s+(silent|mute)\\b")
        )
        if (silentPatterns.any { it.containsMatchIn(phrase) } && !phrase.contains("volume")) {
            return AssistantCommand.SilentMode
        }

        // PHASE 6.5 (A3) — the mirror of "mute the phone". Documented decision:
        // unmuting the *phone* is a ringer action, so it maps to NormalRingMode
        // rather than to media unmuting. The object of the verb decides, exactly
        // as it does for muting.
        val unmutePhonePatterns = listOf(
            Regex("\\bunmute\\s+(the\\s+)?(phone|device|ringer)\\b"),
            Regex("\\bunsilence\\s+(the\\s+)?(phone|device|ringer)\\b")
        )
        if (unmutePhonePatterns.any { it.containsMatchIn(phrase) }) {
            return AssistantCommand.NormalRingMode
        }

        val vibratePatterns = listOf(
            Regex("\\bput\\s+(the\\s+|my\\s+)?phone\\s+on\\s+vibrate\\b"),
            Regex("\\bphone\\s+(on\\s+)?vibrate\\b")
        )
        if (vibratePatterns.any { it.containsMatchIn(phrase) }) {
            return AssistantCommand.VibrateMode
        }

        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Specific settings (before generic settings AND before app launching)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseSpecificSettings(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        if (!OPEN_VERBS.any { phrase.startsWithWord(it) }) return null

        return when {
            WIFI_SETTINGS.containsMatchIn(phrase) -> AssistantCommand.OpenWifiSettings
            BLUETOOTH_SETTINGS.containsMatchIn(phrase) -> AssistantCommand.OpenBluetoothSettings
            BATTERY_SETTINGS.containsMatchIn(phrase) -> AssistantCommand.OpenBatterySettings
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Maps
    // ─────────────────────────────────────────────────────────────────────────

    /** "open maps", "open google maps", "launch maps" — a whole-phrase match. */
    private fun parseMapsOpen(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        if (!OPEN_VERBS.any { phrase.startsWithWord(it) }) return null
        return if (MAPS_PHRASE.matches(phrase)) AssistantCommand.OpenMaps else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Navigation — strict anchors only
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recognises the navigation anchors the brief lists, and nothing else.
     *
     * WHY SO STRICT: "go to university" is listed as *not* a command (there is no
     * bare "go to X" rule), so navigation needs an explicit verb — "navigate",
     * "directions", "take me", "drive me". Bare "navigate" with no destination is
     * [AssistantCommand.Unknown], which the null return produces.
     */
    private fun parseNavigate(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        for (anchor in NAVIGATION_ANCHORS) {
            if (!phrase.startsWithWord(anchor)) continue

            val destination = TextNormalizer.entityAfterPrefix(text, anchor) ?: return null
            // A destination made only of filler is not a destination.
            if (destination.lowercase() in NON_ENTITIES) return null
            return AssistantCommand.NavigateTo(destination)
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Location search — "find X", "search for X", "look for X"
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseLocationSearch(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        for (anchor in SEARCH_ANCHORS) {
            if (!phrase.startsWithWord(anchor)) continue

            // "search" alone is ambiguous (search what? where?), so an anchor that
            // needs a tail must have one.
            val query = TextNormalizer.entityAfterPrefix(text, anchor) ?: return null
            if (query.lowercase() in NON_ENTITIES) return null
            return AssistantCommand.SearchLocation(query)
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. App launching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * "open YouTube", "launch Spotify", "start Instagram", "open app YouTube".
     *
     * WHY THE ENTITY IS REQUIRED TO LOOK LIKE A NAME: "open something" must be
     * [AssistantCommand.Unknown]. What counts as "looks like a name" is decided by
     * [looksLikeAppName] — which since Phase 6.5 (D3) does NOT consult the casing,
     * because a real recogniser lowercases everything. Whether the name exists is
     * then decided by [com.example.aria.actions.AppNameMatcher], against the
     * installed-app list.
     */
    private fun parseOpenApp(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized

        for (prefix in APP_PREFIXES) {
            if (!phrase.startsWithWord(prefix)) continue

            val remainder = phrase.removePrefix(prefix).trim()
            val appName = TextNormalizer.entityAfterPrefix(text, prefix) ?: return null

            // "open app YouTube" → the literal word "app" is command syntax.
            val cleaned = if (remainder.startsWithWord("app")) {
                TextNormalizer.entityAfterPrefix(text, "$prefix app") ?: return null
            } else {
                appName
            }

            if (cleaned.lowercase() in NON_ENTITIES) return null
            val name = stripAppNoise(cleaned)
            if (name.isEmpty()) return null
            if (!looksLikeAppName(name)) return null
            // An app *called* "settings" is still the system settings screen: the
            // specific/generic settings rules ran earlier and must win.
            if (SETTINGS_APP_NAMES.contains(name.lowercase())) return null
            return AssistantCommand.OpenApp(name)
        }
        return null
    }

    /**
     * True when [name] plausibly names an app rather than continuing a sentence.
     *
     * ── PHASE 6.5 (D3): THE CAPITALISATION REQUIREMENT IS GONE ───────────────
     * It used to demand a capital letter, on the theory that recogniser output
     * preserves proper nouns. Real devices do not guarantee that — Samsung's
     * recogniser lowercases everything — so on an actual phone "open chrome"
     * parsed as [AssistantCommand.Unknown] while the same sentence typed with a
     * capital letter worked. A rule that depends on the recogniser's casing is
     * not a rule, it is a coin flip.
     *
     * The decision therefore belongs to
     * [com.example.aria.actions.AppNameMatcher], which compares against the
     * *actually installed* app list and reports an honest miss. The parser's job
     * is only to rule out phrases that are plainly not names, which is what
     * [NON_ENTITIES] and [STOP_WORDS] still do.
     */
    private fun looksLikeAppName(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase()
        if (lower in NON_ENTITIES) return false

        // A known command word is never an app name ("open it", "open settings").
        if (lower in STOP_WORDS) return false

        // Multi-word: reject only when *every* word is filler ("open the thing").
        val words = name.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.size > 1) return words.any { it.lowercase() !in STOP_WORDS }

        // A single word: anything not already excluded is a candidate, because the
        // installed-app list is the only authority on whether it is a real app.
        return true
    }

    /**
     * PHASE 6.5 (D3) — removes the "how people say it" noise from an app name.
     *
     * "open the calculator" → "calculator"; "open the YouTube app" → "YouTube".
     * The leading article and the trailing literal word "app" are command syntax,
     * not part of the name. A bare "app" is never stripped from the *end* of a
     * legitimate multi-word name because that word is always command syntax after
     * "open".
     */
    private fun stripAppNoise(name: String): String {
        var result = name.trim()
        if (result.lowercase().startsWithWord("the")) {
            result = stripLeadingPhrase(result, "the") ?: result
        }
        if (result.lowercase().endsWithWord("app")) {
            result = stripTrailingPhrase(result, "app") ?: result
        }
        return result.trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Generic settings
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseGenericSettings(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        if (!OPEN_VERBS.any { phrase.startsWithWord(it) }) return null

        return when (phrase) {
            "open settings",
            "open the settings",
            "open system settings",
            "open the settings app" -> AssistantCommand.OpenSettings
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Sound modes (generic, no explicit phone target)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseSoundMode(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized

        // Never steal a volume phrase: "turn the volume down" is not a mode.
        if (phrase.contains("volume")) return null

        // ORDER IS LOAD-BEARING: "turn off silent mode" *contains* "silent mode",
        // so the ring-restoring rules must be tested first or the negation would
        // be read as an affirmation.
        return when {
            NORMAL_RING_PATTERNS.any { it.containsMatchIn(phrase) } ->
                AssistantCommand.NormalRingMode

            VIBRATE_PATTERNS.any { it.containsMatchIn(phrase) } ->
                AssistantCommand.VibrateMode

            ELABORATE_SILENT_PATTERNS.any { it.containsMatchIn(phrase) } ->
                AssistantCommand.SilentMode

            // A bare "silent mode" is a command; "silent mode is annoying" is a
            // sentence, so the whole-phrase form demands the entire utterance.
            phrase == "silent mode" || phrase == "go silent" ->
                AssistantCommand.SilentMode

            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Volume — direction, mute, absolute level
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Three distinct shapes, tried in this order:
     *  1. an absolute level ("set ring volume to 40", "volume 30"),
     *  2. mute/media silence,
     *  3. direction ("turn the volume down").
     *
     * ORDER MATTERS: "volume up" contains the word "volume", so the level rule
     * must reject non-numeric tails; and "mute the media" must not reach the
     * sound-mode stage (it cannot — that stage runs earlier only for phone modes).
     */
    private fun parseVolume(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized

        parseSetVolume(phrase)?.let { return it }

        if (muteMedia(phrase)) return AssistantCommand.MuteMedia
        if (unmuteMedia(phrase)) return AssistantCommand.UnmuteMedia

        // PHASE 6.5 (D4): two different questions are asked, with two different
        // rules — mixing them is exactly the bug that was fixed.
        //  1. the BARE comparatives ("louder", "quieter") must be the WHOLE
        //     utterance, so "the movie was louder than I expected" is a sentence;
        //  2. the ANCHORED phrasings ("turn the volume down") may appear inside a
        //     longer sentence, because their verb already carries the intent.
        if (BARE_VOLUME_DOWN.any { it.matches(phrase) }) return AssistantCommand.VolumeDown
        if (BARE_VOLUME_UP.any { it.matches(phrase) }) return AssistantCommand.VolumeUp

        if (VOLUME_DOWN_PATTERNS.any { it.containsMatchIn(phrase) }) {
            return AssistantCommand.VolumeDown
        }
        if (VOLUME_UP_PATTERNS.any { it.containsMatchIn(phrase) }) {
            return AssistantCommand.VolumeUp
        }
        return null
    }

    /**
     * PHASE 6.5 (A3) — "unmute", "unmute the media", "turn the sound back on".
     *
     * WHOLE-UTTERANCE ONLY, for the same reason as [muteMedia]: "unmute it" is a
     * sentence. The ringer mirror ("unmute the phone") is claimed earlier by the
     * sound-mode stage, so media unmuting here can never silence a call.
     */
    private fun unmuteMedia(phrase: String): Boolean {
        if (phrase == "unmute") return true
        return UNMUTE_MEDIA_PATTERNS.any { it.matches(phrase) }
    }

    /**
     * "mute", "mute the media", "silence the music" — never the phone.
     *
     * CONSERVATIVE BY CONSTRUCTION: a bare "mute" is accepted only when it *is*
     * the utterance; "mute the phone" was already claimed by the ringer rule
     * earlier, and anything else ("mute this for me") is a sentence → Unknown.
     */
    private fun muteMedia(phrase: String): Boolean {
        if (phrase == "mute") return true
        return MUTE_MEDIA_PATTERNS.any { it.matches(phrase) }
    }

    /**
     * Removes [phrase] from the *end* of [text], keeping the rest as written.
     * Null when nothing would remain (Phase 6.5, D3: "open app" is not a name).
     */
    private fun stripTrailingPhrase(text: String, phrase: String): String? {
        val wordCount = phrase.split(WHITESPACE).count { it.isNotBlank() }
        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size <= wordCount) return null
        return words.dropLast(wordCount).joinToString(" ").trim().takeIf { it.isNotBlank() }
    }

    /**
     * Removes [phrase] (a matchable, lowercase form) from the start of [text],
     * keeping the rest exactly as written. Null when nothing would remain.
     */
    private fun stripLeadingPhrase(text: String, phrase: String): String? {
        val wordCount = phrase.split(WHITESPACE).count { it.isNotBlank() }
        val words = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size <= wordCount) return null
        return words.drop(wordCount).joinToString(" ").trim().takeIf { it.isNotBlank() }
    }

    /**
     * Extracts an absolute volume level.
     *
     * NUMERIC RULES (Phase 5): digits only, no word numbers ("thirty" is not a
     * number here — that stays [AssistantCommand.Unknown]).
     *
     * PHASE 6.5 (D11): out-of-range values are **refused, never clamped**. A
     * misheard "-20" used to become a mute and a shouted "150" silently became
     * 100; both hid a request the user did not make. They now return
     * [AssistantCommand.Rejected] so the card can say
     * "Volume must be between 0 and 100 percent."
     */
    private fun parseSetVolume(phrase: String): AssistantCommand? {
        if (!phrase.contains("volume")) return null

        // Reject word numbers explicitly: "volume thirty" must be Unknown rather
        // than silently matching some other rule.
        if (WORD_NUMBER.containsMatchIn(phrase)) return null

        // PHASE 6.5 (D6): an EXPLICIT but unsupported stream is refused, not
        // silently redirected to media. "set the notification volume to 50"
        // applied to the media stream would be a silent wrong action.
        if (UNSUPPORTED_STREAMS.any { phrase.containsWord(it) }) return null

        val match = VOLUME_LEVEL.find(phrase) ?: return null
        val digits = match.groupValues[1].toIntOrNull()
            ?: return AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE)

        // PHASE 6.5 (D11): NO CLAMPING. A value outside 0..100 is refused with an
        // explanation. Clamping -20 to 0 would mute the phone from a typo, and
        // clamping 120 to 100 would hide that the request was impossible.
        if (digits !in 0..100) {
            return AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE)
        }

        val stream = when {
            phrase.containsWord("ring") || phrase.containsWord("ringer") -> AudioStream.RING
            phrase.containsWord("alarm") -> AudioStream.ALARM
            else -> AudioStream.MEDIA
        }

        return AssistantCommand.SetVolume(digits, stream)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Compound sentences (Phase 6.5, D10)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detects "do X and also Y" WITHOUT splitting it into two commands.
     *
     * ── WHY THIS IS TAIL-ANALYSIS AND NOT A SPLIT ────────────────────────────
     * Splitting on "and"/"," would destroy legitimate entities: "Rock and Roll",
     * "Bath and Bristol", "Berlin, Germany". So the utterance is never cut up;
     * instead the parser asks a narrower question — *after* a conjunction, does
     * the remainder of the sentence look like a command of its own? Only then is
     * the whole utterance refused.
     *
     * The tail counts as a command when either:
     *  1. it parses to a real command on its own, or
     *  2. it opens with a command verb-anchor AND carries meaningful content
     *     ("turn off wifi", "open chrome"). Case 2 exists because some of those
     *     tails are not command *shapes* yet (there is no "turn off wifi" rule),
     *     but they are unmistakably a second request.
     *
     * Returns a [AssistantCommand.Rejected] or null. Pure, no state.
     */
    private fun parseCompoundTail(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        if (!CONJUNCTION.containsMatchIn(phrase)) return null

        val tail = compoundTailAfter(phrase) ?: return null
        if (tail.isEmpty()) return null

        if (isRecognisableCommand(tail)) {
            return AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE)
        }

        val anchor = COMPOUND_ANCHORS.firstOrNull { tail.startsWithWord(it) } ?: return null
        // StartsWithWord also matches the tail being *only* the anchor, so the
        // removal is guarded against an empty remainder.
        val content = tail.substring(anchor.length).trim()
        // "open and shut" — an anchor with no object is not a second request.
        if (content.isEmpty() || content in NON_ENTITIES) return null

        return AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE)
    }

    /**
     * The words after the **last** conjunction, or null when the conjunction is
     * part of an entity rather than a sentence boundary.
     *
     * WHY THE LAST ONE: in "set the volume to 30 and turn off wifi" the second
     * request is the tail. Taking the last conjunction also means "play Rock and
     * Roll and Dance" reports the more useful tail.
     */
    private fun compoundTailAfter(phrase: String): String? {
        val match = CONJUNCTION.findAll(phrase).lastOrNull() ?: return null
        return phrase.substring(match.range.last + 1).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * True when [tail] parses, on its own, to a command ARIA would actually carry
     * out.
     *
     * WHY IT EXCLUDES [AssistantCommand.Unknown] AND [AssistantCommand.Rejected]:
     * the question is "is this a second request?", and an unparseable tail is not.
     * Recursion terminates because [parse] only reaches this method after a
     * command has been found, and the tail is always strictly shorter than the
     * whole utterance.
     */
    private fun isRecognisableCommand(tail: String): Boolean {
        val command = parse(tail)
        return command !is AssistantCommand.Unknown && command !is AssistantCommand.Rejected
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Music — transport first, then "play"
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseMusicTransport(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized

        return when {
            PAUSE_PATTERNS.any { it.matches(phrase) } -> AssistantCommand.PauseMusic
            RESUME_PATTERNS.any { it.matches(phrase) } -> AssistantCommand.ResumeMusic
            NEXT_PATTERNS.any { it.matches(phrase) } -> AssistantCommand.NextTrack
            PREVIOUS_PATTERNS.any { it.matches(phrase) } -> AssistantCommand.PreviousTrack
            else -> null
        }
    }

    /**
     * "play X" → [AssistantCommand.PlayMusic].
     *
     * The bare word "play" is [AssistantCommand.Unknown] (the brief is explicit):
     * there is no query and no clear intent, so guessing would be a false
     * positive. The entity keeps the user's capitalisation.
     */
    private fun parsePlayMusic(text: NormalizedText): AssistantCommand? {
        val phrase = text.normalized
        if (!phrase.startsWithWord("play")) return null

        val query = TextNormalizer.entityAfterPrefix(text, "play") ?: return null

        // "play the song X" / "play song X" / "play the track X": the leading
        // article + noun is command syntax, not part of the song title.
        val trimmed = SONG_PREFIXES.fold(query) { entity, prefix ->
            if (entity.lowercase().startsWithWord(prefix)) {
                // Drop the phrase; if it was the whole query ("play some music"),
                // keep the query as said rather than an empty title.
                stripLeadingPhrase(entity, prefix) ?: entity
            } else {
                entity
            }
        }

        if (trimmed.lowercase() in NON_ENTITIES) return null
        return AssistantCommand.PlayMusic(trimmed)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** Words that can never be an entity on their own. */
        val NON_ENTITIES = setOf(
            "something", "anything", "nothing", "stuff", "it", "them",
            "a", "an", "the", "my", "your", "some", "please"
        )

        /** Everyday words that do not name an app when they follow "open". */
        val STOP_WORDS = setOf(
            "something", "anything", "nothing", "it", "them", "that", "this",
            "up", "down", "app", "apps", "settings"
        )

        /** Digits-only word forms rejected on purpose (word numbers are Phase 5+). */
        val WORD_NUMBER = Regex(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
                "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|" +
                "twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred)\\b"
        )

        val OPEN_VERBS = listOf("open", "launch", "start", "go to", "show")

        // Accepts "wifi", "wi-fi", "wi fi" and "wireless" — all four are said.
        val WIFI_SETTINGS = Regex("\\b(wi[\\s-]?fi|wireless)\\s+settings\\b")
        val BLUETOOTH_SETTINGS = Regex("\\b(bluetooth|bt)\\s+settings\\b")
        val BATTERY_SETTINGS = Regex("\\bbattery\\s+settings\\b")

        val MAPS_PHRASE = Regex("(open|launch|start)\\s+(the\\s+)?(google\\s+)?maps")

        val NAVIGATION_ANCHORS = listOf(
            "navigate to",
            "navigate",
            "directions to",
            "take me to",
            "drive me to",
            "route to",
            "route me to"
        )

        val SEARCH_ANCHORS = listOf(
            "search for",
            "search",
            "look for",
            "find"
        )

        val APP_PREFIXES = listOf("open", "launch", "start")

        val NORMAL_RING_PATTERNS = listOf(
            Regex("\\bnormal\\s+ring\\s+mode\\b"),
            Regex("\\b(turn|switch)\\s+(the\\s+)?ringtone\\s+(back\\s+)?on\\b"),
            Regex("\\b(turn\\s+off|disable|exit|stop)\\s+(the\\s+)?silent\\s+mode\\b"),
            Regex("\\b(turn\\s+off|disable)\\s+silent\\b")
        )

        val VIBRATE_PATTERNS = listOf(
            Regex("\\bvibrate\\s+mode\\b"),
            Regex("\\bturn\\s+on\\s+vibrate\\b"),
            Regex("\\bswitch\\s+to\\s+vibrate\\b")
        )

        /** Silent-mode phrasings that are unambiguous inside a longer sentence. */
        val ELABORATE_SILENT_PATTERNS = listOf(
            Regex("\\b(turn|switch)\\s+(the\\s+|my\\s+)?phone\\s+(to\\s+)?silent\\b"),
            Regex("\\b(turn|switch)\\s+(the\\s+|my\\s+)?phone\\s+(on\\s+)?silent\\b"),
            Regex("\\bput\\s+(the\\s+|my\\s+)?phone\\s+on\\s+silent\\b")
        )

        /**
         * PHASE 6.5 (A3): whole-utterance unmute phrasings for the media stream.
         *
         * "turn the sound back on" is deliberately absent — it is genuinely
         * ambiguous between media and ringer (see docs/PHASE6.5.md), so it stays
         * [AssistantCommand.Unknown] rather than guessing and getting it wrong.
         */
        val UNMUTE_MEDIA_PATTERNS = listOf(
            Regex("unmute\\s+(the\\s+)?(media|music|audio|sound)"),
            // "sound" is deliberately NOT an option here: "turn the sound back on"
            // is genuinely ambiguous between media and ringer, so it stays
            // [AssistantCommand.Unknown] instead of guessing.
            Regex("turn\\s+(the\\s+)?(media|music|audio)\\s+(back\\s+)?on"),
            Regex("unmute\\s+(the\\s+)?volume")
        )

        /**
         * PHASE 6.5 (D6): stream qualifiers ARIA does not control.
         *
         * A phrase that names one of these must be refused, never redirected to
         * the media stream — silently changing the wrong stream is worse than
         * saying "I didn't understand that".
         */
        val UNSUPPORTED_STREAMS = setOf(
            "notification", "notifications",
            "call", "calls", "in-call",
            "voice", "system", "speaker"
        )

        /** PHASE 6.5 (D10): app names that are really the settings screen. */
        val SETTINGS_APP_NAMES = setOf(
            "settings", "system settings", "the settings"
        )

        /** PHASE 6.5 (D10): sentence conjunctions that can join two commands. */
        val CONJUNCTION = Regex("\\b(and|then|also)\\b|,")

        /**
         * PHASE 6.5 (D10): verbs that open a command, used to spot a second
         * request in the tail of a sentence ("and turn off wifi").
         */
        val COMPOUND_ANCHORS = listOf(
            "turn", "open", "set", "play", "navigate", "mute", "unmute",
            "silence", "launch", "start", "find", "search", "increase",
            "decrease", "raise", "lower", "skip", "pause", "resume", "make"
        )

        val MUTE_MEDIA_PATTERNS = listOf(
            Regex("mute"),
            Regex("mute\\s+(the\\s+)?media"),
            Regex("(silence|mute)\\s+(the\\s+)?(media|music|audio|sound)"),
            Regex("mute\\s+(the\\s+)?volume")
        )

        val VOLUME_DOWN_PATTERNS = listOf(
            Regex("\\b(turn|bring|take)\\s+(the\\s+)?volume\\s+down\\b"),
            Regex("\\b(lower|decrease|reduce|drop)\\s+(the\\s+)?volume\\b"),
            Regex("\\bmake\\s+(it|the\\s+(phone|volume|music))\\s+(quieter|softer|lower)\\b"),
            Regex("\\bturn\\s+(it|the\\s+volume)\\s+down\\b"),
            Regex("\\bvolume\\s+down\\b"),
            Regex("\\b(decrease|reduce)\\s+(the\\s+)?(volume|sound)\\b")
        )

        /**
         * PHASE 6.5 (D4) — bare comparatives, **whole utterance only**.
         *
         * WHY A SEPARATE LIST: the previous `\bquieter\b` pattern was asked with
         * `containsMatchIn`, so any sentence containing the word changed the
         * volume. These are only ever tested with [Regex.matches], so "the movie
         * was louder than I expected" cannot match. "make it louder" is caught by
         * the anchored list above instead.
         */
        val BARE_VOLUME_DOWN = listOf(
            Regex("(quieter|softer|lower)(\\s+please)?")
        )

        val BARE_VOLUME_UP = listOf(
            Regex("(louder|higher)(\\s+please)?")
        )

        val VOLUME_UP_PATTERNS = listOf(
            Regex("\\b(turn|bring|take)\\s+(the\\s+)?volume\\s+up\\b"),
            Regex("\\b(raise|increase|boost)\\s+(the\\s+)?volume\\b"),
            Regex("\\bmake\\s+(it|the\\s+(phone|volume|music))\\s+(louder|higher)\\b"),
            Regex("\\bturn\\s+(it|the\\s+volume)\\s+up\\b"),
            Regex("\\bvolume\\s+up\\b"),
            Regex("\\b(increase|raise)\\s+(the\\s+)?(volume|sound)\\b")
        )

        /** "volume 30", "set ring volume to 40", "set the volume to 30 percent". */
        val VOLUME_LEVEL = Regex(
            "\\bvolume\\b(?:\\s+(?:of|to|at))?\\s*(-?\\d{1,4})\\b"
        )

        val PAUSE_PATTERNS = listOf(
            Regex("pause"),
            Regex("pause\\s+(the\\s+)?(music|song|track|playback)"),
            Regex("stop\\s+(the\\s+)?(music|song|track|playback)")
        )

        val RESUME_PATTERNS = listOf(
            Regex("resume"),
            Regex("resume\\s+(the\\s+)?(music|song|track|playback)"),
            Regex("continue\\s+(playing|the\\s+music|the\\s+song|playback)")
        )

        val NEXT_PATTERNS = listOf(
            // PHASE 6.5 (D5): "play next song" is transport, not a music search.
            // Transport is evaluated before parsePlayMusic, so these win.
            Regex("play\\s+(the\\s+)?next\\s+(song|track)"),
            Regex("next\\s+(song|track)"),
            Regex("(skip|play)\\s+(this\\s+|the\\s+)?(song|track)"),
            Regex("skip\\s+(this|the|to\\s+the)\\s+(song|track|next)")
        )

        val PREVIOUS_PATTERNS = listOf(
            // PHASE 6.5 (D5): "play previous track" is transport, not a search.
            Regex("play\\s+(the\\s+)?previous\\s+(song|track)"),
            Regex("play\\s+(the\\s+)?last\\s+(song|track)"),
            Regex("previous\\s+(song|track)"),
            Regex("(go\\s+)?back\\s+(one\\s+)?(song|track)"),
            Regex("go\\s+back"),
            Regex("last\\s+(song|track)"),
            Regex("replay\\s+(the\\s+)?(previous|last)\\s+(song|track)")
        )

        /**
         * Leading noise inside a play query, longest first.
         *
         * "the song", "some", "a" and "my favorite" are *how people ask*, not
         * what they asked for. "the" alone is NOT stripped: "play the Beatles"
         * must keep its article-as-name.
         */
        val SONG_PREFIXES = listOf(
            "the song",
            "some music",
            "some songs",
            "the track",
            "the music",
            "song",
            "track",
            "some",
            "a"
        ).sortedByDescending { it.length }

        /** True when [word] appears anywhere as a whole word. */
        fun String.containsWord(word: String): Boolean =
            Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(this)

        /** True when [word] is the last whole word (Phase 6.5, D3 "… app"). */
        fun String.endsWithWord(word: String): Boolean =
            Regex("\\b${Regex.escape(word)}\\s*$").containsMatchIn(this)
    }
}
