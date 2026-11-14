package com.example.aria.command

/**
 * PHASE 5 — text normalisation for command matching.
 *
 * ── WHY TWO REPRESENTATIONS ──────────────────────────────────────────────────
 * Matching must be case-insensitive ("OPEN YOUTUBE" is the same intent as
 * "open youtube"), but the *entity* handed to a command must keep the way the
 * user said it: `OpenApp("YouTube")`, not `OpenApp("youtube")`. Lowercasing in
 * place destroys that irreversibly, so [NormalizedText] keeps both views.
 *
 * The parser matches on [NormalizedText.normalized] and extracts entities from
 * [NormalizedText.entitySource] by dropping the words of the matched prefix.
 *
 * ── THE SUBTLE PART: KEEPING THE TWO VIEWS ALIGNED ───────────────────────────
 * Entity extraction works by *word position*: "play Shape of You" minus the
 * one-word prefix "play" leaves "Shape of You". That only works if the string
 * being sliced has the same word sequence the prefix was found in.
 *
 * Lifecycle and wake words ("please", "hey aria") break that alignment if they
 * are stripped from the normalised view only: for "please play music" the
 * normalised text is "play music", so a one-word prefix would slice "please" off
 * the *original* and hand back "play music" as the song.
 *
 * So the filler prefix is removed from **both** views, in parallel, before
 * anything is matched: [entitySource] is the original text with the same leading
 * filler words dropped, keeping the user's capitalisation. The untouched input is
 * still available to the parser (it is the function argument), which is what
 * [AssistantCommand.Unknown] carries.
 *
 * ── WHAT IS REMOVED AND WHY ──────────────────────────────────────────────────
 * Only politeness and wake words at the *start* of the utterance. Words such as
 * "the", "my" and "some" are never removed, because they belong to the entity
 * ("play my favorite song" must keep "my favorite song").
 *
 * ── PURITY ───────────────────────────────────────────────────────────────────
 * Pure Kotlin, no state, no Android, no logging. Same input, same output, always.
 */
object TextNormalizer {

    /**
     * Leading noise, longest first so a multi-word filler wins over its own first
     * word ("hey aria" before "hey", "could you please" before "could you").
     */
    private val LEADING_FILLERS: List<String> = listOf(
        "hey aria",
        "okay aria",
        "ok aria",
        "hey",
        "aria",
        "please",
        "could you please",
        "can you please",
        "would you please",
        "could you",
        "can you",
        "would you",
        "will you",
        "kindly"
    ).sortedByDescending { it.length }

    /** Punctuation that belongs to the sentence, not to an entity. */
    internal const val PUNCTUATION = ".,!?;:\u061F\u060C"

    internal val WHITESPACE = Regex("\\s+")

    /**
     * Produces all three views of [raw].
     *
     * [NormalizedText.original] is trimmed of surrounding whitespace only — that
     * is not a modification of the user's words, it is removing what a
     * recogniser added around them.
     */
    fun normalize(raw: String): NormalizedText {
        val original = raw.trim()
        val entitySource = stripLeadingFillers(original)
        return NormalizedText(
            original = original,
            entitySource = entitySource,
            // PHASE P1.2: trailing politeness is removed from the MATCHING view
            // only. Removing it from [entitySource] too would rename entities —
            // "play Please Please Please" would lose its last word — so entities
            // are tidied only by the comma rule in [entityAfterPrefix] and by the
            // parser's explicit app-name strip.
            normalized = toMatchable(stripTrailingFillers(entitySource))
        )
    }

    /**
     * Lowercases, replaces sentence punctuation with spaces and collapses runs of
     * whitespace. Hyphens are kept: "wi-fi" is one word, not two.
     */
    fun toMatchable(text: String): String = text
        .lowercase()
        .map { character -> if (character in PUNCTUATION) ' ' else character }
        .joinToString("")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * Removes leading politeness / wake words from [text], preserving the
     * capitalisation of everything after them.
     *
     * WHY A LOOP: "please could you open youtube" has three fillers in a row.
     * Each pass removes at most one prefix and the loop stops when nothing
     * matches, so it can never spin.
     */
    fun stripLeadingFillers(text: String): String {
        var result = text
        var changed = true
        while (changed) {
            changed = false
            // A filler is compared on its own matchable form so "Aria," and
            // "ARIA" are both recognised, but the removal itself is positional:
            // exactly as many words as the filler contains.
            val matchable = toMatchable(result)
            for (filler in LEADING_FILLERS) {
                if (matchable == filler) return ""
                if (!matchable.startsWithWord(filler)) continue

                val fillerWordCount = filler.split(WHITESPACE).size
                val words = result.split(WHITESPACE).filter { it.isNotBlank() }
                result = words.drop(fillerWordCount).joinToString(" ")
                changed = true
                break
            }
        }
        return result
    }

    /**
     * PHASE P1.2 — removes trailing politeness ("pause the music please").
     *
     * WHERE IT IS USED: only on the MATCHING view of an utterance (see
     * [normalize]). Entity text is never run through this, because dropping the
     * last word of "play Please Please Please" would rename a song.
     *
     * A single phrase is removed per pass and the loop stops when nothing matches,
     * so "thanks, please" is cleaned without recursion, and an utterance that is
     * *only* politeness still collapses to "" (the blank-input contract).
     */
    fun stripTrailingFillers(text: String): String {
        var result = text
        var changed = true
        while (changed) {
            changed = false
            val matchable = toMatchable(result)
            for (filler in TRAILING_COURTESY) {
                if (matchable == filler) return ""

                val fillerWordCount = filler.split(WHITESPACE).size
                val words = result.split(WHITESPACE).filter { it.isNotBlank() }
                if (words.size <= fillerWordCount) return ""
                result = words.dropLast(fillerWordCount).joinToString(" ")
                changed = true
                break
            }
        }
        return result
    }

    /**
     * Cuts the entity that follows a normalised [prefix] out of
     * [NormalizedText.entitySource].
     *
     * The entity starts after however many words [prefix] contains, which is why
     * this works when the user typed different capitalisation or extra
     * punctuation: normalisation only removes or collapses characters, so the
     * word counts line up.
     *
     * Returns null when there is nothing left, so a caller can never accidentally
     * produce an empty command from a missing entity.
     */
    fun entityAfterPrefix(normalized: NormalizedText, prefix: String): String? {
        val prefixWordCount = prefix.trim().split(WHITESPACE).count { it.isNotBlank() }
        if (prefixWordCount == 0) return null

        val words = normalized.entitySource.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.size <= prefixWordCount) return null

        val entity = stripTrailingCourtesyAfterComma(
            words
                .drop(prefixWordCount)
                .joinToString(" ")
                .trim()
        )
            .trimEnd { it in PUNCTUATION }
            .trim()

        return entity.takeIf { it.isNotBlank() }
    }
}

/** True when this matchable phrase starts with [word] as a whole word. */
internal fun String.startsWithWord(word: String): Boolean =
    this == word || this.startsWith("$word ")
/** True when [word] is the last whole word of this matchable phrase (Phase P1.2). */
internal fun String.endsWithWord(word: String): Boolean =
    this == word || this.endsWith(" $word")

/**
 * PHASE P1.2 — drops ", please" / ", thanks" / ", thank you" from the end of an
 * entity, **only when the comma is present**.
 *
 * WHY THE COMMA IS REQUIRED: without it, "play hello please" and "play Please
 * Please Please" have the same shape, and stripping would rename a song. A comma
 * is the speaker's own signal that the remainder is an aside, so it is the only
 * safe boundary. Documented consequence: "play hello please" (no comma) keeps
 * "please" in the title — an odd title is better than a silent rename.
 *
 * Positional, like every other trim in this file: words are dropped by count and
 * the rest keeps the user's own capitalisation.
 */
internal fun stripTrailingCourtesyAfterComma(text: String): String {
    var words = text.trim().split(TextNormalizer.WHITESPACE).filter { it.isNotBlank() }
    if (words.isEmpty()) return text

    var changed = true
    while (changed && words.isNotEmpty()) {
        changed = false
        for (phrase in TRAILING_COURTESY) {
            val phraseWords = phrase.split(TextNormalizer.WHITESPACE)
            if (words.size < phraseWords.size) continue

            // The courtesy phrase must really be at the end (case-insensitive)…
            val tail = words.takeLast(phraseWords.size).map { it.lowercase().trimEnd { c -> c in TextNormalizer.PUNCTUATION } }
            if (tail != phraseWords) continue

            // …and the word BEFORE it must carry the comma: that is the signal.
            val remaining = words.dropLast(phraseWords.size)
            if (remaining.isEmpty()) continue
            val last = remaining.last()
            if (!last.trimEnd().endsWith(",")) continue

            val trimmedLast = last.trimEnd().dropLast(1).trim()
            words = (remaining.dropLast(1) + listOf(trimmedLast)).filter { it.isNotBlank() }
            changed = true
            break
        }
    }
    return words.joinToString(" ")
}

/** PHASE P1.2 — courtesy words, longest first so "thank you" precedes "you". */
internal val TRAILING_COURTESY: List<String> = listOf("thank you", "thanks", "please")
    .sortedByDescending { it.length }

/**
 * The three views of one utterance.
 *
 * - [original] — the input, trimmed only. Used for [AssistantCommand.Unknown].
 * - [entitySource] — [original] with leading filler/wake words removed, keeping
 *   the user's capitalisation. Sliced for song titles, app names, destinations.
 * - [normalized] — lowercase, punctuation-free, whitespace-collapsed
 *   [entitySource]. Matched against by every rule.
 */
data class NormalizedText(
    val original: String,
    val entitySource: String,
    val normalized: String
) {
    /** True when nothing is left to match, e.g. `""`, `"   "` or `"please"`. */
    val isEmpty: Boolean get() = normalized.isEmpty()
}
