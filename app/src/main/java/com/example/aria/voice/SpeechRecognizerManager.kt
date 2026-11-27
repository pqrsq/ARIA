package com.example.aria.voice

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
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

/**
 * PHASE 1A — the single Logcat tag for recognition diagnostics.
 *
 * Filter with: `adb logcat -s ARIA_STT`
 *
 * Deliberately a `const` and emitted only from [AndroidSpeechRecognizerManager],
 * so the whole trace can be found (or removed) by one grep.
 */
private const val TAG_STT = "ARIA_STT"

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

    // ─────────────────────────────────────────────────────────────────────────
    // V1.1 PHASE 1A — RECOGNITION LIFECYCLE INSTRUMENTATION (OBSERVATION ONLY)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // WHY THIS EXISTS: on a real device "play face off" sometimes arrives as
    // "Face off", and speaking immediately after the tap is less reliable than
    // waiting a moment. The suspicious interval is between startListening() and
    // the platform's onReadyForSpeech()/onBeginningOfSpeech(): if the microphone
    // is not really open yet, the first word is simply not in the audio. That is
    // a HYPOTHESIS — this instrumentation exists to observe it, not to assume it.
    //
    // WHAT IT DOES NOT DO: nothing here feeds the pipeline, filters, merges,
    // re-orders, delays or suppresses anything. Every callback is logged and then
    // handled EXACTLY as before. No audio is captured, stored or transmitted, and
    // transcripts are never written anywhere except Logcat (see the gate below).
    //
    // WHY THE GATE: transcripts are the most sensitive data this app produces, and
    // R3-final spent a whole phase correcting privacy claims. Logging a user's
    // words unconditionally would make "ARIA stores no audio and persists nothing
    // it hears" quietly false on any shipped build. So diagnostics are compiled in
    // but only emitted when the running APK is `debuggable` — i.e. `installDebug`
    // and every on-device test build shows the trace, a release build shows
    // nothing at all. This is a logging gate, NOT a runtime behaviour switch: it
    // cannot change what is recognised.

    /** True only for a debuggable APK, so a release build logs no transcripts. */
    private val diagnosticsEnabled: Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Monotonic, never reused. 0 means "no session has started yet". */
    private var sessionCounter = 0

    /**
     * The session the callbacks currently belong to.
     *
     * Stays 0 when [diagnosticsEnabled] is false — deliberately: a non-debuggable
     * build does no session bookkeeping at all, so there is nothing for a release
     * APK to log even by mistake.
     */
    private var currentSession = 0

    /** [SystemClock.elapsedRealtime] captured immediately before startListening. */
    private var sessionStartElapsed = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // V1.1 PHASE 1C — SCALAR AUDIO-LEVEL STATISTICS (OBSERVATION ONLY)
    // ─────────────────────────────────────────────────────────────────────────
    //
    // WHY: the physical test split the failure by microphone — a Bluetooth headset
    // recognised 5/5, the phone's own microphone 1/5, with TTS off. That makes the
    // audio-input path the suspect. Every earlier trace could not separate two very
    // different upstream causes, because a truncated utterance and an utterance
    // that was never captured look identical from transcript callbacks alone.
    //
    // These statistics separate them. The one number that matters is the delta
    // between the level before speech starts and the level during it:
    //   - speech clearly above the pre-speech floor  -> audio ARRIVED; any loss is
    //     a boundary/decoding decision, and endpoint extras are the right lever;
    //   - no rise at all, or a flat near-silent trace -> the recogniser never
    //     received usable audio, and NO endpoint setting can fix that.
    //
    // WHY IT IS PRIVACY-SAFE: only scalars are kept — count, min, max, mean and a
    // few last values. No sample is stored, no buffer is copied, nothing is written
    // to disk or sent anywhere. `onBufferReceived` still discards every byte.
    //
    // WHY IT IS CHEAP: `onRmsChanged` fires many times a second on the main thread,
    // so the accumulator is allocation-free after construction and O(1) per call.
    // Nothing is formatted until the session ends.

    /** Level statistics for one recognition session. Reset, never re-allocated. */
    private class RmsTrace {

        /** How many recent values are kept so "around an event" means a window. */
        private val recent = FloatArray(RECENT_WINDOW)
        private var recentIndex = 0
        private var recentFilled = 0

        var count = 0
            private set
        var minD = Float.MAX_VALUE
            private set
        var maxD = -Float.MAX_VALUE
            private set
        private var sum = 0.0

        /** Last value seen, or null if the service never reported one. */
        var last: Float? = null
            private set

        // Before BEGINNING_OF_SPEECH.
        private var preCount = 0
        private var preSum = 0.0
        private var preMax = -Float.MAX_VALUE

        // Between BEGINNING_OF_SPEECH and END_OF_SPEECH (or session end).
        private var duringCount = 0
        private var duringSum = 0.0
        private var duringMax = -Float.MAX_VALUE

        // After END_OF_SPEECH.
        private var afterEndCount = 0
        private var afterEndSum = 0.0
        private var afterEndMax = -Float.MAX_VALUE

        var beginBefore: Float? = null
            private set
        var beginRecentMax: Float? = null
            private set
        var beginAfter: Float? = null
            private set
        var endBefore: Float? = null
            private set
        var endRecentMax: Float? = null
            private set

        var beginCount = 0
            private set
        var endCount = 0
            private set

        private var began = false
        private var ended = false
        private var awaitingFirstAfterBegin = false

        fun reset() {
            recentIndex = 0
            recentFilled = 0
            count = 0
            minD = Float.MAX_VALUE
            maxD = -Float.MAX_VALUE
            sum = 0.0
            last = null
            preCount = 0; preSum = 0.0; preMax = -Float.MAX_VALUE
            duringCount = 0; duringSum = 0.0; duringMax = -Float.MAX_VALUE
            afterEndCount = 0; afterEndSum = 0.0; afterEndMax = -Float.MAX_VALUE
            beginBefore = null; beginRecentMax = null; beginAfter = null
            endBefore = null; endRecentMax = null
            beginCount = 0; endCount = 0
            began = false; ended = false; awaitingFirstAfterBegin = false
        }

        fun onValue(value: Float) {
            count++
            if (value < minD) minD = value
            if (value > maxD) maxD = value
            sum += value
            last = value

            recent[recentIndex] = value
            recentIndex = (recentIndex + 1) % RECENT_WINDOW
            if (recentFilled < RECENT_WINDOW) recentFilled++

            when {
                awaitingFirstAfterBegin -> { beginAfter = value; awaitingFirstAfterBegin = false }
                ended -> { afterEndCount++; afterEndSum += value; if (value > afterEndMax) afterEndMax = value }
                began -> { duringCount++; duringSum += value; if (value > duringMax) duringMax = value }
                else -> { preCount++; preSum += value; if (value > preMax) preMax = value }
            }
        }

        /** Max of the last few values, i.e. the level in the moments just before now. */
        private fun recentMax(): Float? =
            if (recentFilled == 0) null else (0 until recentFilled).maxOf { recent[it] }

        fun markBegin() {
            beginCount++
            if (began) return // a second BEGIN on the same session: counted, not re-based
            began = true
            beginBefore = last
            beginRecentMax = recentMax()
            awaitingFirstAfterBegin = true
        }

        fun markEnd() {
            endCount++
            if (ended) return
            ended = true
            endBefore = last
            endRecentMax = recentMax()
            awaitingFirstAfterBegin = false
        }

        /**
         * The single diagnostic number: how far the speech level rose above the
         * pre-speech floor. Null when either side has no samples — which is itself
         * meaningful, because it means one of the two phases produced no values.
         */
        fun levelRise(): Float? {
            if (preCount == 0 || duringCount == 0) return null
            return (duringSum / duringCount).toFloat() - (preSum / preCount).toFloat()
        }

        private fun mean(sum: Double, n: Int): Float? = if (n == 0) null else (sum / n).toFloat()

        fun format(): String {
            fun f(v: Float?): String = if (v == null) "n/a" else String.format(Locale.US, "%.1f", v)
            val s = StringBuilder()
            s.append("n=").append(count)
            s.append(" | min=").append(f(if (count == 0) null else minD))
            s.append(" | max=").append(f(if (count == 0) null else maxD))
            s.append(" | mean=").append(f(mean(sum, count)))
            s.append(" | begin#=").append(beginCount).append(" end#=").append(endCount)
            s.append(" | pre(n=").append(preCount)
            s.append(" mean=").append(f(mean(preSum, preCount)))
            s.append(" max=").append(f(if (preCount == 0) null else preMax)).append(")")
            s.append(" | atBegin(before=").append(f(beginBefore))
            s.append(" recentMax=").append(f(beginRecentMax))
            s.append(" after=").append(f(beginAfter)).append(")")
            s.append(" | during(n=").append(duringCount)
            s.append(" mean=").append(f(mean(duringSum, duringCount)))
            s.append(" max=").append(f(if (duringCount == 0) null else duringMax)).append(")")
            s.append(" | RISE=").append(f(levelRise()))
            s.append(" | atEnd(before=").append(f(endBefore))
            s.append(" recentMax=").append(f(endRecentMax)).append(")")
            s.append(" | afterEnd(n=").append(afterEndCount)
            s.append(" mean=").append(f(mean(afterEndSum, afterEndCount)))
            s.append(" max=").append(f(if (afterEndCount == 0) null else afterEndMax)).append(")")
            return s.toString()
        }

        private companion object {
            /** ~200-400 ms of callbacks at the service's usual reporting rate. */
            const val RECENT_WINDOW = 8
        }
    }

    /** Reused across sessions: one instance, reset at each START_LISTENING. */
    private val rmsTrace = RmsTrace()

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

            // PHASE 1D: remember whether this session reused the existing instance.
            // Purely for the diagnostic line below - the recogniser is obtained
            // exactly as before, and nothing about reuse is changed.
            val reused = speechRecognizer != null
            val recognizer = speechRecognizer ?: createRecognizer().also {
                speechRecognizer = it
            }

            isListening = true
            isCancelling = false

            // PHASE 1A: a real session begins here, so it gets a new id and a new
            // monotonic start point. Both are captured BEFORE startListening() is
            // called, so every callback's elapsed time is measured from the
            // actual call rather than from some later moment inside it.
            if (diagnosticsEnabled) {
                sessionCounter++
                currentSession = sessionCounter
                sessionStartElapsed = SystemClock.elapsedRealtime()
                // PHASE 1C: a new session gets a clean level accumulator. Done here,
                // before startListening(), so no value from a previous session can
                // bleed into this one's statistics.
                rmsTrace.reset()
                // PHASE 1D: the audio state IMMEDIATELY BEFORE startListening(), on
                // every recogniser creation and every reuse, so the two Bluetooth
                // conditions can be compared at the same moment in the lifecycle.
                // PHASE 1D: reused or freshly created? A recogniser that is created
                // once and reused binds to the audio state at its creation time,
                // which is a different experiment from recreating it per session.
                logLifecycle(
                    "START_LISTENING",
                    detail = "recognizer=" + (if (reused) "REUSED" else "CREATED")
                )
                logInputRoute("PRE_START")
            }

            try {
                recognizer.startListening(buildRecognizerIntent())
            } catch (illegalState: IllegalStateException) {
                // Most often "recognition is busy": a previous session was not
                // closed properly. Report it instead of crashing the app.
                isListening = false
                logLifecycle("START_FAILED", detail = "IllegalStateException (recognition busy)")
                mutableEvents.tryEmit(SpeechEvent.Failure(SpeechError.RECOGNIZER_BUSY))
            }
        }
    }

    override fun stopListening() {
        runOnMain {
            if (isReleased || !isListening) return@runOnMain
            isListening = false
            // PHASE 1A: logged because a stop explains a following
            // END_OF_SPEECH/FINAL pair with no PARTIALs, which would otherwise
            // look like the microphone never opened.
            logLifecycle("STOP_REQUESTED")
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
            // PHASE 1A: every cancel() is followed by an ERROR_CLIENT from the
            // platform. Logging the intent here stops that error looking like a
            // real failure when the trace is read afterwards.
            logLifecycle("CANCEL_REQUESTED")
            runCatching { speechRecognizer?.cancel() }
        }
    }

    override fun release() {
        runOnMain {
            if (isReleased) return@runOnMain
            isReleased = true
            isListening = false
            isCancelling = true
            logLifecycle("RELEASED")
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
            // PHASE 1A: the gap between START_LISTENING and this line is the
            // prime suspect. If the user starts talking during that gap, the
            // opening word is not in the audio the service ever receives.
            logLifecycle("READY_FOR_SPEECH")
            // PHASE 1D: read again here, because a capture may not be registered at
            // the instant startListening() returns. This line is the one that should
            // name the capture device actually in use.
            logInputRoute("READY_FOR_SPEECH")
            mutableEvents.tryEmit(SpeechEvent.ReadyForSpeech)
        }

        override fun onBeginningOfSpeech() {
            // Nothing to report: [SpeechEvent.ReadyForSpeech] already told the
            // UI that the microphone is open.
            //
            // PHASE 1A: logged ONLY, deliberately not turned into a SpeechEvent.
            // It is diagnostic information, not a state the UI has any use for,
            // and adding it to the public vocabulary would be a contract change
            // for a logging phase.
            logLifecycle("BEGINNING_OF_SPEECH")
            if (diagnosticsEnabled) rmsTrace.markBegin()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // VERSION 1 BEHAVIOUR IS UNCHANGED: no audio level meter, nothing shown
            // to the user, nothing emitted as a SpeechEvent. Adding a meter is a UI
            // decision for Phase 15, not a speech concern.
            //
            // PHASE 1C: the single scalar is folded into the session accumulator.
            // It is NOT logged here — this callback fires many times a second and a
            // line per call would flood Logcat and alter the timing of the very
            // session being measured. One summary line is emitted at session end.
            if (diagnosticsEnabled) rmsTrace.onValue(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio is never retained or transmitted by ARIA.
        }

        override fun onEndOfSpeech() {
            logLifecycle("END_OF_SPEECH")
            if (diagnosticsEnabled) rmsTrace.markEnd()
            // PHASE 1B — EXPERIMENT (b): ask the service what its endpointing was.
            // The extras are documented as being returned in the results bundle,
            // but services vary; reading them here as well costs one line and
            // catches the ones that only attach them at end-of-speech.
            logEndpointConfig("END_OF_SPEECH", null)
            mutableEvents.tryEmit(SpeechEvent.EndOfSpeech)
        }

        override fun onError(error: Int) {
            isListening = false
            // Mapped exactly once, by the SAME function the pipeline uses, so the
            // log can never disagree with what the user is told.
            val mapped = error.toSpeechError()
            if (isCancelling) {
                // Expected: the session was cancelled by ARIA itself.
                logLifecycle(
                    "ERROR",
                    detail = "code=$error | mapped=$mapped | swallowed (ARIA cancelled this session)"
                )
                isCancelling = false
                return
            }
            logLifecycle("ERROR", detail = "code=$error | mapped=$mapped")
            logRms("ERROR")
            mutableEvents.tryEmit(SpeechEvent.Failure(mapped))
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            // firstTranscript() is called ONCE and its result reused, so the log
            // shows literally the string that is about to be emitted. A blank
            // result is logged explicitly rather than passed over in silence:
            // "the service returned nothing" is a finding, not a non-event.
            val text = results.firstTranscript().orEmpty()

            // PHASE 1B — EXPERIMENT (a): "did the service hear the whole phrase
            // and we only kept the top hypothesis?"
            //
            // ARIA asks for EXTRA_MAX_RESULTS = 1 and reads index 0 only. If the
            // service put the full sentence in hypotheses 2..N, the loss is in
            // SCORING, and that would be an ARIA-side lever. If every hypothesis
            // is equally truncated, the words were never decoded at all and the
            // lever is acoustic/endpointing instead. This line is the whole
            // experiment — it observes, it does not change what is emitted.
            logHypotheses(results)

            // PHASE 1B — EXPERIMENT (b): the endpointing configuration the service
            // actually used for this session, as opposed to the one it defaults to.
            logEndpointConfig("RESULTS", results)

            logLifecycle(
                "FINAL",
                transcript = text,
                detail = if (text.isBlank()) "BLANK_RESULT" else null
            )
            logRms("FINAL")
            mutableEvents.tryEmit(SpeechEvent.FinalResult(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // PHASE 1A: partialResults.firstTranscript() — first (best) only, as
            // before. The log is per-callback: no merging, no longest-wins, and
            // the final result is never substituted with a partial. The sequence
            // of PARTIAL lines is the evidence for whether the opening word ever
            // entered the audio.
            val text = partialResults.firstTranscript()
            logLifecycle("PARTIAL", transcript = text ?: "")
            if (!text.isNullOrBlank()) {
                mutableEvents.tryEmit(SpeechEvent.PartialResult(text))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Reserved by the platform; ARIA does not use it.
        }
    }

    /**
     * PHASE 1A — one line per lifecycle event, in a fixed, greppable shape:
     *
     * ```
     * ARIA_STT | session=12 | +840ms | PARTIAL | "play"
     * ```
     *
     * @param event one of START_LISTENING, READY_FOR_SPEECH, BEGINNING_OF_SPEECH,
     *   PARTIAL, END_OF_SPEECH, FINAL, ERROR, STOP_REQUESTED, CANCEL_REQUESTED,
     *   RELEASED, START_FAILED.
     * @param transcript the transcript EXACTLY as the platform delivered it. Never
     *   rewritten, trimmed for display, merged or reordered — the whole point is to
     *   see what actually arrived.
     */
    private fun logLifecycle(event: String, transcript: String? = null, detail: String? = null) {
        if (!diagnosticsEnabled) return
        // Elapsed from the session's startListening() call. Guarded because a
        // callback can arrive before any session began (a stale one from a
        // session that started in a previous manager instance), which would
        // otherwise print a meaningless huge number.
        val elapsed = if (currentSession == 0) {
            "?"
        } else {
            val delta = SystemClock.elapsedRealtime() - sessionStartElapsed
            "+${delta}ms"
        }
        val line = buildString {
            append("session=").append(currentSession)
            append(" | ").append(elapsed.padEnd(8))
            append("| ").append(event)
            if (transcript != null) {
                // Quoted so an empty final ("") is visibly empty rather than
                // invisible — a blank transcript and a missing one are different
                // findings.
                append(" | \"").append(transcript).append("\"")
                if (transcript.isBlank()) append(" (blank)")
            }
            if (detail != null) append(" | ").append(detail)
        }
        Log.d(TAG_STT, line)
    }

    /**
     * PHASE 1C — one line per session, the scalar level statistics.
     *
     * Scoped to the session by design: every phase (before speech, during speech,
     * after end-of-speech) is measured separately, because an average over the
     * whole session cannot distinguish "quiet throughout" from "loud, then cut".
     *
     * Only ranges and means are printed — no sample, no audio, nothing that could
     * reconstruct what was said. A level in dB cannot be turned back into speech.
     */
    private fun logRms(stage: String) {
        if (!diagnosticsEnabled) return
        Log.d(TAG_STT, "session=$currentSession | RMS | at=$stage | " + rmsTrace.format())
    }

    /**
     * PHASE 1D — what is ACTUALLY capturing, and what audio state is in effect.
     *
     * WHY THIS REPLACES THE PHASE 1C VERSION: that version reported
     * `getCommunicationDevice()` as "the closest observable answer to which
     * microphone is being used". That was WRONG. `getCommunicationDevice()` returns
     * the device selected for communication on the OUTPUT path — its own javadoc
     * says it replaces `isBluetoothScoOn()`/`isSpeakerphoneOn()` — so it can never
     * name an input microphone. In the 1C trace it read TYPE_1
     * (TYPE_BUILTIN_EARPIECE, an output type) in all 15 sessions, including the
     * Bluetooth ones. It was measuring the wrong side of the audio path.
     *
     * THE CORRECT SOURCE: `getActiveRecordingConfigurations()` lists every active
     * capture in the system. Each entry carries `getAudioDevice()`, which IS the
     * input device that recording is actually using, plus `isClientSilenced()`,
     * which reports whether the system is feeding that client silence. Read at
     * START_LISTENING and again at the first lifecycle callback, because the
     * capture may not be registered yet at the instant startListening() is called.
     *
     * Still strictly read-only: nothing here selects, changes or requests a route.
     */
    private fun logInputRoute(stage: String) {
        if (!diagnosticsEnabled) return
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            Log.d(TAG_STT, "session=$currentSession | INPUT_ROUTE | at=$stage | audio_service=unavailable")
            return
        }

        // THE ANSWER: which input device each active capture is really using.
        val recordings = runCatching {
            audio.activeRecordingConfigurations.joinToString(" ; ") { config ->
                val device = config.audioDevice
                val name = device?.run { "${typeName()}/" + (productName ?: "?") } ?: "unknown"
                "src=${config.clientAudioSessionId} dev=$name silenced=${config.isClientSilenced}"
            }
        }.getOrElse { "error" }

        // Context: what the rest of the audio state looks like. mode=0 (NORMAL) is
        // the state in which Android is most likely to prefer the built-in mic.
        val mode = runCatching { audio.mode }.getOrElse { -1 }
        val sco = runCatching { audio.isBluetoothScoOn }.getOrElse { false }
        val speaker = runCatching { audio.isSpeakerphoneOn }.getOrElse { false }
        val musicActive = runCatching { audio.isMusicActive }.getOrElse { false }
        val commDev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio.communicationDevice?.typeName() ?: "null" }.getOrElse { "error" }
        } else {
            "unsupported"
        }

        // Which input devices exist right now. Kept for comparison only - a paired
        // headset appears here even when it is not capturing, so this is never proof.
        val available = runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString(",") { it.typeName() }
        }.getOrElse { "error" }

        Log.d(
            TAG_STT,
            "session=$currentSession | INPUT_ROUTE | at=$stage | " +
                "capturing=[$recordings] | mode=$mode | sco_on=$sco | " +
                "speakerphone_on=$speaker | music_active=$musicActive | " +
                "comm_device=$commDev | available=[$available]"
        )
    }

    /** Maps an input device type to a stable, greppable name. */
    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM_TUNER"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_ECHO_REFERENCE -> "ECHO_REFERENCE"
        else -> "TYPE_$type"
    }

    /**
     * PHASE 1B — EXPERIMENT (a): every hypothesis the service returned, plus its
     * confidence when the service supplies one.
     *
     * WHY THIS IS LOGGING AND NOT A FIX: ARIA requests `EXTRA_MAX_RESULTS = 1`, so
     * the platform may legitimately return a single candidate. This line answers a
     * question ARIA cannot answer by reasoning — *was the full phrase in the
     * bundle and only de-ranked, or was it never decoded?* Nothing here changes
     * which hypothesis is used; index 0 remains the only one ever emitted.
     *
     * "no score" is distinct from a LOW score, so it is printed as `conf=n/a`
     * rather than hidden behind a default of 0.0 — the two lead to different
     * conclusions about how much the ranking can be trusted.
     */
    private fun logHypotheses(results: Bundle?) {
        if (!diagnosticsEnabled) return
        val alternatives = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.map { it.trim() }
            ?: emptyList()
        // THE KEY IS SpeechRecognizer.CONFIDENCE_SCORES ("confidence_scores") AND NOT
        // RecognizerIntent.EXTRA_CONFIDENCE_SCORES ("android.speech.extra.
        // CONFIDENCE_SCORES"). They are two different strings: the EXTRA_ variant is
        // what a recogniser reads on the way IN, so looking it up in the RESULTS
        // bundle would return null every time and "conf=n/a" would print for every
        // session regardless of whether scores existed — a silent false negative.
        // This exact mistake is what broke the first build of this experiment.
        //
        // The value is documented as a float[], but Bundle.get() returns the raw
        // object and a third-party RecognitionService may put a List<Float> there.
        // One read plus a type check covers both; anything else means "no scores".
        val rawScores = results?.get(SpeechRecognizer.CONFIDENCE_SCORES)
        val scores: List<Float> = when (rawScores) {
            is FloatArray -> rawScores.toList()
            is List<*> -> rawScores.mapNotNull { (it as? Number)?.toFloat() }
            else -> emptyList()
        }

        val rendered = if (alternatives.isEmpty()) {
            "none"
        } else {
            alternatives.withIndex().joinToString(" ; ") { (index, text) ->
                // -1 (or anything negative) is the platform sentinel for
                // "no score given", not a confidence of minus one.
                val raw = scores.getOrNull(index)
                val score = if (raw == null || raw < 0f) {
                    " conf=n/a"
                } else {
                    // Locale.US on purpose: a device locale that renders
                    // digits differently must not produce a log line that
                    // cannot be parsed back. Diagnostic, not user-facing text.
                    " conf=" + String.format(Locale.US, "%.2f", raw)
                }
                "#$index \"$text\"$score"
            }
        }
        Log.d(TAG_STT, "session=$currentSession | ALTERNATIVES | n=${alternatives.size} | $rendered")
    }

    /**
     * PHASE 1B — EXPERIMENT (b): reports the endpointing values carried by the
     * service, if it carries any.
     *
     * WHY IT MATTERS: in the Phase 1A trace two sessions reported only 170 ms and
     * 245 ms between BEGINNING_OF_SPEECH and END_OF_SPEECH — far shorter than the
     * utterance. If the service's *complete*-silence length or minimum-length
     * config is small, the endpointer is closing the utterance around a fragment,
     * and the remedy is a configuration value. If those extras are absent (or
     * report normal values), endpointing is not the cause and we look at acoustics.
     *
     * "n/a" is printed per key on purpose. A missing extra and an extra that says
     * 800 are opposite findings, and a silently-skipped key would hide that.
     */
    private fun logEndpointConfig(stage: String, results: Bundle?) {
        if (!diagnosticsEnabled) return
        fun value(key: String): String {
            val raw = results?.get(key) ?: return "n/a"
            return when (raw) {
                is Long -> "${raw}ms"
                is Int -> "${raw}ms"
                is Boolean -> raw.toString()
                else -> raw.toString()
            }
        }
        val parts = listOf(
            "complete_silence=${value(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS)}",
            "possibly_complete_silence=${value(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS)}",
            "minimum_length=${value(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)}",
            "segmented_session=${value(RecognizerIntent.EXTRA_SEGMENTED_SESSION)}",
            "prefer_offline=${value(RecognizerIntent.EXTRA_PREFER_OFFLINE)}"
        )
        Log.d(TAG_STT, "session=$currentSession | ENDPOINT_CONFIG | at=$stage | " + parts.joinToString(" | "))
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
