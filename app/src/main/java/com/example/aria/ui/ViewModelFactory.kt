package com.example.aria.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.example.aria.AriaApplication
import com.example.aria.permissions.AndroidPermissionChecker
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.voice.SpeechRecognizerManager
import com.example.aria.voice.TextToSpeechManager

/**
 * Dependency wiring for the assistant screen — still no DI framework.
 *
 * WHY A FACTORY (and not Hilt/Koin): the graph is three objects (speech manager,
 * permission checker, ViewModel). A factory keeps the build fast and the project
 * readable. When the graph grows in Phase 6+, swapping in a container is a local
 * change because screens only ever ask for a ViewModel.
 *
 * The application context arrives through `CreationExtras`, so nothing here can
 * hold an Activity.
 */
fun stateViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val application = this[APPLICATION_KEY] as AriaApplication
        AssistantViewModel(
            speechRecognizerManager = application.speechRecognizerManager,
            permissionChecker = AndroidPermissionChecker(application),
            textToSpeechManager = application.textToSpeechManager
        )
    }
}

/**
 * Explicit factory used by tests and by any code that needs to inject fakes for
 * [SpeechRecognizerManager] and [TextToSpeechManager].
 */
class AssistantViewModelFactory(
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val permissionChecker: MicrophonePermissionChecker,
    private val textToSpeechManager: TextToSpeechManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AssistantViewModel(
            speechRecognizerManager = speechRecognizerManager,
            permissionChecker = permissionChecker,
            textToSpeechManager = textToSpeechManager
        ) as T
    }
}
