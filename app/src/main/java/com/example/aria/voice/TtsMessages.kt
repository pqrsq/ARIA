package com.example.aria.voice

/**
 * PHASE 3 — user-facing wording for the talking side.
 *
 * Same reasoning as [SpeechMessages]: the ViewModel owns
 * `AssistantUiState.message` and must stay free of Android types, so it cannot
 * call `stringResource`. Sentences live next to the [TtsError] taxonomy; the
 * Phase 15 polish pass moves all of them into `res/values/strings.xml` at once.
 */
object TtsMessages {

    /** Shown under the card when the engine is unusable. Never a stack trace. */
    const val ENGINE_UNAVAILABLE = "Text-to-Speech engine not available."

    /** Says what to do about it, rather than only what is wrong. */
    const val ENGINE_UNAVAILABLE_HINT =
        "ARIA cannot speak on this device. Replies are still shown as text."

    /** One-line notice when a single utterance fails but the engine survives. */
    const val SPEAK_FAILED = "ARIA could not speak that reply. The text is still above."

    /** Debug-block label for a manager that exists but is not initialised. */
    const val NOT_READY = "initialising"

    /** Fixed line per [TtsError]; never technical, never null. */
    fun forError(error: TtsError): String = when (error) {
        TtsError.ENGINE_INIT_FAILED -> ENGINE_UNAVAILABLE
        TtsError.LANGUAGE_NOT_SUPPORTED ->
            "Text-to-Speech engine not available for English (en-US)."
        TtsError.SPEAK_FAILED -> SPEAK_FAILED
    }
}
