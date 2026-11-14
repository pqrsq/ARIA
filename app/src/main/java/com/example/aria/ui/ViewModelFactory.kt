package com.example.aria.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.example.aria.AriaApplication
import com.example.aria.actions.ActionExecutor
import com.example.aria.command.CommandParser
import com.example.aria.permissions.AndroidPermissionChecker
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.permissions.PolicyAccessChecker
import com.example.aria.voice.SpeechRecognizerManager
import com.example.aria.voice.TextToSpeechManager

/**
 * Dependency wiring for the assistant screen — still no DI framework.
 *
 * WHY A FACTORY (and not Hilt/Koin): the graph is five collaborators (speech
 * manager, microphone checker, speech engine, command parser, action executor).
 * Still small enough that a factory keeps the build fast and the wiring visible;
 * if it grows further, swapping in a container is a local change because screens
 * only ever ask for a ViewModel.
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
            textToSpeechManager = application.textToSpeechManager,
            commandParser = application.commandParser,
            actionExecutor = application.actionExecutor,
            policyAccessChecker = application.policyAccessChecker
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
    private val textToSpeechManager: TextToSpeechManager,
    private val commandParser: CommandParser,
    private val actionExecutor: ActionExecutor,
    private val policyAccessChecker: PolicyAccessChecker = PolicyAccessChecker { false }
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AssistantViewModel(
            speechRecognizerManager = speechRecognizerManager,
            permissionChecker = permissionChecker,
            textToSpeechManager = textToSpeechManager,
            commandParser = commandParser,
            actionExecutor = actionExecutor,
            policyAccessChecker = policyAccessChecker
        ) as T
    }
}
