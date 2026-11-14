package com.example.aria.ui

import com.example.aria.MainDispatcherRule
import com.example.aria.actions.ActionMessages
import com.example.aria.actions.ExecutionResult
import com.example.aria.actions.FakeActionExecutor
import com.example.aria.actions.SettingsActions
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /**
     * @param ttsReady false simulates a device whose speech engine never bound.
     *   It is a parameter because the helper sets [FakeTextToSpeechManager.isReady]
     *   itself — an earlier version defaulted it to `true` here and silently
     *   undid a test that had set it to `false` beforehand.
     */
    private fun viewModel(
        ttsReady: Boolean = true,
        dndGranted: Boolean = false
    ): AssistantViewModel {
        speech.recognitionAvailable = true
        tts.isReady = ttsReady
        return AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { true },
            textToSpeechManager = tts,
            commandParser = parser,
            actionExecutor = executor,
            computeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis },
            policyAccessChecker = PolicyAccessChecker { dndGranted }
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
        // Nothing failed, so the card is never red. While the reply is being
        // spoken the status is SPEAKING, so the *settled* state is asserted:
        // once the audio ends, a NotUnderstood result is a success.
        assertNotEquals(AssistantStatus.ERROR, state.status)
        tts.finishSpeaking()
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
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
    fun `a failure stays an ERROR card after the result has been spoken`() = runTest(dispatcher) {
        // REGRESSION (found by the first real build of Phase 6): `speak()` raises
        // TtsEvent.SpeakStarted *before it returns*, and the handler for that
        // event used to repaint any non-null `lastSpokenText` as SPEAKING. A
        // failing command was therefore shown as SPEAKING and then flipped to
        // SUCCESS when the audio ended — a failed action that looked successful.
        executor.result = ExecutionResult.Failure(ActionMessages.NO_MAP_APP)
        val vm = viewModel()

        vm.say("navigate to milan")
        tts.finishSpeaking()

        val state = vm.uiState.value
        assertEquals("A spoken failure must stay red", AssistantStatus.ERROR, state.status)
        assertEquals(AssistantStatus.ERROR, state.history.first().status)
        assertEquals(ActionMessages.NO_MAP_APP, state.lastSpokenText)
    }

    @Test
    fun `a success still becomes SPEAKING while its result is read aloud`() = runTest(dispatcher) {
        // The other half of the regression: the fix must not stop successful
        // results from showing the speaking state.
        executor.result = ExecutionResult.Success("Silent mode is on.")
        val vm = viewModel()

        vm.say("silent mode")

        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)
        tts.finishSpeaking()
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
    }

    // ── PHASE 6.5 ────────────────────────────────────────────────────────────

    // D1 — duplicate suppression must not leave the card on PROCESSING.

    @Test
    fun `a suppressed duplicate leaves the card settled, never PROCESSING`() = runTest(dispatcher) {
        // BEFORE: onFinalResult reset the card to PROCESSING *before* the
        // duplicate check, then returned without publishing, so the UI sat on
        // "Processing…" forever after a repeated command.
        val vm = viewModel()

        vm.say("volume up")
        tts.finishSpeaking()
        val settled = vm.uiState.value.status
        assertEquals(AssistantStatus.SUCCESS, settled)

        nowMillis += 300L
        vm.say("volume up")

        assertEquals("Executed once", 1, executor.executed.size)
        assertNotEquals("Must not be stuck processing", AssistantStatus.PROCESSING, vm.uiState.value.status)
        assertEquals("The earlier card is untouched", settled, vm.uiState.value.status)
    }

    @Test
    fun `a suppressed duplicate keeps the earlier message and history`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("volume up")
        tts.finishSpeaking()
        val firstMessage = vm.uiState.value.message

        nowMillis += 200L
        vm.say("volume up")

        assertEquals(firstMessage, vm.uiState.value.message)
        assertEquals(1, vm.uiState.value.history.size)
    }

    @Test
    fun `a different command after a duplicate still runs`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.say("volume up")
        nowMillis += 100L
        vm.say("volume down")

        assertEquals(2, executor.executed.size)
    }

    // D2 — the permission hint is carried, and only a real grant retries.

    @Test
    fun `a permission refusal records the command to retry`() = runTest(dispatcher) {
        executor.result = ExecutionResult.RequiresPermission(
            ActionMessages.DO_NOT_DISTURB_REQUIRED,
            SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
        val vm = viewModel(dndGranted = false)

        vm.say("silent mode")

        val state = vm.uiState.value
        assertEquals(SettingsActions.NOTIFICATION_POLICY_ACCESS, state.pendingSettingsAction)
        assertEquals("silent mode", state.pendingRawSpeech)
        assertEquals("The refused command is remembered", 1, executor.executed.size)
    }

    @Test
    fun `returning from settings without the grant keeps the hint`() = runTest(dispatcher) {
        executor.result = ExecutionResult.RequiresPermission(
            ActionMessages.DO_NOT_DISTURB_REQUIRED,
            SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
        val vm = viewModel(dndGranted = false)
        vm.say("silent mode")

        vm.onReturnedFromSettings()

        // Nothing was retried, and the button must still be available.
        assertEquals(1, executor.executed.size)
        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            vm.uiState.value.pendingSettingsAction
        )
    }

    @Test
    fun `returning from settings after the grant retries the original command`() = runTest(dispatcher) {
        executor.result = ExecutionResult.RequiresPermission(
            ActionMessages.DO_NOT_DISTURB_REQUIRED,
            SettingsActions.NOTIFICATION_POLICY_ACCESS
        )
        var granted = false
        val vm = AssistantViewModel(
            speechRecognizerManager = speech,
            permissionChecker = MicrophonePermissionChecker { true },
            textToSpeechManager = tts,
            commandParser = parser,
            actionExecutor = executor,
            computeDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            clockMillis = { nowMillis },
            policyAccessChecker = PolicyAccessChecker { granted }
        )
        vm.say("silent mode")
        assertEquals(
            SettingsActions.NOTIFICATION_POLICY_ACCESS,
            vm.uiState.value.pendingSettingsAction
        )

        granted = true
        executor.result = ExecutionResult.Success("Silent mode is on.")
        vm.onReturnedFromSettings()

        assertNull("Cleared only after the grant", vm.uiState.value.pendingSettingsAction)
        assertEquals(2, executor.executed.size)
    }

    // D7 — utterance ids and queueing.

    @Test
    fun `the result is appended so it does not cut the acknowledgement`() = runTest(dispatcher) {
        // BEFORE: every utterance used QUEUE_FLUSH, so the result sentence cut the
        // acknowledgement off mid-word.
        val vm = viewModel()

        vm.say("volume up")

        assertEquals(
            listOf(SpeakPriority.REPLACE, SpeakPriority.APPEND),
            tts.priorities
        )
    }

    @Test
    fun `a stale finish event does not settle the current card`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.say("volume up")
        val current = tts.lastUtteranceId()
        assertEquals(AssistantStatus.SPEAKING, vm.uiState.value.status)

        // A callback belonging to an older utterance arrives late.
        tts.emit(TtsEvent.SpeakFinished(utteranceId = "some-old-utterance"))

        assertEquals(
            "A superseded utterance must not settle the card",
            AssistantStatus.SPEAKING,
            vm.uiState.value.status
        )

        // The real one settles it.
        tts.emit(TtsEvent.SpeakFinished(current))
        assertEquals(AssistantStatus.SUCCESS, vm.uiState.value.status)
    }

    // D10 / D11 — a rejection is neutral, not green and not red.

    @Test
    fun `a rejected command renders as a neutral card`() = runTest(dispatcher) {
        // Note: no executor result is stubbed — the real parser produces the
        // Rejected command, so this also proves the two halves are wired together.
        val vm = viewModel()

        vm.say("set the volume to 30 percent and turn off wifi")

        val state = vm.uiState.value
        assertEquals(AssistantStatus.REJECTED, state.status)
        assertEquals(ActionMessages.ONE_COMMAND_AT_A_TIME, state.message)
        assertEquals(AssistantStatus.REJECTED, state.history.first().status)
        // Speaking it must not repaint it as a success…
        tts.finishSpeaking()
        assertEquals(AssistantStatus.REJECTED, vm.uiState.value.status)
    }

    @Test
    fun `an out-of-range volume reaches the card as a rejection`() = runTest(dispatcher) {
        // End to end: real parser -> Rejected(VOLUME_OUT_OF_RANGE) -> neutral card.
        val vm = viewModel()

        vm.say("set the volume to 120 percent")

        assertEquals(ActionMessages.VOLUME_OUT_OF_RANGE, vm.uiState.value.message)
        assertEquals(AssistantStatus.REJECTED, vm.uiState.value.status)
        // Nothing may be *performed*: the only thing that reaches the executor is
        // the refusal itself, which the real executor routes to the fallback
        // handler rather than to a volume handler.
        assertEquals(
            listOf<AssistantCommand>(
                AssistantCommand.Rejected(RejectionReason.VOLUME_OUT_OF_RANGE)
            ),
            executor.executed
        )
    }

    @Test
    fun `the whole flow works with an engine that cannot speak`() = runTest(dispatcher) {
        val vm = viewModel(ttsReady = false)

        vm.say("volume up")

        assertEquals(1, executor.executed.size)
        assertTrue("Nothing can be spoken", tts.spoken.isEmpty())
        // The result is still visible as text.
        assertEquals("Media volume up to 60%.", vm.uiState.value.message)
        assertEquals("Media volume up to 60%.", vm.uiState.value.spokenReply)
    }
}
