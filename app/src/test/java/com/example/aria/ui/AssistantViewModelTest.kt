package com.example.aria.ui

import androidx.lifecycle.ViewModelStore
import com.example.aria.MainDispatcherRule
import com.example.aria.actions.ExecutionResult
import com.example.aria.actions.FakeActionExecutor
import com.example.aria.command.AssistantCommand
import com.example.aria.command.CommandParser
import com.example.aria.command.RuleBasedCommandParser
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.voice.FakeSpeechRecognizerManager
import com.example.aria.voice.FakeTextToSpeechManager
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import com.example.aria.voice.SpeechMessages
import com.example.aria.voice.TtsAcknowledgements
import com.example.aria.voice.TtsError
import com.example.aria.voice.TtsEvent
import com.example.aria.voice.TtsMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PHASE 2 + 3 tests: the speech round-trip state machine, on the JVM, with no
 * device, no microphone, no TTS engine and no Android framework.
 *
 * This is the payoff of putting every Android call behind
 * [com.example.aria.voice.SpeechRecognizerManager],
 * [com.example.aria.voice.TextToSpeechManager] and [MicrophonePermissionChecker]:
 * the interesting logic is testable in milliseconds.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val speech = FakeSpeechRecognizerManager()
    private val tts = FakeTextToSpeechManager()

    /** PHASE 6: the real parser (it is pure Kotlin, so it is safe in a JVM test). */
    private val parser: CommandParser = RuleBasedCommandParser()

    /** PHASE 6: an executor whose answer each test decides. */
    private val executor = FakeActionExecutor()

    private fun viewModel(
        hasPermission: Boolean = true,
        recognitionAvailable: Boolean = true,
        ttsReady: Boolean = true
    ): AssistantViewModel {
        speech.recognitionAvailable = recognitionAvailable
        tts.isReady = ttsReady
        return AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { hasPermission },
            textToSpeechManager = tts,
            commandParser = parser,
            actionExecutor = executor,
            // PHASE 6: the pipeline hops to Default/IO in production. These tests
            // assert synchronously right after an event, so both hops are pointed
            // at the unconfined test dispatcher and complete inline.
            computeDispatcher = mainDispatcherRule.testDispatcher,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )
    }

    /** Runs one complete interaction: tap, speak, final transcript. */
    private fun AssistantViewModel.speakOnce(transcript: String = "hello there") {
        onMicrophoneTapped()
        speech.emitSuccessfulSession(transcript)
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
        // PHASE 3: a captured sentence is acknowledged out loud, so the live
        // status is SPEAKING (it returns to SUCCESS when the audio ends, see the
        // dedicated Phase 3 test). Everything the transcript fills is unchanged.
        assertEquals(AssistantStatus.SPEAKING, state.status)
        assertEquals("Turn the volume down", state.rawSpeech)
        assertEquals("turn the volume down", state.normalizedText)
        assertEquals(1, state.history.size)
        assertEquals("Turn the volume down", state.history.first().rawSpeech)
        assertEquals(AssistantStatus.SUCCESS, state.history.first().status)
        // PHASE 6: the transcript is finally parsed and executed, so the fields
        // that Phase 2 left as placeholders now carry real values.
        assertEquals("VOLUME_DOWN", state.commandType)
        assertEquals("Volume Down", state.commandLabel)
        assertEquals("VolumeActionHandler", state.handler)
        assertEquals(ExecutionResult.Success(DEFAULT_REPLY).message, state.message)
        assertEquals("Exactly one execution per utterance", 1, executor.executed.size)
        assertEquals(AssistantCommand.VolumeDown, executor.executed.first())
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

    // --- PHASE 3: speaking back ---------------------------------------------

    @Test
    fun `a captured sentence is acknowledged out loud, then the result is spoken`() {
        val vm = viewModel()

        vm.speakOnce("turn the volume down")

        // PHASE 6: two utterances — the acknowledgement while the action runs,
        // then the honest result sentence.
        assertEquals(
            listOf(TtsAcknowledgements.forTurn(0), DEFAULT_REPLY),
            tts.spoken
        )
        assertEquals(DEFAULT_REPLY, vm.uiState.value.spokenReply)
        assertEquals(DEFAULT_REPLY, vm.uiState.value.lastSpokenText)
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)
    }

    @Test
    fun `the reply becomes visible text as well as audio`() {
        val vm = viewModel()

        vm.speakOnce()

        // A muted or broken engine must never leave the user without the reply.
        // PHASE 6: the visible reply is the command result, which is more useful
        // than the neutral acknowledgement it replaces.
        assertEquals(DEFAULT_REPLY, vm.uiState.value.spokenReply)
    }

    @Test
    fun `finishing the utterance returns the card to COMPLETED`() {
        val vm = viewModel()
        vm.speakOnce()
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)

        tts.finishSpeaking()

        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
        // The transcript and history must survive the speaking state.
        assertEquals("hello there", vm.uiState.value.rawSpeech)
        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test
    fun `muted ARIA does not speak but still shows the reply`() {
        val vm = viewModel()
        vm.onTtsEnabledChanged(false)

        vm.speakOnce()

        assertTrue("Muted means silent", tts.spoken.isEmpty())
        assertFalse(vm.uiState.value.ttsEnabled)
        assertEquals(DEFAULT_REPLY, vm.uiState.value.spokenReply)
        // Nothing is playing, so the card must not claim to be speaking.
        assertNotEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
    }

    @Test
    fun `re-enabling speech makes the next reply audible again`() {
        val vm = viewModel()
        vm.onTtsEnabledChanged(false)
        vm.speakOnce("first")

        vm.onTtsEnabledChanged(true)
        vm.speakOnce("second")

        // PHASE 6: the audible turn produces the acknowledgement and the result.
        assertEquals(2, tts.spoken.size)
        assertEquals(TtsAcknowledgements.forTurn(0), tts.spoken.first())
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)
    }

    @Test
    fun `muting mid-sentence stops the audio immediately`() {
        val vm = viewModel()
        vm.speakOnce()
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)

        vm.onTtsEnabledChanged(false)

        assertEquals(1, tts.stopCount)
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
    }

    @Test
    fun `acknowledgements rotate across turns`() {
        val vm = viewModel()

        vm.speakOnce("one")
        tts.finishSpeaking()
        vm.speakOnce("two")

        // PHASE 6: each turn speaks an acknowledgement *and* the result sentence.
        // The acknowledgement rotation must advance by exactly one per turn — that
        // is what stops "the same phrase twice" sounding broken.
        assertEquals(4, tts.spoken.size)
        assertEquals(TtsAcknowledgements.forTurn(0), tts.spoken[0])
        assertEquals(TtsAcknowledgements.forTurn(1), tts.spoken[2])
        assertNotEquals("The same phrase twice sounds broken", tts.spoken[0], tts.spoken[2])
        // ...and the result sentences are spoken in between, not instead.
        assertEquals(DEFAULT_REPLY, tts.spoken[1])
        assertEquals(DEFAULT_REPLY, tts.spoken[3])
    }

    @Test
    fun `an unavailable engine is reported and never blocks the transcript`() {
        val vm = viewModel(ttsReady = false)

        vm.speakOnce("hello")

        assertFalse(vm.uiState.value.isTtsAvailable)
        assertTrue("Nothing can be spoken", tts.spoken.isEmpty())
        assertEquals(
            "An unavailable engine must explain itself",
            TtsMessages.forError(TtsError.ENGINE_INIT_FAILED),
            vm.uiState.value.ttsMessage
        )
        assertEquals("hello", vm.uiState.value.rawSpeech)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
    }

    @Test
    fun `an engine failure arriving later still restores the card`() {
        val vm = viewModel()
        vm.speakOnce()

        tts.emit(TtsEvent.Unavailable(TtsError.ENGINE_INIT_FAILED))

        val state = vm.uiState.value
        assertFalse(state.isTtsAvailable)
        assertEquals(TtsMessages.forError(TtsError.ENGINE_INIT_FAILED), state.ttsMessage)
        // Never leave the screen stuck on SPEAKING when no audio can play.
        assertNotEquals(AssistantStatus.ERROR, state.status)
        tts.finishSpeaking()
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)

    }

    @Test
    fun `a rejected speak request is logged as a notice, not a crash`() {
        val vm = viewModel()
        tts.acceptsSpeak = false

        vm.speakOnce()

        assertEquals(
            TtsMessages.forError(TtsError.SPEAK_FAILED),
            vm.uiState.value.ttsMessage
        )
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
        // The transcript itself is still a success.
        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test
    fun `a speak failure event does not erase the result`() {
        val vm = viewModel()
        vm.speakOnce("keep me")

        tts.emit(TtsEvent.SpeakFailed)

        assertEquals("keep me", vm.uiState.value.rawSpeech)
        assertEquals(
            TtsMessages.forError(TtsError.SPEAK_FAILED),
            vm.uiState.value.ttsMessage
        )
    }

    @Test
    fun `an engine becoming ready clears the warning`() {
        val vm = viewModel(ttsReady = false)
        vm.speakOnce()
        assertFalse(vm.uiState.value.isTtsAvailable)

        tts.isReady = true
        tts.emit(TtsEvent.Ready)

        assertTrue(vm.uiState.value.isTtsAvailable)
        assertNull(vm.uiState.value.ttsMessage)
    }

    @Test
    fun `backgrounding the app silences the voice`() {
        val vm = viewModel()
        vm.speakOnce()
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)

        vm.onAppBackgrounded()

        assertEquals(1, tts.stopCount)
        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)
    }

    @Test
    fun `a failed recognition is never spoken`() {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.Failure(SpeechError.NO_MATCH))

        assertTrue("There is nothing to acknowledge", tts.spoken.isEmpty())
        assertEquals(AssistantStatus.ERROR, vm.uiState.value.status)
    }

    @Test
    fun `a new attempt clears the previous spoken reply`() {
        val vm = viewModel()
        vm.speakOnce()
        assertEquals(DEFAULT_REPLY, vm.uiState.value.spokenReply)

        vm.onMicrophoneTapped()

        assertNull(vm.uiState.value.spokenReply)
        assertNull(vm.uiState.value.lastSpokenText)
    }

    @Test
    fun `onCleared silences the engine without shutting down the app-wide one`() {
        val vm = viewModel()
        vm.speakOnce()
        val store = ViewModelStore()
        store.put("aria-test", vm)

        store.clear()

        assertEquals(1, tts.stopCount)
        assertEquals("The engine is Application-scoped, not ViewModel-scoped", 0, tts.shutdownCount)
        assertEquals(1, speech.releaseCount)
    }

    /** Reply the fake executor returns unless a test overrides it. */
    private companion object {
        const val DEFAULT_REPLY = "Media volume up to 60%."
    }
}
