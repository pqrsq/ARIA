package com.example.aria.voice

/**
 * Human-readable sentences for speech outcomes.
 *
 * WHY NOT `res/values/strings.xml` (the project's normal rule):
 * the ViewModel owns `AssistantUiState.message`, and it must stay free of
 * Android types so it can be unit-tested on the JVM (no `Context`, therefore no
 * `stringResource`). Keeping the sentences next to the [SpeechError] taxonomy
 * also makes it obvious when a new error needs new wording. The whole app's
 * strings are moved into resources in one pass during Phase 15 (UI polish).
 */
object SpeechMessages {

    /** Shown after a transcript is captured but before any action exists. */
    const val CAPTURED_NOT_EXECUTED =
        "Speech captured. Understanding commands and performing actions comes in Phase 5."

    /** Standing in for "nothing was heard" in history rows. */
    const val NO_SPEECH_CAPTURED = "—"

    /** First time the permission is refused. */
    const val PERMISSION_DENIED =
        "ARIA needs the microphone to hear you. Tap the microphone to grant access."

    /** After "don't ask again": only Settings can re-enable the microphone. */
    const val PERMISSION_BLOCKED =
        "Microphone access is blocked. Allow it for ARIA in the system settings, then try again."

    /** Fixed line per [SpeechError]; never null, never technical. */
    fun forError(error: SpeechError): String = when (error) {
        SpeechError.PERMISSION_DENIED -> PERMISSION_DENIED
        SpeechError.NO_MATCH -> "I did not catch that. Tap the microphone and try again."
        SpeechError.SPEECH_TIMEOUT -> "I heard nothing. Tap the microphone and speak."
        SpeechError.AUDIO -> "The microphone could not be opened. Check that no other app is using it."
        SpeechError.NETWORK -> "Speech recognition needs a connection right now. Check your network and try again."
        SpeechError.RECOGNIZER_BUSY -> "The speech service is busy. Wait a moment and tap the microphone again."
        SpeechError.SERVICE_UNAVAILABLE -> "No speech recognition service is available on this device."
        SpeechError.UNKNOWN -> "Speech recognition stopped unexpectedly. Please try again."
    }
}
