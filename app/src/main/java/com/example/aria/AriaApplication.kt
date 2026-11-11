package com.example.aria

import android.app.Application
import com.example.aria.actions.ActionExecutor
import com.example.aria.actions.createAndroidActionExecutor
import com.example.aria.command.CommandParser
import com.example.aria.command.RuleBasedCommandParser
import com.example.aria.voice.AndroidSpeechRecognizerManager
import com.example.aria.voice.AndroidTextToSpeechManager
import com.example.aria.voice.SpeechRecognizerManager
import com.example.aria.voice.TextToSpeechManager

/**
 * Process-scoped owner of the two audio engines.
 *
 * WHY THIS CLASS EXISTS (Phase 2, extended in Phase 3)
 * `SpeechRecognizer` and `TextToSpeech` both need a `Context` and both are
 * expensive to build. Creating either inside a Composable rebuilt the platform
 * object on recomposition; creating them in the ViewModel dragged Android classes
 * into logic that must stay unit-testable. The Application is the correct owner:
 * one of each per process, no Activity leak, and the ViewModel factory can hand
 * them over.
 *
 * It is also the single seam where a fake can be injected in a future test.
 */
class AriaApplication : Application() {

    // Held as explicit Lazy<T> delegates (not `by lazy` directly) so onTerminate
    // can ask isInitialized() — that API does not exist for plain delegated vals.
    private val speechRecognizerLazy = lazy { AndroidSpeechRecognizerManager(applicationContext) }

    /** Lazily created so a cold start that never opens the mic pays nothing. */
    val speechRecognizerManager: SpeechRecognizerManager by speechRecognizerLazy

    /**
     * PHASE 3 — lazily created for the same reason as the recogniser: binding to
     * a system TTS service is not free, and a user who never speaks should not
     * pay for it.
     */
    private val textToSpeechLazy = lazy { AndroidTextToSpeechManager(applicationContext) }

    /** The app-wide speech engine. One per process. */
    val textToSpeechManager: TextToSpeechManager by textToSpeechLazy

    /**
     * PHASE 6 — the parser is stateless and pure, so one instance per process is
     * plenty; a stale instance cannot exist.
     */
    val commandParser: CommandParser by lazy { RuleBasedCommandParser() }

    /**
     * PHASE 6 — the action executor and its handlers.
     *
     * WHY OWNED HERE: handlers read system services (`AudioManager`,
     * `PackageManager`, `NotificationManager`) through thin adapters that hold the
     * *application* context, so a single process-wide instance is correct and
     * cannot leak an Activity. Building it lazily means a cold start that never
     * speaks never touches `PackageManager`.
     *
     * Exposed as the [ActionExecutor] interface so the ViewModel depends on the
     * contract, not on the Android implementation.
     */
    private val actionExecutorLazy = lazy { createAndroidActionExecutor(applicationContext) }

    val actionExecutor: ActionExecutor by actionExecutorLazy

    /**
     * Releases both engines for a clean shutdown.
     *
     * HONEST LIMITATION: Android documents `onTerminate` as emulator-only — it is
     * *not* called on a real phone, because the system normally just kills the
     * process. It is kept because it is the only ordered teardown hook an
     * Application has, and it costs nothing.
     *
     * WHY THAT IS STILL NOT A LEAK: both engines hold the **application** context
     * only, are single instances per process, and are backed by system services
     * the OS reclaims with the process. Nothing here can keep a destroyed
     * Activity alive. The parts that must stop *promptly* — the microphone and
     * any speech output — are stopped in `AssistantViewModel.onAppBackgrounded()`
     * and `onCleared()` instead.
     */
    override fun onTerminate() {
        if (textToSpeechLazy.isInitialized()) textToSpeechLazy.value.shutdown()
        if (speechRecognizerLazy.isInitialized()) speechRecognizerLazy.value.release()
        super.onTerminate()
    }
}
