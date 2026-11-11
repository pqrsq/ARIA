package com.example.aria.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale

/**
 * PHASE 2 — the only place in ARIA that talks to Android's speech recogniser.
 *
 * WHY THIS FILE EXISTS
 * `Task 3` of the brief is "no Android types leak into the UI". Android's
 * [SpeechRecognizer] hands back raw `Bundle`s, integer error codes and callbacks
 * on the main thread; the ViewModel must never see any of that. Everything here
 * is therefore converted into the small, Android-free vocabulary below
 * ([SpeechEvent] / [SpeechError]), and delivered as a cold-ish [Flow].
 *
 * WHAT THIS FILE DOES NOT DO
 * No parsing, no command model, no execution, no TTS. It turns sound into a
 * `String` and stops there — exactly the left edge of the pipeline in
 * `docs/PROJECT_GUIDE.md`.
 */

/** Why recognition failed, expressed without Android's integer error codes. */
enum class SpeechError {
    /** `RECORD_AUDIO` was not actually granted when listening started. */
    PERMISSION_DENIED,

    /** The recogniser finished but heard nothing it could turn into words. */
    NO_MATCH,

    /** The user stayed silent for too long. */
    SPEECH_TIMEOUT,

    /** Microphone hardware / audio-recording problem. */
    AUDIO,

    /** The recognition service needed the network and could not reach it. */
    NETWORK,

    /** Another app (or a previous session) still holds the recogniser. */
    RECOGNIZER_BUSY,

    /** No speech recognition service installed or enabled on this device. */
    SERVICE_UNAVAILABLE,

    /** Anything the platform does not describe more precisely. */
    UNKNOWN
}

/**
 * Everything the ViewModel is allowed to know about one interaction with the
 * microphone. Ordered the way a real session happens:
 * [ReadyForSpeech] → [PartialResult]* → [EndOfSpeech] → [FinalResult] | [Failure].
 */
sealed interface SpeechEvent {
    /** The recogniser is open and waiting for the user to speak. */
    data object ReadyForSpeech : SpeechEvent

    /** Interim text. Shown live so the user can see ARIA is hearing them. */
    data class PartialResult(val text: String) : SpeechEvent

    /** The user stopped speaking; the final transcript is being produced. */
    data object EndOfSpeech : SpeechEvent

    /** The final transcript. This is the `String` that Phase 5 will parse. */
    data class FinalResult(val text: String) : SpeechEvent

    /** Recognition ended without a usable transcript. See [SpeechError]. */
    data class Failure(val error: SpeechError) : SpeechEvent
}

/**
 * The seam the ViewModel depends on.
 *
 * WHY AN INTERFACE: it lets `AssistantViewModelTest` drive the whole speech flow
 * on the JVM with a fake, so Phase 2 logic is unit-tested without a device, and
 * Phase 2's future `LlmCommandParser` siblings stay independent of Android.
 */
interface SpeechRecognizerManager {

    /** Events of the current (and future) sessions. Hot: collect before starting. */
    val events: Flow<SpeechEvent>

    /** False when the device has no recognition service at all. */
    fun isRecognitionAvailable(): Boolean

    /** Opens the microphone. Must be called on the main thread. */
    fun startListening()

    /** Asks for the final transcript of what has been said so far. */
    fun stopListening()

    /** Abandons the session; no results will be delivered afterwards. */
    fun cancel()

    /** Releases the platform recogniser. The manager is unusable afterwards. */
    fun release()
}

/**
 * Thin wrapper around [SpeechRecognizer].
 *
 * THREADING: `SpeechRecognizer` must be created and driven on the main thread,
 * so every public method marshals itself there. Callbacks are already main
 * thread, so events are emitted from there too.
 *
 * LIFECYCLE: one instance per ViewModel is enough. [release] destroys the
 * platform object; `cancel` (called when the app leaves the foreground) only
 * ends the current session so the mic is never left open.
 *
 * @param preferOfflineRecognition when true, hints the platform to use the
 *   on-device model. It is a *hint*: devices without a downloaded offline model
 *   answer with an error instead, which is why it defaults to false.
 * @param languageTag BCP-47 tag for recognition; device locale by default.
 */
class AndroidSpeechRecognizerManager(
    context: Context,
    private val preferOfflineRecognition: Boolean = false,
    private val languageTag: String = Locale.getDefault().toLanguageTag()
) : SpeechRecognizerManager {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // tryEmit from callbacks, buffered so a slow collector can never block the
    // recogniser; dropping the oldest partial is harmless.
    private val mutableEvents = MutableSharedFlow<SpeechEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<SpeechEvent> = mutableEvents.asSharedFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isReleased = false

    /**
     * True between [cancel] and the platform's follow-up error callback.
     *
     * WHY: `SpeechRecognizer.cancel()` *always* delivers `ERROR_CLIENT`
     * afterwards. Reporting that as a failure would show the user an error card
     * every time they backgrounded the app — so a cancelled session is silent.
     */
    private var isCancelling = false

    override fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    override fun startListening() {
        runOnMain {
            if (isReleased || isListening) return@runOnMain

            val recognizer = speechRecognizer ?: createRecognizer().also {
                speechRecognizer = it
            }

            isListening = true
            isCancelling = false
            try {
                recognizer.startListening(buildRecognizerIntent())
            } catch (illegalState: IllegalStateException) {
                // Most often "recognition is busy": a previous session was not
                // closed properly. Report it instead of crashing the app.
                isListening = false
                mutableEvents.tryEmit(SpeechEvent.Failure(SpeechError.RECOGNIZER_BUSY))
            }
        }
    }

    override fun stopListening() {
        runOnMain {
            if (isReleased || !isListening) return@runOnMain
            isListening = false
            // Result callbacks still arrive after stopListening(): that is the
            // final transcript the user expects.
            runCatching { speechRecognizer?.stopListening() }
        }
    }

    override fun cancel() {
        runOnMain {
            if (isReleased || !isListening) return@runOnMain
            isListening = false
            isCancelling = true
            runCatching { speechRecognizer?.cancel() }
        }
    }

    override fun release() {
        runOnMain {
            if (isReleased) return@runOnMain
            isReleased = true
            isListening = false
            isCancelling = true
            speechRecognizer?.let { recognizer ->
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
            speechRecognizer = null
        }
    }

    private fun createRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer ->
            recognizer.setRecognitionListener(platformListener)
        }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // Live "you said …" feedback while the user is still speaking.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (preferOfflineRecognition) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    /** Platform callbacks → [SpeechEvent]. Always invoked on the main thread. */
    private val platformListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            mutableEvents.tryEmit(SpeechEvent.ReadyForSpeech)
        }

        override fun onBeginningOfSpeech() {
            // Nothing to report: [SpeechEvent.ReadyForSpeech] already told the
            // UI that the microphone is open.
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Deliberately ignored: Version 1 has no audio level meter. Adding
            // one is a UI decision for Phase 15, not a speech concern.
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio is never retained or transmitted by ARIA.
        }

        override fun onEndOfSpeech() {
            mutableEvents.tryEmit(SpeechEvent.EndOfSpeech)
        }

        override fun onError(error: Int) {
            isListening = false
            if (isCancelling) {
                // Expected: the session was cancelled by ARIA itself.
                isCancelling = false
                return
            }
            mutableEvents.tryEmit(SpeechEvent.Failure(error.toSpeechError()))
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = results.firstTranscript().orEmpty()
            mutableEvents.tryEmit(SpeechEvent.FinalResult(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstTranscript()
            if (!text.isNullOrBlank()) {
                mutableEvents.tryEmit(SpeechEvent.PartialResult(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Reserved by the platform; ARIA does not use it.
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}

/** First (best) transcript from a recogniser `Bundle`, or null. */
private fun Bundle?.firstTranscript(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * Android's integer error codes → [SpeechError].
 *
 * Kept private to this file: the rest of the app must never branch on Android
 * constants. Several codes are grouped because the user-facing distinction is
 * the same ("that did not work, try again").
 */
private fun Int.toSpeechError(): SpeechError = when (this) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_DENIED
    SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.SPEECH_TIMEOUT
    SpeechRecognizer.ERROR_AUDIO -> SpeechError.AUDIO
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SpeechError.NETWORK
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechError.RECOGNIZER_BUSY
    SpeechRecognizer.ERROR_CLIENT -> SpeechError.SERVICE_UNAVAILABLE
    else -> SpeechError.UNKNOWN
}
