package com.example.aria

import android.app.Application
import com.example.aria.voice.AndroidSpeechRecognizerManager
import com.example.aria.voice.SpeechRecognizerManager

/**
 * Why this class exists in Phase 2 (Phase 1 had none).
 *
 * `SpeechRecognizer.createSpeechRecognizer` needs a `Context`. Creating it inside
 * a Composable would rebuild the platform recogniser on every recomposition;
 * creating it in the ViewModel would drag an Android class into logic that must
 * stay unit-testable. The Application is the correct owner: one instance, no
 * Activity leak, and the ViewModel factory can hand it over.
 *
 * It is also the single seam where a test/fake implementation can be injected
 * later without touching the screen.
 */
class AriaApplication : Application() {

    /** Lazily created so a cold start that never opens the mic pays nothing. */
    val speechRecognizerManager: SpeechRecognizerManager by lazy {
        AndroidSpeechRecognizerManager(applicationContext)
    }
}
