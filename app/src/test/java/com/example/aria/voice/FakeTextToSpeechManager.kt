package com.example.aria.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Test double for [TextToSpeechManager].
 *
 * WHY A FAKE: the ViewModel's speak/mute logic must be provable on the JVM, with
 * no device and no Android engine. This records what ARIA tried to say and lets a
 * test script the engine's replies (`SpeakFinished`, `Unavailable`, …).
 *
 * PHASE 6.5 (D7): `speak` now returns an utterance id and takes a
 * [SpeakPriority], so the fake mints ids, records the priority each request used,
 * and (like the real engine) raises `SpeakStarted(id)` synchronously.
 */
class FakeTextToSpeechManager(
    readyInitially: Boolean = true
) : TextToSpeechManager {

    private val mutableEvents = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    override val events: Flow<TtsEvent> = mutableEvents

    override var isReady: Boolean = readyInitially

    /** Everything ARIA asked the engine to say, in order. */
    val spoken: MutableList<String> = mutableListOf()

    /** The priority of each request, parallel to [spoken] (Phase 6.5, D7). */
    val priorities: MutableList<SpeakPriority> = mutableListOf()

    /** Ids handed out, parallel to [spoken], so tests can emit for one of them. */
    val utteranceIds: MutableList<String> = mutableListOf()

    var stopCount: Int = 0
        private set
    var shutdownCount: Int = 0
        private set

    /** Set false to simulate an engine that rejects the request. */
    var acceptsSpeak: Boolean = true

    private var counter = 0

    override fun speak(text: String, priority: SpeakPriority): String? {
        if (!isReady || !acceptsSpeak) return null
        val id = "fake-${++counter}"
        spoken += text
        priorities += priority
        utteranceIds += id
        mutableEvents.tryEmit(TtsEvent.SpeakStarted(id))
        return id
    }

    override fun stop() {
        stopCount++
    }

    override fun shutdown() {
        shutdownCount++
        isReady = false
    }

    /** Plays an engine event as if it came from the platform. */
    fun emit(event: TtsEvent) {
        check(mutableEvents.tryEmit(event)) { "Test double buffer overflow" }
    }

    /** Simulates the engine finishing whatever it was asked to say. */
    fun finishSpeaking() = emit(TtsEvent.SpeakFinished())

    /** Simulates finishing a *specific* utterance (Phase 6.5, D7). */
    fun finishSpeaking(utteranceId: String) = emit(TtsEvent.SpeakFinished(utteranceId))

    /** The id of the most recent request, or null when nothing was spoken. */
    fun lastUtteranceId(): String? = utteranceIds.lastOrNull()

    /** The priority used by the most recent request. */
    fun lastPriority(): SpeakPriority? = priorities.lastOrNull()
}
