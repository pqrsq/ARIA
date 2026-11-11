package com.example.aria.ui

import com.example.aria.MainDispatcherRule
import com.example.aria.actions.ActionMessages
import com.example.aria.actions.ExecutionResult
import com.example.aria.actions.FakeActionExecutor
import com.example.aria.actions.SettingsActions
import com.example.aria.command.AssistantCommand
import com.example.aria.command.CommandParser
import com.example.aria.command.RuleBasedCommandParser
import com.example.aria.permissions.MicrophonePermissionChecker
import com.example.aria.voice.FakeSpeechRecognizerManager
import com.example.aria.voice.FakeTextToSpeechManager
import com.example.aria.voice.SpeechError
import com.example.aria.voice.SpeechEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PHASE 6 — the ViewModel side of the pipeline.
 *
 * WHAT MAKES THESE TESTS MEANINGFUL: the **real** [RuleBasedCommandParser] runs
 * (it is pure Kotlin), and only the Android-facing executor is faked. So a
 * transcript really does travel speech → parser → command → executor → state, and
 * a regression anywhere in that chain shows up here.
 *
 * The dispatchers are the test dispatcher: the ViewModel's `withContext` hops
 * complete inline, so assertions are immediate and nothing relies on timing
 * except the deliberately injected clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelPhase6Test {

    /**
     * ONE TestDispatcher for everything: the Main rule and the ViewModel's
     * `withContext` hops share this scheduler, so `runTest(dispatcher)` can drive
     * the whole pipeline deterministically. Mixing dispatchers from different
     * schedulers makes `runTest` fail with "detected use of different schedulers".
     */
    private val dispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(dispatcher)

    private val speech = FakeSpeechRecognizerManager()
    private val tts = FakeTextToSpeechManager()
    private val executor = FakeActionExecutor()
    private val parser: CommandParser = RuleBasedCommandParser()

    /** Controllable wall clock for the duplicate window. */
    private var nowMillis: Long = 1_000L

    private fun viewModel(): AssistantViewModel {
        speech.recognitionAvailable = true
        tts.isReady = true
        return AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { true },
            textToSpeechManager = tts,
            commandParser = parser,
            actionExecutor = executor,
            computeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis }
        )
    }

    /** One complete interaction: tap, speak, final transcript. */
    private fun AssistantViewModel.say(transcript: String) {
        onMicrophoneTapped()
        speech.emitSuccessfulSession(transcript)
    }

    // --- The pipeline ---------------------------------------------------------

    @Test
    fun `a recognised command is parsed and executed with real values`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("turn the volume down")

        assertEquals(listOf<AssistantCommand>(AssistantCommand.VolumeDown), executor.executed)
        val state = vm.uiState.value
        assertEquals("VOLUME_DOWN", state.commandType)
        assertEquals("Volume Down", state.commandLabel)
        assertEquals("VolumeActionHandler", state.handler)
        assertEquals(1, state.history.size)
        assertEquals("VOLUME_DOWN", state.history.first().commandType)
    }

    @Test
    fun `the executor result becomes the card message and the spoken reply`() = runTest(dispatcher) {
        executor.result = ExecutionResult.Success("Silent mode is on.")
        val vm = viewModel()

        vm.say("silent mode")

        assertEquals("Silent mode is on.", vm.uiState.value.message)
        assertEquals("Silent mode is on.", vm.uiState.value.spokenReply)
        assertEquals("Silent mode is on.", vm.uiState.value.lastSpokenText)
    }

    @Test
    fun `an unrecognised sentence is NotUnderstood and stays a success card`() = runTest(dispatcher) {
        executor.result = ExecutionResult.NotUnderstood(ActionMessages.NOT_UNDERSTOOD)
        val vm = viewModel()

        vm.say("make me a sandwich")

        val state = vm.uiState.value
        assertEquals("UNKNOWN", state.commandType)
        assertEquals("UnknownActionHandler", state.handler)
        assertEquals(ActionMessages.NOT_UNDERSTOOD, state.message)
        // Nothing failed, so the card is not red.
        assertEquals(AssistantStatus.SUCCESS, state.status)
    }

    @Test
    fun `a failed action is an ERROR card but is still spoken`() = runTest(dispatcher) {
        executor.result = ExecutionResult.Failure(ActionMessages.NO_MAP_APP)
        val vm = viewModel()

        vm.say("navigate to milan")

        val state = vm.uiState.value
        assertEquals(AssistantStatus.ERROR, state.status)
        assertEquals(ActionMessages.NO_MAP_APP, state.message)
        assertEquals(AssistantStatus.ERROR, state.history.first().status)
        // Speaking a failure must not repaint it as a neutral SPEAKING state.
        assertEquals(ActionMessages.NO_MAP_APP, state.lastSpokenText)
        assertEquals(AssistantStatus.ERROR, state.status)
    }

    @Test
    fun `a missing permission is carried to the state, not acted on`() = runTest(dispatcher) {
        executor.result = ExecutionResult.RequiresPermission(
            message = ActionMessages.DO_NOT_DISTURB_REQUIRED,
            settingsIntentAction = SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
        val vm = viewModel()

        vm.say("silent mode")

        val state = vm.uiState.value
        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            state.pendingSettingsAction
        )
        assertEquals(AssistantStatus.ERROR, state.status)
        // ARIA never opens settings itself: no second recognition was started and
        // no launcher was involved — the screen is only *offered* to the user.
        assertEquals(1, speech.startCount)
    }

    @Test
    fun `a NotSupported outcome is an ERROR because the device could not do it`() = runTest(dispatcher) {
        executor.result = ExecutionResult.NotSupported(ActionMessages.VOLUME_FIXED)
        val vm = viewModel()

        vm.say("volume up")

        assertEquals(AssistantStatus.ERROR, vm.uiState.value.status)
        assertEquals(ActionMessages.VOLUME_FIXED, vm.uiState.value.message)
    }

    @Test
    fun `end of speech shows PROCESSING until a result exists`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.EndOfSpeech)

        // The user has stopped talking but nothing has been executed yet: the
        // card must not look finished.
        assertEquals(AssistantStatus.PROCESSING, vm.uiState.value.status)

        speech.emit(SpeechEvent.FinalResult("volume up"))

        assertEquals(1, executor.executed.size)
        assertTrue(
            "A finished interaction is no longer processing",
            vm.uiState.value.status != AssistantStatus.PROCESSING
        )
    }

    // --- Duplicate suppression ------------------------------------------------

    @Test
    fun `the same command twice within one second is executed once`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("volume up")
        nowMillis += 500L
        vm.say("volume up")

        assertEquals("One spoken word, one action", 1, executor.executed.size)
    }

    @Test
    fun `the same command again after one second is executed`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("volume up")
        nowMillis += AssistantViewModel.DUPLICATE_WINDOW_MILLIS + 1L
        vm.say("volume up")

        assertEquals("A deliberate repeat must work", 2, executor.executed.size)
    }

    @Test
    fun `different commands are never suppressed`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("volume up")
        vm.say("volume down")

        assertEquals(2, executor.executed.size)
        assertEquals(
            listOf<AssistantCommand>(AssistantCommand.VolumeUp, AssistantCommand.VolumeDown),
            executor.executed
        )
    }

    @Test
    fun `a suppressed repeat does not add a second history row`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("volume up")
        nowMillis += 200L
        vm.say("volume up")

        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test
    fun `the duplicate window is exactly one second`() = runTest(dispatcher) {
        // Boundary: at 1000 ms the repeat is still suppressed, at 1001 ms it runs.
        val vm = viewModel()

        vm.say("volume up")
        nowMillis += AssistantViewModel.DUPLICATE_WINDOW_MILLIS
        vm.say("volume up")
        assertEquals(1, executor.executed.size)

        nowMillis += AssistantViewModel.DUPLICATE_WINDOW_MILLIS + 1L
        vm.say("volume up")
        assertEquals(2, executor.executed.size)
    }

    // --- Error handling and lifecycle ----------------------------------------

    @Test
    fun `an executor that throws becomes a friendly failure, not a crash`() = runTest(dispatcher) {
        executor.throwing = IllegalStateException("vendor service exploded")
        val vm = viewModel()

        vm.say("volume up")

        val state = vm.uiState.value
        assertEquals(ActionMessages.UNEXPECTED, state.message)
        assertEquals(AssistantStatus.ERROR, state.status)
        assertEquals(1, state.history.size)
    }

    @Test
    fun `a throwing executor still leaves a usable history row`() = runTest(dispatcher) {
        executor.throwing = RuntimeException("boom")
        val vm = viewModel()

        vm.say("volume up")

        val record = vm.uiState.value.history.first()
        assertEquals("volume up", record.rawSpeech)
        assertEquals("VOLUME_UP", record.commandType)
        assertEquals("VolumeActionHandler", record.handler)
        assertEquals(AssistantStatus.ERROR, record.status)
    }

    @Test
    fun `backgrounding the app returns to idle without executing anything else`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.say("volume up")

        vm.onAppBackgrounded()

        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)
        assertEquals(1, executor.executed.size)
    }

    @Test
    fun `a blank transcript never reaches the parser or the executor`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.FinalResult("   "))

        assertTrue(executor.executed.isEmpty())
        assertEquals(AssistantStatus.ERROR, vm.uiState.value.status)
    }

    @Test
    fun `a failed recognition never executes a command`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMicrophoneTapped()

        speech.emit(SpeechEvent.Failure(SpeechError.NO_MATCH))

        assertTrue(executor.executed.isEmpty())
        assertNull(vm.uiState.value.pendingSettingsAction)
    }

    @Test
    fun `a new attempt clears a previously carried settings action`() = runTest(dispatcher) {
        executor.result = ExecutionResult.RequiresPermission(
            ActionMessages.DO_NOT_DISTURB_REQUIRED,
            SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
        val vm = viewModel()
        vm.say("silent mode")
        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            vm.uiState.value.pendingSettingsAction
        )

        executor.result = ExecutionResult.Success("Volume up.")
        vm.say("volume up")

        assertNull("A stale hint must not survive", vm.uiState.value.pendingSettingsAction)
    }

    @Test
    fun `dismissing the result clears the settings hint and the reply`() = runTest(dispatcher) {
        executor.result = ExecutionResult.RequiresPermission(
            ActionMessages.DO_NOT_DISTURB_REQUIRED,
            SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
        val vm = viewModel()
        vm.say("silent mode")

        vm.dismissResult()

        assertNull(vm.uiState.value.pendingSettingsAction)
        assertNull(vm.uiState.value.spokenReply)
        assertEquals(AssistantStatus.IDLE, vm.uiState.value.status)
        assertFalse("History is kept", vm.uiState.value.history.isEmpty())
    }

    @Test
    fun `muting ARIA still executes the command`() = runTest(dispatcher) {
        // The mute switch controls the voice, never the actions.
        val vm = viewModel()
        vm.onTtsEnabledChanged(false)

        vm.say("volume up")

        assertEquals(1, executor.executed.size)
        assertTrue("Nothing may be spoken", tts.spoken.isEmpty())
        assertTrue(vm.uiState.value.message != null)
    }

    @Test
    fun `the whole flow works with an engine that cannot speak`() = runTest(dispatcher) {
        tts.isReady = false
        val vm = viewModel()

        vm.say("volume up")

        assertEquals(1, executor.executed.size)
        assertTrue("Nothing can be spoken", tts.spoken.isEmpty())
        // The result is still visible as text.
        assertEquals("Media volume up to 60%.", vm.uiState.value.message)
        assertEquals("Media volume up to 60%.", vm.uiState.value.spokenReply)
    }
}
