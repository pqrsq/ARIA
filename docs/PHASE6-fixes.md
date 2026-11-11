# PHASE 6 — post-build fixes

The Phase 6 code was written in an environment with **no JDK and no Android SDK**,
so it was never compiled by the agent that produced it. The first real builds
exposed four defects. This document records them, because the *pattern* matters
more than the individual lines: three were ordinary slips, but one was a genuine
product bug that the tests had been written to miss.

---

## Build 1 — `:app:compileDebugKotlin` failed

```
e: AssistantScreen.kt:308:17 No parameter with name 'commandLabel' found.
```

**Cause.** `commandLabel` belongs to `AssistantUiState`, not to `CommandRecord`,
and the preview block constructs a `CommandRecord`. The card reads the label from
`state.commandLabel`, so the parameter was both invalid and redundant.

**Fix.** Removed the single argument from the `@Preview` data
(`AssistantScreen.kt`). Nothing else referenced it.

**Lesson.** A `@Preview` is still compiled code. Editing sample data in a preview
is a real code change and needs the same care as production code.

---

## Build 2 — 255 tests, 7 failed

Four distinct causes.

### A. Production bug: a spoken failure lost its ERROR card

`AssistantViewModel.onTtsEvent`, `TtsEvent.SpeakStarted`:

```kotlin
if (it.lastSpokenText == null) it
else it.copy(status = AssistantStatus.SPEAKING)
```

`TextToSpeechManager.speak()` raises `SpeakStarted` **before it returns**, so for a
failing command the sequence was:

1. `acknowledgeOutLoud()` → `lastSpokenText` set, status `SPEAKING`
2. `publish()` → status `ERROR`
3. `speak(result)` → platform callback `SpeakStarted` → `lastSpokenText` non-null
   → status overwritten with `SPEAKING`
4. `SpeakFinished` → status `SUCCESS`

A command that failed therefore ended up **green**. On a phone, "open Photoshop"
(no such app) would have shown a successful card.

**Fix.** A settled `ERROR` is never repainted while it is spoken:

```kotlin
if (it.lastSpokenText == null || it.status == AssistantStatus.ERROR) it
else it.copy(status = AssistantStatus.SPEAKING)
```

This also restores the architecture rule from `PROJECT_GUIDE.md` §3: audio
callbacks may only touch audio fields, never a finished result.

**Why the tests missed it.** The failing-path tests asserted `status` immediately
after `vm.say(...)`, which is *after* `publish()` and *before* the asynchronous
`SpeakStarted` callback. They checked the one instant when the bug was invisible.

**Regression tests added** (`AssistantViewModelPhase6Test`):
- `a failure stays an ERROR card after the result has been spoken` — asserts
  `ERROR` after `finishSpeaking()`, for both the card and the history row.
- `a success still becomes SPEAKING while its result is read aloud` — the mirror
  case, so the fix cannot be "stop using SPEAKING".

### B. Test bug: two instances of one command type

`ExecutionResultTest.allCommands()` listed both `PlayMusic("jazz")` and
`PlayMusic("")`. The history id identifies the command **type**, so both correctly
map to `PLAY_MUSIC` and "every command gets a distinct id" failed.

**Fix.** Removed `PlayMusic("")` from the fixture; the empty-query case is covered
in `MediaActionHandlerTest`. The fixture now holds exactly one instance per type.

### C. Test bug: the helper overwrote the flag it was testing

```kotlin
tts.isReady = false   // the test
val vm = viewModel()  // helper then sets tts.isReady = true
```

`AssistantViewModelPhase6Test.viewModel()` assigned `tts.isReady = true`
unconditionally, undoing the test's setup. Fixed by making it a parameter
(`viewModel(ttsReady = false)`).

### D. Test bug: asserted a transient status

`an unrecognised sentence is NotUnderstood and stays a success card` asserted
`SUCCESS` while the result sentence was still being spoken (`SPEAKING`). It now
asserts "never ERROR", then asserts `SUCCESS` after `finishSpeaking()` — i.e. it
checks the settled state rather than an intermediate one.

---

## Test accounting

The Phase 6 documentation originally reported **242** unit tests. The true figure
at the time of Build 2 was **255**, and with the two regression tests above it is
now **257**.

The error came from counting with a `*Test.kt` glob, which skipped
`actions/Fakes.kt` — a file that contains 13 test methods
(`VolumeLevelCalculatorTest`) alongside the fakes. Counts in this project should
be produced by scanning every file under `app/src/test`, and quoted only after a
run confirms them.

---

## What this says about the phase as a whole

- **310 of 315 tests never touched an Android API**, and the compile error was in a
  `@Preview` — neither is a flaw; the ports did their job.
- The one serious defect was a **state-transition bug at the seam between two
  correct-looking pieces** (a callback that fires early, and a handler that
  trusted a field instead of the status). Those are exactly what a device run
  finds and a paper review does not.
- The honest conclusion: for phases that touch Android APIs, `compileDebugKotlin`
  plus `testDebugUnitTest` on a real machine are not optional, and a green card
  for a failed command is worth a manual check on the device.
