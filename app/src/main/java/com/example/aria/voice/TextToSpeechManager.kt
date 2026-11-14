package com.example.aria.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * PHASE 3 — the only place in ARIA that talks to Android's [TextToSpeech].
 *
 * WHY THIS FILE EXISTS
 * Same rule as Phase 2: no `android.*` type may cross into the ViewModel or the
 * Compose tree. Android's engine hands back integer status codes, calls an
 * `OnInitListener` asynchronously and notifies through a
 * `UtteranceProgressListener` on an internal thread. All of that stops here and
 * leaves as the small, Android-free vocabulary below ([TtsEvent] / [TtsError]).
 *
 * WHAT THIS FILE DOES NOT DO
 * It does not decide *what* to say (that is
 * [TtsAcknowledgements]/[com.example.aria.ui.AssistantViewModel]) and it does not
 * parse anything. It turns a `String` into sound and reports what happened.
 */

/** Why the engine could not be used, expressed without Android's integers. */
enum class TtsError {
    /** No engine installed, or the engine refused to initialise. */
    ENGINE_INIT_FAILED,

    /** An engine exists but does not have the en-US voice data. */
    LANGUAGE_NOT_SUPPORTED,

    /** The engine accepted initialisation but rejected a speak request. */
    SPEAK_FAILED
}

/**
 * Everything the ViewModel is allowed to know about the speaking side.
 *
 * A session goes [Ready] → [SpeakStarted] → [SpeakFinished], or ends in
 * [Unavailable] / [SpeakFailed]. Both failure events are informational: the
 * ViewModel shows a notice and carries on. Nothing here can crash the app.
 */
sealed interface TtsEvent {
    /** The engine initialised and the en-US voice is present. */
    data object Ready : TtsEvent

    /** The engine cannot be used. [error] says why. */
    data class Unavailable(val error: TtsError) : TtsEvent

    /**
     * Audio for one utterance has begun.
     *
     * [utteranceId] identifies WHICH utterance (Phase 6.5, D7). The ViewModel
     * tracks the utterance it is waiting for and ignores events for any other,
     * so a late callback from a superseded reply cannot rewrite a newer card.
     */
    data class SpeakStarted(val utteranceId: String? = null) : TtsEvent

    /** The utterance finished, or was stopped by ARIA itself. */
    data class SpeakFinished(val utteranceId: String? = null) : TtsEvent

    /** A speak request was rejected or failed mid-utterance. */
    data class SpeakFailed(val utteranceId: String? = null) : TtsEvent
}

/**
 * PHASE 6.5 (D7) — how a new utterance relates to whatever is already speaking.
 *
 * WHY THIS EXISTS: the acknowledgement ("I heard you.") is spoken *before* the
 * action runs, and the result sentence follows milliseconds later. With a single
 * "interrupt" behaviour the result cut the acknowledgement off mid-word, which
 * sounded like a glitch. Two modes are needed, and they must be visible at the
 * call site rather than hidden in the engine wrapper.
 */
enum class SpeakPriority {
    /**
     * Interrupt whatever is playing (platform `QUEUE_FLUSH`).
     *
     * Used for a NEW user utterance: barge-in is a feature — ARIA must stop
     * talking when the user starts speaking.
     */
    REPLACE,

    /**
     * Speak after the current utterance finishes (platform `QUEUE_ADD`).
     *
     * Used for the result sentence, so it never cuts the acknowledgement.
     */
    APPEND
}

/**
 * The seam the ViewModel depends on.
 *
 * WHY AN INTERFACE: `AssistantViewModelTest` drives the whole speak/no-speak
 * logic on the JVM with a fake, with no device and no Android framework — the
 * same trick that made Phase 2 testable.
 */
interface TextToSpeechManager {

    /** Engine lifecycle and utterance events. Hot: collect before speaking. */
    val events: Flow<TtsEvent>

    /** True once the engine is initialised and en-US is available. */
    val isReady: Boolean

    /**
     * Speaks [text]. Whether it interrupts or queues behind the current utterance
     * is the implementation's decision — see [SpeakPriority].
     *
     * @return the utterance id when something was queued, or null when nothing
     *   was (engine not ready, or shut down). Callers must treat null as "no
     *   sound", never as an error to crash on. Phase 6.5 (D7): returning the id
     *   lets the ViewModel match later events to the utterance it is waiting for.
     */
    fun speak(text: String, priority: SpeakPriority = SpeakPriority.REPLACE): String?

    /** Stops the current utterance immediately, if any. */
    fun stop()

    /** Releases the platform engine. The manager is unusable afterwards. */
    fun shutdown()
}

/**
 * Thin wrapper around [TextToSpeech].
 *
 * INITIALISATION IS ASYNCHRONOUS: Android binds to a speech engine service and
 * answers later. Construction therefore returns before the engine is usable, so
 * [isReady] starts false and flips to true when [TtsEvent.Ready] arrives.
 *
 * THREADING: the engine is created and driven on the main thread — Android
 * delivers `OnInitListener` on the constructing thread's looper, so creating it
 * anywhere else can silently lose the callback. `UtteranceProgressListener`
 * fires on an internal TTS thread, so those callbacks are marshalled back to
 * main before they become events.
 *
 * LIFECYCLE: one instance per process (owned by `AriaApplication`). The engine
 * is a bound system service, not an Activity, so it cannot leak a screen; the
 * ViewModel calls [stop] and the Application calls [shutdown].
 *
 * @param language the only language ARIA speaks in Version 1. en-US by design.
 */
class AndroidTextToSpeechManager(
    context: Context,
    private val language: Locale = Locale.US
) : TextToSpeechManager {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicInteger(0)

    private val mutableEvents = MutableSharedFlow<TtsEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<TtsEvent> = mutableEvents.asSharedFlow()

    private var engine: TextToSpeech? = null
    private var isShutdown = false

    @Volatile
    private var ready = false
    override val isReady: Boolean get() = ready && !isShutdown

    init {
        initialise()
    }

    override fun speak(text: String, priority: SpeakPriority): String? {
        val engine = engine
        if (isShutdown || !ready || engine == null || text.isBlank()) return null

        val utteranceId = "aria-${utteranceCounter.incrementAndGet()}"
        val queueMode = when (priority) {
            SpeakPriority.REPLACE -> TextToSpeech.QUEUE_FLUSH
            SpeakPriority.APPEND -> TextToSpeech.QUEUE_ADD
        }

        val result = try {
            engine.speak(text, queueMode, null, utteranceId)
        } catch (illegalState: IllegalStateException) {
            // Defensive: an engine torn down mid-flight throws instead of failing.
            Log.w(TAG, "speak() rejected for $utteranceId", illegalState)
            mutableEvents.tryEmit(TtsEvent.SpeakFailed(utteranceId))
            return null
        }

        if (result != TextToSpeech.SUCCESS) {
            // Requirement: log and carry on. Never crash because audio failed.
            Log.w(TAG, "speak() returned $result for $utteranceId")
            mutableEvents.tryEmit(TtsEvent.SpeakFailed(utteranceId))
            return null
        }

        // Report immediately rather than waiting for onStart, so the UI reacts
        // the instant the user asks; the listener's onStart is idempotent.
        mutableEvents.tryEmit(TtsEvent.SpeakStarted(utteranceId))
        return utteranceId
    }

    override fun stop() {
        val engine = engine
        if (isShutdown || engine == null) return
        runCatching { engine.stop() }
        // Reflect the stop in the UI even if the platform stays silent about it.
        // No id: this event is about "everything stopped", not one utterance.
        if (ready) mutableEvents.tryEmit(TtsEvent.SpeakFinished())
    }

    override fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        ready = false
        val current = engine
        current?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        engine = null
    }

    /**
     * Binds to the platform engine.
     *
     * WHY IT IS POSTED: `TextToSpeech`'s constructor may invoke the init callback
     * before the assignment to [engine] completes. Deferring the handling by one
     * main-thread message guarantees the field is set first.
     */
    private fun initialise() {
        if (isShutdown) return
        mainHandler.post {
            if (isShutdown || engine != null) return@post
            try {
                engine = TextToSpeech(appContext) { status ->
                    mainHandler.post { onEngineInitialised(status) }
                }
            } catch (error: Exception) {
                // No TTS service on this device (or it refused the connection).
                Log.w(TAG, "TextToSpeech could not be created", error)
                mutableEvents.tryEmit(TtsEvent.Unavailable(TtsError.ENGINE_INIT_FAILED))
            }
        }
    }

    private fun onEngineInitialised(status: Int) {
        val engine = engine
        if (isShutdown || engine == null) return

        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech init failed with status $status")
            mutableEvents.tryEmit(TtsEvent.Unavailable(TtsError.ENGINE_INIT_FAILED))
            return
        }

        val languageResult = runCatching { engine.setLanguage(language) }.getOrElse { -1 }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            // An engine exists, but it cannot pronounce en-US: useless for ARIA.
            Log.w(TAG, "en-US unavailable on this engine (result $languageResult)")
            mutableEvents.tryEmit(TtsEvent.Unavailable(TtsError.LANGUAGE_NOT_SUPPORTED))
            return
        }

        engine.setOnUtteranceProgressListener(progressListener)
        ready = true
        mutableEvents.tryEmit(TtsEvent.Ready)
    }

    /**
     * Platform utterance callbacks → [TtsEvent].
     *
     * These arrive on a TTS-internal thread, never the main one, so each is
     * posted back before it touches the flow.
     */
    private val progressListener = object : UtteranceProgressListener() {

        override fun onStart(utteranceId: String?) {
            mainHandler.post { mutableEvents.tryEmit(TtsEvent.SpeakStarted(utteranceId)) }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post { mutableEvents.tryEmit(TtsEvent.SpeakFinished(utteranceId)) }
        }

        // Superseded by onError(String, Int) but still called by some engines.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            Log.w(TAG, "Utterance failed: $utteranceId")
            mainHandler.post { mutableEvents.tryEmit(TtsEvent.SpeakFailed(utteranceId)) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "Utterance failed: $utteranceId (code $errorCode)")
            mainHandler.post { mutableEvents.tryEmit(TtsEvent.SpeakFailed(utteranceId)) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            mainHandler.post { mutableEvents.tryEmit(TtsEvent.SpeakFinished(utteranceId)) }
        }
    }

    private companion object {
        const val TAG = "AriaTts"
    }
}
