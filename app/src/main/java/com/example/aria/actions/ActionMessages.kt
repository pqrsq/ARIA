package com.example.aria.actions

/**
 * PHASE 6 — the sentences ARIA says about actions, in one place.
 *
 * WHY NOT `res/values/strings.xml` (the project's normal rule): these strings are
 * produced by classes that must stay unit-testable on the plain JVM, with no
 * `Context` and therefore no `stringResource`. The same exception is already made
 * for [com.example.aria.voice.SpeechMessages] and
 * [com.example.aria.voice.TtsMessages]; Phase 15 moves every user-facing string
 * into resources in one pass, when the ViewModel gains a `Context`-free
 * indirection.
 *
 * WHY CONSTANTS AND NOT LITERALS INSIDE HANDLERS: several of these sentences are
 * asserted verbatim by tests and quoted by the documentation. One definition
 * means the wording can never drift between the code, the tests and the docs.
 */
object ActionMessages {

    /** Shown when a sound-mode change needs Do Not Disturb access. */
    const val DO_NOT_DISTURB_REQUIRED =
        "ARIA needs Do Not Disturb access to change sound modes."

    /** This device does not let apps change volume at all. */
    const val VOLUME_FIXED =
        "This device keeps its volume fixed, so ARIA cannot change it."

    /** No app on the device can open a map. */
    const val NO_MAP_APP = "No map app is installed"

    /** Hand-off to the map app with no query. Says "opening", never "opened". */
    const val OPENING_MAPS = "Opening your map app."

    /** No app on the device can play music. */
    const val NO_MUSIC_APP = "No music app could be opened on this device."

    /** The parser found nothing it trusted. */
    const val NOT_UNDERSTOOD = "I didn't understand that command."

    /** No app matched the spoken name. */
    fun appNotFound(appName: String): String = "Could not find an app named \"$appName\"."

    /**
     * Ask an app to open a location search.
     *
     * Says "opening", never "searched": we hand the query to another app and
     * cannot know what it did with it.
     */
    fun mapSearch(query: String): String = "Opening a map search for \"$query\"."

    /** Turn-by-turn hand-off. Wording is fixed by the Phase 6 specification. */
    fun navigation(destination: String): String =
        "Opening navigation to $destination in your map app."

    /** The executor could not even dispatch to a media app. */
    const val MEDIA_UNAVAILABLE = "ARIA could not reach the media app."

    /** A handler threw unexpectedly; the ViewModel's safety net uses this. */
    const val UNEXPECTED = "Something went wrong while carrying out that command."

    // ── PHASE 6.5 ────────────────────────────────────────────────────────────

    /**
     * D10 — two commands in one breath.
     *
     * Says "one at a time" rather than "I didn't understand": the words *were*
     * understood, and pretending otherwise would teach the user to repeat the
     * same sentence louder.
     */
    const val ONE_COMMAND_AT_A_TIME = "I can only handle one command at a time."

    /** D11 — a volume outside the range the model allows. */
    const val VOLUME_OUT_OF_RANGE = "Volume must be between 0 and 100 percent."

    /**
     * D8 — ring volume refused because Do Not Disturb access is missing.
     *
     * Names *why* rather than saying "failed": the user can act on a permission,
     * but not on a mystery.
     */
    const val RING_VOLUME_BLOCKED =
        "ARIA needs Do Not Disturb access to change the ring volume."
}
