package com.example.aria.voice

/**
 * PHASE 3 — what ARIA says back, before it understands anything.
 *
 * WHY THIS IS ITS OWN FILE: choosing the reply is pure logic with no Android and
 * no state, so it is the one part of TTS that can be tested exhaustively on the
 * JVM. Phase 5 replaces this object's *contents* with real command results; the
 * call site in the ViewModel does not change.
 *
 * WHY IT ROTATES: repeating "Done." after every single utterance sounds broken.
 * Cycling through a few neutral acknowledgements keeps the interaction feeling
 * alive while staying honest — none of them claims a command was performed.
 */
object TtsAcknowledgements {

    /** Neutral, truthful replies. Deliberately short: TTS latency is audible. */
    val ALL: List<String> = listOf(
        "I heard you.",
        "Done.",
        "Got it."
    )

    /**
     * The reply for turn [turnIndex] (0-based).
     *
     * Uses `floorMod` so a negative index (which a counter overflow could in
     * theory produce) wraps to a real entry instead of throwing.
     */
    fun forTurn(turnIndex: Int): String = ALL[Math.floorMod(turnIndex, ALL.size)]
}
