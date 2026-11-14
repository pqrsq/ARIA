# PHASE P1.6 — debug text input

Phase P1 is merged and the parser is device-verified. What is *not* reliable is the
microphone on the target device: ambient noise makes a wrong answer ambiguous —
parser defect, or misrecognition? P1.6 removes the recogniser from the picture so
the parser can be exercised deterministically.

This is not a new feature and not a new command path. It is a **second input into
the existing pipeline**.

> ⚠️ **Not executed.** No JDK, no Android SDK in the authoring sandbox, so nothing
> here has been compiled or run. §6 has the exact commands. Behaviour was verified
> by reading the code and by replaying the ViewModel state machine (see §5).

---

## 1. Step 0 — what the code actually said

Checked before changing anything, as required:

| Question | Answer found in the code |
|---|---|
| What does a final recogniser result invoke? | `SpeechEvent.FinalResult` → `onFinalResult(text)` → the whole pipeline inline (parse off-main, dedupe, execute, publish) |
| Is there an existing text entry point? | **No.** `onMicrophoneTapped` / `onMicrophonePermissionResult` / `onReturnedFromSettings` were the only public intents |
| How is debug mode toggled? | `AssistantViewModel.onDebugModeChanged` ← switch in `DebugSettingsSheet` presented from `AssistantScreen`'s `showSettings` |
| Where does the result render? | `StatusCard`, whose `DebugBlock` already shows raw speech / normalized / command / handler / result |
| Is the keyboard handled? | `android:windowSoftInputMode="adjustResize"` + `enableEdgeToEdge()` in `MainActivity`, and **no** `imePadding` anywhere — so a text field would have been covered |

The last row is the one that mattered: the pipeline was ready, the *insets* were
not.

---

## 2. The design decision: one pipeline, two callers

`onFinalResult` was a 70-line private function containing the entire pipeline. The
temptation is to copy the interesting parts into a text entry point — which would
make the affordance worthless, because a green typed test would then say nothing
about speech.

Instead the pipeline was **extracted, not duplicated**:

```
SpeechEvent.FinalResult ─► onFinalResult ─┐
                                          ├─► runCommand(text, source) ─► parse ─► dedupe ─► execute ─► publish
DebugTextInput.submit ──► onSubmitTextInput┘
```

- `runCommand(text, source)` is the old body of `onFinalResult`, unchanged in order
  and guards.
- `onFinalResult` keeps its blank-transcript special case (`NO_MATCH` → `onFailure`)
  and then calls `runCommand` with `VOICE`.
- `onSubmitTextInput` is **three lines**: blank guard, then `runCommand(TEXT)`.

`source` is carried for attribution only. It changes no behaviour — a debug
affordance must not alter the thing it is testing.

**No microphone anywhere on the text path.** No permission check, no
`startListening()`, no recogniser call. Typed input therefore works with
`RECORD_AUDIO` denied and with no speech service installed; both are tested.

---

## 3. What the user sees

- **Hidden unless debug mode is on.** The visibility rule lives in one composable,
  `DebugTextInputIfDebug`, which the screen *and* the instrumented test both use —
  so the test asserts the real rule rather than a copy of it. V1 stays voice-first.
- **Placed directly beneath the microphone**, so typing and speaking are the same
  gesture in the same place.
- **`imePadding()` added to the scrolling column**, before `navigationBarsPadding()`
  (keyboard insets sit below the nav bar; insetting for the bar first would leave a
  dead strip). With `adjustResize` + edge-to-edge this is what keeps the field and
  the result card visible while typing.
- **IME `Done` submits** as well as the Send button; the field **clears** on submit.
- **Disabled while an execution is in flight**, exactly like the microphone: the
  pipeline is single-flight and a second submit would cancel the first one's result.
- **Blank/whitespace is a no-op** — no history row, no execution, no audio. A
  mis-tapped Send button is not a failed command.

---

## 4. The source marker

`InputSource { VOICE, TEXT }`, carried in **two** places:

- `AssistantUiState.inputSource` — the current card; rendered in `StatusCard`'s
  debug block as `input`.
- `CommandRecord.source` — every history row; rendered as `VOLUME_DOWN · TEXT`.

Both default to `VOICE`. `CommandRecord.source` is the **last** parameter, so every
existing positional construction and every `assertEquals` on a record keeps its
meaning — adding it anywhere else would have silently re-bound arguments.

Three behaviours that are easy to get wrong, and are now pinned by tests:

| Situation | Correct marker | Why |
|---|---|---|
| A typed command, then a **spoken failure** | the failure row is `VOICE` | failure events can only come from the recogniser. An earlier draft read the *card's* source, so after a typed command the next spoken failure was filed as `TEXT`. |
| A typed command, then **tapping the microphone** | card returns to `VOICE` | the marker describes the *current* interaction; a card left over from a typed command must not keep claiming `TEXT`. |
| A **suppressed duplicate** | restores the *settled* source with the rest of the card | D1 rebuilds the card from the snapshot; the marker travels with it. |

---

## 5. Tests

### Unit (`app/src/test/.../ui/AssistantViewModelTextInputTest.kt`) — 15 tests

The real `RuleBasedCommandParser` runs; only the Android-facing executor is faked.

| Test | Pins |
|---|---|
| `a typed command produces the same command, result and status as speech` | the central claim: same string, both paths, **identical** command/status/label/handler/message/history; only `source` differs |
| `the spoken acknowledgement and the spoken reply still happen for typed input` | two utterances, same as voice — no shortcut round the audio side effects |
| `typed input is duplicate-suppressed inside the window and never left PROCESSING` | D1 through the new input |
| `two different typed commands inside the window both run` | the window is on the *command*, not on "anything typed recently" |
| `a typed command after a voice command is not suppressed` | the duplicate memory is shared across sources on purpose |
| `typing an out-of-range volume is rejected and performs nothing` | D11: only `Rejected(VOLUME_OUT_OF_RANGE)` reaches the executor |
| `typing two commands in one sentence is rejected, not half-executed` | D10; also that the rejection is *visible* |
| `typed input never starts the recogniser and never asks for the microphone` | `startCount == 0`, no permission flag |
| `typed input works when the microphone permission is denied` | the actual debugging scenario |
| `typed input works when no speech service is installed` | idem |
| `blank typed input does nothing at all` | no row, no execution, no audio, still IDLE |
| `the source marker is TEXT for typed input and VOICE for speech` | and the older row keeps its own attribution |
| `a speech failure is attributed to voice even after a typed command` | the §4 bug |
| `opening the microphone returns the card to the voice source` | the §4 bug |
| `the default source is voice so nothing else can be labelled text by accident` | defaults |

### Instrumented (`app/src/androidTest/.../DebugTextInputTest.kt`) — 5 tests

`debugOff_hidesTheFieldEntirely` · `debugOn_showsTheField` · `submit_invokesTheCallbackWithTheTypedText_andClearsTheField` ·
`blankInput_neverSubmits` · `theFieldIsDisabledWhileAnExecutionIsInFlight`

Totals: unit **347 → 362**, instrumented **9 → 14**.

### What the tests do NOT prove

They do not prove the field is *reachable* on a device (it is behind the gear icon →
debug switch), and they do not prove the inset behaviour under a real keyboard —
`imePadding()` is verified by reading, not by running. Both are on the device script
in §7.

---

## 6. How to verify (not executed here)

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Expect **362** unit tests and **14** instrumented tests.

---

## 7. Device test script — type, no microphone needed

1. Gear icon → **Debug mode** on → close the sheet. The text field appears under the
   microphone. Keyboard must not cover the field or the result card.
2. Type each line and press `Done` (or Send). Each must match the previously verified
   **voice** behaviour exactly:

| Type this | Expect |
|---|---|
| `turn up the volume` | `VolumeUp` |
| `turn down the volume` | `VolumeDown` |
| `pause the music please` | `PauseMusic` |
| `open chrome please` | Chrome launches |
| `turn on silent mode` | `SilentMode` |
| `mute my phone` | `SilentMode` |
| `vibrate` | `VibrateMode` |
| `find my phone` | `Unknown` (honest refusal, no map search) |
| `skip` | `NextTrack` |
| `play Hello, please` | `PlayMusic("Hello")` |
| `play Please Please Please` | `PlayMusic("Please Please Please")` |
| `volume 120 please` | `Rejected` (volume out of range) — nothing executed |
| `the movie was louder than I expected` | `Unknown` |
| `set the volume to 120 percent` | `Rejected` — nothing executed |
| `set the volume to 30 percent and turn off wifi` | `Rejected` (two commands) — nothing executed |
| `play next song` | `NextTrack` |

3. Each result must show `input: text` in the debug block, and its history row must
   read `<COMMAND> · TEXT`.
4. **By design:** re-submitting the *same* command **within 1 second** is
   **suppressed** — the card keeps the first result and the history gains no row.
   Wait more than a second to run it again.
5. Speak once, then check the row reads `<COMMAND> · VOICE` and `input: voice`.
6. Turn debug mode **off**: the field must disappear completely. Turn it back on:
   blank input must do nothing (no row, no sound).

---

## 8. Scope confirmation

Touched: `AssistantViewModel.kt`, `AssistantScreen.kt`, `AssistantUiState.kt`,
`components/DebugTextInput.kt` (new), `components/StatusCard.kt` (one debug line),
`components/HistoryList.kt` (marker), `strings.xml` (two strings), and tests.

Untouched, as instructed: the parser, the normalizer, `ExecutionResult`, every
action handler, TTS, the manifest, Gradle files, dependencies, theme and layout.
No new command, no persistence, no architecture change.

**V1-completion (history persistence) has not been started.** P1.6 stops here.

---

## 9. Post-merge build fix: three assertions compared a state that cannot settle yet

**Only `AssistantViewModelTextInputTest` failed (3 of 15); the pipeline was fine and
all 362 other tests passed.** The failures were mine, and they shared one cause: a
successful result is `SPEAKING` while its sentence is read aloud and only becomes
`SUCCESS` when the engine reports the utterance finished. The tests asserted a
*settled* status without letting it settle.

| Test | What it asserted | Why it failed |
|---|---|---|
| `typed input works when the microphone permission is denied` | `SUCCESS` right after submit | the card is `SPEAKING`; `SUCCESS` only after `finishSpeaking()` |
| `typed input is duplicate-suppressed … never left PROCESSING` | `settled.status == after.status` | the snapshot was taken while `SPEAKING`, so the comparison was `SPEAKING` vs `SPEAKING` for two different reasons — it could not fail meaningfully |
| `a typed command produces the same command, result and status as speech` | `voiceState.status == textState.status` | same defect: both sides were `SPEAKING`, so the assertion was true by accident instead of by evidence |

Fixes are test-only: call `tts.finishSpeaking()` before snapshotting or asserting, and
additionally assert `SUCCESS` explicitly so the comparison cannot pass spuriously.

**Why the line numbers in the log did not help:** the reported lines (85, 140, 248)
were the `fun` declaration lines. kotlinx-coroutines-test rethrows the original
failure from its `runTest` lambda boundary, so the stack always points at the test,
not the assertion. The real cause had to be derived from the code — and the existing
Phase 6 suite showed the idiom that was missing (`tts.finishSpeaking()` before any
settled-status assertion, used in 4 places there).

**Wider check:** every other test in the suite that asserts a settled status either
calls `finishSpeaking()` first or asserts a state that is not speech-dependent
(`REJECTED`, `ERROR`, `IDLE`, `PROCESSING`) — so no other test carries this defect.

---

## 10. Second build fix: a shared fake compared against itself

One test still failed: `a typed command produces the same command, result and status as speech`.

The test runs the same sentence **twice** — once by voice, once typed — using the
**same** `FakeActionExecutor`, which accumulates every command it is asked to
execute. It snapshotted `executor.executed` after run 1:

```
run 1 (voice): [VolumeDown]
after run 2  : [VolumeDown, VolumeDown]
assertion    : [VolumeDown] == [VolumeDown, VolumeDown]   → false
```

The pipeline was correct; the assertion compared a one-element snapshot with a
two-element list. A length mismatch, not a behaviour difference.

Fixed by comparing the second run's slice:

```kotlin
assertEquals(voiceExecuted, executor.executed.drop(voiceExecuted.size))
```

Audited the rest of the file for the same shape — a shared fake compared to itself
across runs. The remaining comparisons (`tts.spoken`, `executor.executed`,
`tts.spoken.size`) all occur in single-run, single-ViewModel tests, so this was the
only instance.
