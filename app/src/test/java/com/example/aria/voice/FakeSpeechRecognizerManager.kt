package com.example.aria.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Test double for [SpeechRecognizerManager].
 *
 * WHY A FAKE AND NOT MOCKITO: the interface is intentionally tiny, and a fake
 * lets a test script a realistic session
 * (`ReadyForSpeech → PartialResult → EndOfSpeech → FinalResult`) while counting
 * calls, with no mocking library added to the project.
 */
class FakeSpeechRecognizerManager : SpeechRecognizerManager {

    private val mutableEvents = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = 16)
    override val events: Flow<SpeechEvent> = mutableEvents

    /** Flip to simulate a device with no speech service installed. */
    var recognitionAvailable: Boolean = true

    var startCount: Int = 0
        private set
    var stopCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set
    var releaseCount: Int = 0
        private set

    override fun isRecognitionAvailable(): Boolean = recognitionAvailable

    override fun startListening() {
        startCount++
    }

    override fun stopListening() {
        stopCount++
    }

    override fun cancel() {
        cancelCount++
    }

    override fun release() {
        releaseCount++
    }

    /** Plays a recogniser event as if it came from the platform. */
    fun emit(event: SpeechEvent) {
        check(mutableEvents.tryEmit(event)) { "Test double buffer overflow" }
    }

    /** Plays a complete, successful session. */
    fun emitSuccessfulSession(transcript: String) {
        emit(SpeechEvent.ReadyForSpeech)
        emit(SpeechEvent.PartialResult(transcript.take(transcript.length / 2)))
        emit(SpeechEvent.PartialResult(transcript))
        emit(SpeechEvent.EndOfSpeech)
        emit(SpeechEvent.FinalResult(transcript))
    }
}
