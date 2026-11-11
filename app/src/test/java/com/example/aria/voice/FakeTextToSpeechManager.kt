package com.example.aria.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Test double for [TextToSpeechManager].
 *
 * WHY A FAKE: the ViewModel's speak/mute logic must be provable on the JVM, with
 * no device and no Android engine. This records what ARIA tried to say and lets a
 * test script the engine's replies (`SpeakFinished`, `Unavailable`, …).
 */
class FakeTextToSpeechManager(
    readyInitially: Boolean = true
) : TextToSpeechManager {

    private val mutableEvents = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    override val events: Flow<TtsEvent> = mutableEvents

    override var isReady: Boolean = readyInitially

    /** Everything ARIA asked the engine to say, in order. */
    val spoken: MutableList<String> = mutableListOf()

    var stopCount: Int = 0
        private set
    var shutdownCount: Int = 0
        private set

    /** Set false to simulate an engine that rejects the request. */
    var acceptsSpeak: Boolean = true

    override fun speak(text: String): Boolean {
        if (!isReady || !acceptsSpeak) return false
        spoken += text
        mutableEvents.tryEmit(TtsEvent.SpeakStarted)
        return true
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
    fun finishSpeaking() = emit(TtsEvent.SpeakFinished)
}
