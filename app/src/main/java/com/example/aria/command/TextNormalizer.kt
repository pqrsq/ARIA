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
    private const val PUNCTUATION = ".,!?;:\u061F\u060C"

    private val WHITESPACE = Regex("\\s+")

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
            normalized = toMatchable(entitySource)
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

        val entity = words
            .drop(prefixWordCount)
            .joinToString(" ")
            .trim()
            .trimEnd { it in PUNCTUATION }
            .trim()

        return entity.takeIf { it.isNotBlank() }
    }
}

/** True when this matchable phrase starts with [word] as a whole word. */
internal fun String.startsWithWord(word: String): Boolean =
    this == word || this.startsWith("$word ")

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
