package com.example.aria.ui

import androidx.lifecycle.ViewModelStore
import com.example.aria.MainDispatcherRule
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.voice.FakeSpeechRecognizerManager
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import com.example.aria.voice.SpeechMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PHASE 2 tests: the speech-to-text state machine, on the JVM, with no device,
 * no microphone and no Android framework.
 *
 * This is the payoff of putting every Android call behind
 * [com.example.aria.voice.SpeechRecognizerManager] and
 * [MicrophonePermissionChecker]: the interesting logic is testable in
 * milliseconds.
 */
class AssistantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val speech = FakeSpeechRecognizerManager()

    private fun viewModel(
        hasPermission: Boolean = true,
        recognitionAvailable: Boolean = true
    ): AssistantViewModel {
        speech.recognitionAvailable = recognitionAvailable
        return AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { hasPermission }
        )
    }

    // --- Permission gating ---------------------------------------------------

    @Test
    fun `tapping the mic without permission asks for it and does not listen`() {
        val vm = viewModel(hasPermission = false)

        vm.onMicrophoneTapped()

        assertTrue("UI must be told to show the system dialog", vm.uiState.value.isAwaitingPermission)
        assertEquals(0, speech.startCount)
        assertNull("Nothing to report before the dialog is answered", vm.uiState.value.message)
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `granting permission starts listening immediately`() {
        val vm = viewModel(hasPermission = false)
        vm.onMicrophoneTapped()

        vm.onMicrophonePermissionResult(granted = true, permanentlyDenied = false)

        assertEquals(1, speech.startCount)
        assertFalse(vm.uiState.value.isAwaitingPermission)
        assertEquals(AssistantStatus.LISTENING, vm.uiState.value.status)
    }

    @Test
    fun `refusing permission shows an error and never opens the mic`() {
        val vm = viewModel(hasPermission = false)
        vm.onMicrophoneTapped()

        vm.onMicrophonePermissionResult(granted = false, permanentlyDenied = false)

        assertEquals(0, speech.startCount)
        assertEquals(AssistantStatus.ERROR, vm.uiState.value.status)
        assertEquals(SpeechMessages.PERMISSION_DENIED, vm.uiState.value.message)
        assertFalse(vm.uiState.value.isPermissionBlocked)
    }

    @Test
    fun `a permanent refusal offers the settings route`() {
        val vm = viewModel(hasPermission = false)
        vm.onMicrophoneTapped()

        vm.onMicrophonePermissionResult(granted = false, permanentlyDenied = true)

        assertTrue(vm.uiState.value.isPermissionBlocked)
        assertEquals(SpeechMessages.PERMISSION_BLOCKED, vm.uiState.value.message)
    }

    @Test
    fun `tapping with permission granted starts listening straight away`() {
        val vm = viewModel(hasPermission = true)

        vm.onMicrophoneTapped()

        assertEquals(1, speech.startCount)
        assertFalse(vm.uiState.value.isAwaitingPermission)
        assertEquals(AssistantStatus.LISTENING, vm.uiState.value.status)
    }

    // --- Failure paths that must not crash or hang ----------------------------

    @Test
    fun `a device without a speech service reports an error and does not listen`() {
        val vm = viewModel(recognitionAvailable = false)

        vm.onMicrophoneTapped()

        assertEquals(0, speech.startCount)
        assertEquals(AssistantStatus.ERROR, vm.uiState.value.status)
        assertEquals(
            SpeechMessages.forError(SpeechError.SERVICE_UNAVAILABLE),
            vm.uiState.value.message
        )
        assertEquals(1, vm.uiState.value.history.size)
    }

    // --- The happy path ------------------------------------------------------

    @Test
    fun `partial results are shown live without creating history`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.ReadyForSpeech)
        speech.emit(SpeechEvent.PartialResult("turn the vol"))

        assertEquals("turn the vol", vm.uiState.value.rawSpeech)
        assertEquals(AssistantStatus.LISTENING, vm.uiState.value.status)
        assertTrue(vm.uiState.value.isLiveTranscript)
        assertTrue(vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `the final transcript fills the existing state fields and one history entry`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emitSuccessfulSession("Turn the volume down")

        val state = vm.uiState.value
        assertEquals(AssistantStatus.SUCCESS, state.status)
        assertEquals("Turn the volume down", state.rawSpeech)
        assertEquals("turn the volume down", state.normalizedText)
        assertEquals(AssistantViewModel.NO_COMMAND_YET, state.commandType)
        assertEquals(SpeechMessages.CAPTURED_NOT_EXECUTED, state.message)
        assertEquals(1, state.history.size)
        assertEquals("Turn the volume down", state.history.first().rawSpeech)
        assertEquals(AssistantStatus.SUCCESS, state.history.first().status)
        // Phase 2 must not invent a command it cannot parse yet.
        assertNull("No command label before Phase 5", state.commandLabel)
    }

    @Test
    fun `history keeps the newest transcript first`() {
        val vm = viewModel()

        vm.onMicrophoneTapped()
        speech.emitSuccessfulSession("first")
        vm.onMicrophoneTapped()
        speech.emitSuccessfulSession("second")

        assertEquals(listOf("second", "first"), vm.uiState.value.history.map { it.rawSpeech })
    }

    @Test
    fun `a second tap while listening stops the recogniser instead of restarting`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()
        assertEquals(1, speech.startCount)

        vm.onMicrophoneTapped()

        assertEquals(1, speech.startCount)
        assertEquals(1, speech.stopCount)
    }

    // --- Errors --------------------------------------------------------------

    @Test
    fun `no match produces a friendly error and a FAIL history row`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.Failure(SpeechError.NO_MATCH))

        val state = vm.uiState.value
        assertEquals(AssistantStatus.ERROR, state.status)
        assertEquals(SpeechMessages.forError(SpeechError.NO_MATCH), state.message)
        assertEquals(1, state.history.size)
        assertEquals(AssistantStatus.ERROR, state.history.first().status)
        assertNull("A failed attempt has no transcript to show", state.rawSpeech)
    }

    @Test
    fun `a blank final result is treated as no match rather than an empty success`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.FinalResult("   "))

        assertEquals(AssistantStatus.ERROR, vm.uiState.value.status)
        assertEquals(SpeechMessages.forError(SpeechError.NO_MATCH), vm.uiState.value.message)
    }

    @Test
    fun `every error code maps to a sentence so the user is never shown a blank card`() {
        SpeechError.entries.forEach { error ->
            assertTrue(
                "Missing message for $error",
                SpeechMessages.forError(error).isNotBlank()
            )
        }
    }

    // --- Lifecycle -----------------------------------------------------------

    @Test
    fun `backgrounding the app cancels the microphone and returns to idle`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        vm.onAppBackgrounded()

        assertEquals(1, speech.cancelCount)
        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)
    }

    @Test
    fun `the recogniser is released when the ViewModel is cleared`() {
        val vm = viewModel()
        // onCleared() is protected, so drive it through the public lifecycle API
        // the framework itself uses.
        val store = ViewModelStore()
        store.put("aria-test", vm)

        store.clear()

        assertEquals(1, speech.releaseCount)
    }

    @Test
    fun `clearing history keeps the last result visible but empty`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()
        speech.emitSuccessfulSession("hello")

        vm.clearHistory()

        assertTrue(vm.uiState.value.history.isEmpty())
        assertEquals("hello", vm.uiState.value.rawSpeech)
    }
}
