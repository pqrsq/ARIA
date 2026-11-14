package com.example.aria.ui

import com.example.aria.MainDispatcherRule
import com.example.aria.actions.ActionMessages
import com.example.aria.actions.ExecutionResult
import com.example.aria.actions.FakeActionExecutor
import com.example.aria.command.AssistantCommand
import com.example.aria.command.CommandParser
import com.example.aria.command.RejectionReason
import com.example.aria.command.RuleBasedCommandParser
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.permissions.PolicyAccessChecker
import com.example.aria.voice.FakeSpeechRecognizerManager
import com.example.aria.voice.FakeTextToSpeechManager
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import com.example.aria.voice.TtsAcknowledgements
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PHASE P1.6 — the debug text input, from the ViewModel's side.
 *
 * WHAT THESE TESTS ARE ACTUALLY FOR: not "does a text field work", but "can typed
 * input prove anything about the voice path". The value of the affordance rests
 * entirely on [AssistantViewModel.onSubmitTextInput] being the same pipeline as
 * `SpeechEvent.FinalResult`; if it were a parallel path, a green typed test would
 * say nothing about speech. So the central test here runs the SAME string both
 * ways and demands the SAME command, result, status and history row.
 *
 * The real [RuleBasedCommandParser] runs (it is pure Kotlin); only the
 * Android-facing executor is faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTextInputTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val speech = FakeSpeechRecognizerManager()
    private val tts = FakeTextToSpeechManager()
    private val executor = FakeActionExecutor()
    private val parser: CommandParser = RuleBasedCommandParser()

    /** Controllable wall clock for the one-second duplicate window. */
    private var nowMillis: Long = 1_000L

    /**
     * @param micGranted false simulates a device where RECORD_AUDIO is denied —
     *   typed input must still work there.
     */
    private fun viewModel(micGranted: Boolean = true): AssistantViewModel {
        speech.recognitionAvailable = true
        tts.isReady = true
        return AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { micGranted },
            textToSpeechManager = tts,
            commandParser = parser,
            actionExecutor = executor,
            computeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis },
            policyAccessChecker = PolicyAccessChecker { false }
        )
    }

    /** One complete voice interaction: tap, speak, final transcript. */
    private fun AssistantViewModel.say(transcript: String) {
        onMicrophoneTapped()
        speech.emitSuccessfulSession(transcript)
    }

    // ── Same pipeline, same outcome ──────────────────────────────────────────

    @Test
    fun `a typed command produces the same command, result and status as speech`() = runTest(dispatcher) {
        val transcript = "turn the volume down"

        // Run 1: by voice.
        val byVoice = viewModel()
        byVoice.say(transcript)
        val voiceState = byVoice.uiState.value
        val voiceExecuted = executor.executed.toList()
        assertEquals(listOf<AssistantCommand>(AssistantCommand.VolumeDown), voiceExecuted)

        // Run 2: the same words, typed, on a FRESH ViewModel (its own duplicate
        // memory) and after the window has passed.
        nowMillis += 5_000L
        val byText = viewModel()
        byText.onSubmitTextInput(transcript)
        val textState = byText.uiState.value

        // The command handed to the executor is identical…
        //
        // NOT `executor.executed`: the fake is a shared field and accumulates
        // across both runs, so comparing it to the run-1 snapshot would compare
        // [VolumeDown] with [VolumeDown, VolumeDown] — a list-length mismatch, not
        // a behaviour difference. The second run's slice is what is comparable.
        assertEquals(voiceExecuted, executor.executed.drop(voiceExecuted.size))

        // …and so is everything the user sees.
        //
        // SETTLE FIRST: a successful result is SPEAKING while its sentence is read
        // aloud, and only becomes SUCCESS when the engine reports the utterance
        // finished. Comparing the raw statuses would compare "SPEAKING" with
        // "SPEAKING" — true by accident, and silent about the real outcome.
        tts.finishSpeaking()
        val voiceSettled = byVoice.uiState.value.status
        val textSettled = byText.uiState.value.status
        assertEquals(voiceSettled, textSettled)
        assertEquals(AssistantStatus.SUCCESS, textSettled)
        assertEquals(voiceState.commandType, textState.commandType)
        assertEquals(voiceState.commandLabel, textState.commandLabel)
        assertEquals(voiceState.handler, textState.handler)
        assertEquals(voiceState.message, textState.message)
        assertEquals(voiceState.normalizedText, textState.normalizedText)
        assertEquals(voiceState.history.size, textState.history.size)
        assertEquals(voiceState.history.first().commandType, textState.history.first().commandType)
        assertEquals(voiceState.history.first().status, textState.history.first().status)

        // Only the attribution differs — that is the point of the marker.
        assertEquals(InputSource.VOICE, voiceState.history.first().source)
        assertEquals(InputSource.TEXT, textState.history.first().source)
    }

    @Test
    fun `the spoken acknowledgement and the spoken reply still happen for typed input`() = runTest(dispatcher) {
        executor.result = ExecutionResult.Success("Media volume down to 47%.")
        val vm = viewModel()

        vm.onSubmitTextInput("turn the volume down")

        // Two utterances, exactly as on the voice path: the rotating
        // acknowledgement before execution, then the real result. Both go through
        // the same speak() calls, which is what proves this is not a shortcut
        // path that skips the audio side effects.
        assertEquals(2, tts.spoken.size)
        assertEquals(TtsAcknowledgements.forTurn(0), tts.spoken.first())
        assertEquals("Media volume down to 47%.", tts.spoken.last())
    }

    // ── D1: duplicate suppression ────────────────────────────────────────────

    @Test
    fun `typed input is duplicate-suppressed inside the window and never left PROCESSING`() = runTest(dispatcher) {
        val vm = viewModel()
        nowMillis = 2_000L
        vm.onSubmitTextInput("turn the volume up")
        assertEquals(1, executor.executed.size)

        // Settle the spoken reply before snapshotting. Without this the snapshot
        // is SPEAKING, and the suppressed duplicate would "match" it by being
        // SPEAKING for its own (never executed) reason — a comparison that cannot
        // fail tells you nothing.
        tts.finishSpeaking()
        val settled = vm.uiState.value
        assertEquals(AssistantStatus.SUCCESS, settled.status)

        // The same command again, inside one second.
        nowMillis += 300L
        vm.onSubmitTextInput("turn the volume up")

        // D1: executed once, and the card still describes the FIRST attempt.
        assertEquals(1, executor.executed.size)
        assertEquals(1, vm.uiState.value.history.size)
        assertEquals(settled.status, vm.uiState.value.status)
        assertEquals(settled.message, vm.uiState.value.message)
        // …and never stranded on PROCESSING, which was the whole defect.
        assertTrue(
            "a suppressed duplicate must not be left processing: ${vm.uiState.value.status}",
            vm.uiState.value.status != AssistantStatus.PROCESSING
        )
    }

    @Test
    fun `two different typed commands inside the window both run`() = runTest(dispatcher) {
        // Guards against over-suppression: the window is on the COMMAND, not on
        // "anything typed recently".
        val vm = viewModel()
        vm.onSubmitTextInput("turn the volume up")
        nowMillis += 200L
        vm.onSubmitTextInput("turn the volume down")

        assertEquals(
            listOf<AssistantCommand>(AssistantCommand.VolumeUp, AssistantCommand.VolumeDown),
            executor.executed
        )
        assertEquals(2, vm.uiState.value.history.size)
    }

    @Test
    fun `a typed command after a voice command is not suppressed`() = runTest(dispatcher) {
        // The duplicate memory is shared across sources on purpose: it protects
        // the user from a double execution, and it does not care where the words
        // came from. Different commands must still both run.
        val vm = viewModel()
        vm.say("pause the music")
        nowMillis += 100L
        vm.onSubmitTextInput("turn the volume down")

        assertEquals(2, vm.uiState.value.history.size)
        assertEquals(1, vm.uiState.value.history.count { it.source == InputSource.TEXT })
        assertEquals(1, vm.uiState.value.history.count { it.source == InputSource.VOICE })
    }

    // ── D10 / D11 through the new input ──────────────────────────────────────

    @Test
    fun `typing an out-of-range volume is rejected and performs nothing`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onSubmitTextInput("set the volume to 120 percent")

        // The refusal reaches the executor (so the fallback handler can explain
        // it) but no volume command ever does.
        assertEquals(
            listOf<AssistantCommand>(
                AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE)
            ),
            executor.executed
        )
        assertEquals(AssistantStatus.REJECTED, vm.uiState.value.status)
        assertEquals(ActionMessages.VOLUME_OUT_OF_RANGE, vm.uiState.value.message)
        assertEquals(InputSource.TEXT, vm.uiState.value.history.first().source)
    }

    @Test
    fun `typing two commands in one sentence is rejected, not half-executed`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onSubmitTextInput("set the volume to 30 percent and turn off wifi")

        assertEquals(
            listOf<AssistantCommand>(
                AssistantCommand.Rejected(RejectionReason.COMPOUND_SENTENCE)
            ),
            executor.executed
        )
        assertEquals(AssistantStatus.REJECTED, vm.uiState.value.status)
        // A rejection with no transcript must still be VISIBLE (hasResult).
        assertTrue(vm.uiState.value.hasResult)
    }

    // ── No microphone, no recogniser ─────────────────────────────────────────

    @Test
    fun `typed input never starts the recogniser and never asks for the microphone`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onSubmitTextInput("turn the volume up")
        vm.onSubmitTextInput("pause the music")

        // The whole point: typing is not a disguised voice session.
        assertEquals(0, speech.startCount)
        assertEquals(0, speech.stopCount)
        assertFalse(vm.uiState.value.isAwaitingPermission)
    }

    @Test
    fun `typed input works when the microphone permission is denied`() = runTest(dispatcher) {
        // The debugging scenario this exists for: a device where voice cannot be
        // used at all must still be able to exercise the parser.
        val vm = viewModel(micGranted = false)

        vm.onSubmitTextInput("turn the volume down")

        assertEquals(listOf<AssistantCommand>(AssistantCommand.VolumeDown), executor.executed)
        assertEquals(InputSource.TEXT, vm.uiState.value.history.first().source)
        // The result is being read aloud, so the status is SPEAKING; what matters
        // here is that it SETTLES to SUCCESS without ever having been an ERROR.
        tts.finishSpeaking()
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
        assertFalse(vm.uiState.value.isAwaitingPermission)
        assertEquals(0, speech.startCount)
    }

    @Test
    fun `typed input works when no speech service is installed`() = runTest(dispatcher) {
        speech.recognitionAvailable = false
        val vm = viewModel()

        vm.onSubmitTextInput("pause the music")

        assertEquals(listOf<AssistantCommand>(AssistantCommand.PauseMusic), executor.executed)
    }

    // ── Blank input ──────────────────────────────────────────────────────────

    @Test
    fun `blank typed input does nothing at all`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.onSubmitTextInput("")
        vm.onSubmitTextInput("   ")
        vm.onSubmitTextInput("\n\t ")

        // No history row, no execution, no spoken acknowledgement: a mis-tapped
        // send button is not a failed command.
        assertTrue(executor.executed.isEmpty())
        assertTrue(vm.uiState.value.history.isEmpty())
        // No pipeline at all: nothing was said, so nothing was spoken either.
        assertTrue(tts.spoken.isEmpty())
        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)
        assertFalse(vm.uiState.value.hasResult)
    }

    // ── The source marker ────────────────────────────────────────────────────

    @Test
    fun `the source marker is TEXT for typed input and VOICE for speech`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("turn the volume up")
        assertEquals(InputSource.VOICE, vm.uiState.value.inputSource)
        assertEquals(InputSource.VOICE, vm.uiState.value.history.first().source)

        nowMillis += 5_000L
        vm.onSubmitTextInput("turn the volume down")
        assertEquals(InputSource.TEXT, vm.uiState.value.inputSource)
        assertEquals(InputSource.TEXT, vm.uiState.value.history.first().source)
        // The older row keeps its own attribution; appending must not rewrite it.
        assertEquals(InputSource.VOICE, vm.uiState.value.history.last().source)
    }

    @Test
    fun `a speech failure is attributed to voice even after a typed command`() = runTest(dispatcher) {
        // THE BUG THIS PINS: an earlier version read the CARD's source for a
        // failure row, so after a typed command the card still said TEXT and the
        // next spoken failure was filed as typed. Failure events can only come
        // from the recogniser, so the row is VOICE unconditionally.
        val vm = viewModel()
        vm.onSubmitTextInput("turn the volume up")
        assertEquals(InputSource.TEXT, vm.uiState.value.inputSource)

        // Now a voice attempt that produces no words at all.
        nowMillis += 5_000L
        vm.onMicrophoneTapped()
        speech.emit(SpeechEvent.Failure(SpeechError.NO_MATCH))

        assertEquals(2, vm.uiState.value.history.size)
        assertEquals(InputSource.VOICE, vm.uiState.value.history.first().source)
        assertEquals(AssistantStatus.ERROR, vm.uiState.value.history.first().status)
    }

    @Test
    fun `opening the microphone returns the card to the voice source`() = runTest(dispatcher) {
        // The marker must describe the CURRENT interaction: once the microphone
        // is tapped, the card may not keep claiming the previous typed command.
        val vm = viewModel()
        vm.onSubmitTextInput("turn the volume up")
        assertEquals(InputSource.TEXT, vm.uiState.value.inputSource)

        vm.onMicrophoneTapped()

        assertEquals(InputSource.VOICE, vm.uiState.value.inputSource)
    }

    @Test
    fun `the default source is voice so nothing else can be labelled text by accident`() = runTest(dispatcher) {
        // A state and a record built the old way (no source argument) must mean
        // "voice", because voice is the product and TEXT is the exception.
        assertEquals(InputSource.VOICE, AssistantUiState().inputSource)
        assertEquals(
            InputSource.VOICE,
            CommandRecord(id = 1L, timestampMillis = 0L, rawSpeech = "test").source
        )
        // And a fresh ViewModel has never seen text input.
        assertEquals(InputSource.VOICE, viewModel().uiState.value.inputSource)
    }
}
