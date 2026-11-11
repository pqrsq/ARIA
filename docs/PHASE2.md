# Phase 2 — Speech-to-Text

**Goal:** the microphone becomes real. Tap it, speak, and your words appear in the
card and in the history — as text, nothing more.

**Status: written and reviewed; not yet compiled.** No JDK or Android SDK was
available in the environment that wrote this phase, so the code has been reviewed
by hand but **not built**. Run the commands in §4 before trusting it, then the
phone checklist in §5. If anything fails to compile, the fix belongs in this
phase — nothing here changed Phase 1 behaviour by accident.

---

## 1. What the user gets

```
        ● ARIA            ⚙
          ARIA
      PERSONAL ASSISTANT

         ( 🎙️ )            ← pulses while the phone is listening
        Listening…
        Tap again to finish

   ┌───────────────────────────────┐
   │ ● LISTENING                   │   ← live, while you speak
   │ HEARING                       │
   │ turn the vol                  │
   └───────────────────────────────┘

   …then, after you stop speaking…

   ┌───────────────────────────────┐
   │ ● COMPLETED                   │
   │ YOU SAID                      │
   │ “Turn the volume down”         │
   │ Speech captured. Understanding │
   │ commands and performing actions│
   │ comes in Phase 5.             │
   └───────────────────────────────┘

   HISTORY
   20:14  Turn the volume down   OK
          NO_COMMAND_YET
```

The first time you tap the microphone, Android asks for microphone access. That is
the **only** permission prompt in the app, and it happens when you tap — never on
launch.

---

## 2. What changed, file by file

### New

| File | Why it exists |
|---|---|
| `voice/SpeechRecognizerManager.kt` | The **only** code that touches Android's `SpeechRecognizer`. Wraps it as an interface (`startListening` / `stopListening` / `cancel` / `release`) and emits `SpeechEvent`s plus a `SpeechError` enum. Android's integer error codes and `Bundle`s never leave this file. |
| `voice/SpeechMessages.kt` | The sentences shown for each `SpeechError`. Kept out of the ViewModel so it stays Android-free and JVM-testable. |
| `permissions/PermissionManager.kt` | `Permissions.RECORD_AUDIO`, a `MicrophonePermissionChecker` interface and its Android implementation. |
| `ui/MicrophonePermissionController.kt` | Performs the actual system dialog (needs an activity-result contract, so it cannot live in the ViewModel). Counts consecutive refusals to detect "don't ask again" and offer a route to Settings. |
| `AriaApplication.kt` | Owns the single `SpeechRecognizer` instance for the whole process. |
| `test/.../MainDispatcherRule.kt` | Swaps `Dispatchers.Main` so `viewModelScope` works in plain JVM tests. |
| `test/.../voice/FakeSpeechRecognizerManager.kt` | Scriptable fake used by the new tests. |
| `test/.../ui/AssistantViewModelTest.kt` | 16 tests: permission gating, happy path, errors, lifecycle. |
| `test/.../voice/SpeechMessagesTest.kt` | 2 tests: every error has its own non-technical sentence. |
| `androidTest/.../StatusCardTest.kt` | 2 tests: a real transcript and a real error render in the card. |

### Modified

| File | Change |
|---|---|
| `AssistantViewModel.kt` | Now takes `SpeechRecognizerManager` + `MicrophonePermissionChecker`. `simulateVoiceCommand()` and `MIC_NOT_WIRED_MESSAGE` are **deleted**. Real flow: tap → permission? → listen → partial results live → final result → history. Added `onMicrophonePermissionResult`, `onAppBackgrounded`, `onCleared`. |
| `AssistantScreen.kt` | The "Simulate a command (Phase 1 only)" button is **deleted**. Shows the permission dialog when needed, closes the mic on `ON_STOP`, adds an "Open app settings" action when the permission is blocked. |
| `AssistantUiState.kt` | Two new fields (`isAwaitingPermission`, `isPermissionBlocked`) and `isLiveTranscript`. Phase 1 fields untouched. |
| `ViewModelFactory.kt` | Feeds the manager/checker in from `AriaApplication`. Still no DI framework. |
| `AndroidManifest.xml` | Declares `RECORD_AUDIO`; adds the `android.speech.RecognitionService` `<queries>` entry (needed on API 30+ so availability is reported correctly); registers `.AriaApplication`. |
| `StatusCard.kt` | The live transcript is labelled `HEARING` without quotes; the final one stays `YOU SAID` in quotes. |
| `MicrophoneButton.kt` | `enabled` is now honoured (dims and ignores taps while a transcript is being finalised). |
| `strings.xml` | New: `home_processing`, `home_tap_to_stop`, `home_mic_hint`, `home_waiting_permission`, `home_permission_hint`, `open_app_settings`, `label_hearing`, `version_footer`. Removed the Phase 1 placeholder `home_not_available_yet`. |
| `libs.versions.toml`, `app/build.gradle.kts` | `kotlinx-coroutines-test` (test-only) and an explicit `material-icons-core`. See §6. |

---

## 3. How it is wired

```
tap 🎙
  │
  ├─ no microphone permission ──► isAwaitingPermission = true
  │                                 └─► screen shows the system dialog
  │                                       ├─ allow ──► start listening
  │                                       └─ deny  ──► FAILED + "ARIA needs …"
  │                                                     (after 2 refusals: Open app settings)
  ├─ no speech service installed ──► FAILED + explanation, no crash
  └─ ready ──► LISTENING
                 │  PartialResult  → "Hearing: …" updates live
                 │  EndOfSpeech    → PROCESSING
                 └─ FinalResult   → COMPLETED + one history row (NO_COMMAND_YET)
```

Boundaries that matter:

- **The `String` is the end of this phase.** `rawSpeech` receives it,
  `normalizedText` holds a lowercased copy for the debug block, and the card says
  in plain words that understanding arrives in Phase 5.
- **No parsing, no actions, no TTS** — as scoped. `commandLabel` stays `null`, so
  even the UI cannot suggest a command was recognised.
- **The microphone never stays open in the background.** `ON_STOP` cancels the
  session; the platform's follow-up `ERROR_CLIENT` is swallowed inside the manager
  so the user is not shown a fake error.

---

## 4. Build & test (do this first)

```bash
cd ARIA
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
./gradlew :app:installDebug      # optional, with a phone connected
```

Expected: **BUILD SUCCESSFUL**, and `:app:testDebugUnitTest` reporting
**22 tests, 0 failures** (4 from Phase 1 + 18 new).

If the build fails, the most likely culprits — in the order the compiler will hit
them — are: a missing import in `AssistantScreen.kt` or `StatusCard.kt`
(hand-written edits), and the `material-icons-core` alias in
`gradle/libs.versions.toml`. Send the first error and it will be fixed in this
phase.

---

## 5. How to test Phase 2 on the phone

| # | Action | Expected result |
|---|---|---|
| 1 | Uninstall the Phase 1 build first, then install this one | Clean slate, so the permission prompt actually appears |
| 2 | Open ARIA | No permission prompt on launch. "Tap to speak", empty history |
| 3 | Tap the **microphone** | Android's microphone dialog appears. Allow it |
| 4 | Wait, say "turn the volume down" | Card shows **LISTENING**; the `HEARING` line updates while you speak; mic pulses |
| 5 | Stop speaking (or tap the mic again) | **PROCESSING**, then **COMPLETED**, with “Turn the volume down” quoted |
| 6 | Read the card's last line | "Speech captured. Understanding commands and performing actions comes in Phase 5." |
| 7 | Look at **HISTORY** | One row: time, your sentence, `NO_COMMAND_YET`, `OK` |
| 8 | Enable **Debug mode** in ⚙, speak again | The debug block shows `raw speech`, `normalized`, `command = NO_COMMAND_YET`, `handler = None (Phase 2 captures speech only)`, `result = SUCCESS` |
| 9 | Tap the mic and stay silent ~10 s | **FAILED** with "I heard nothing. Tap the microphone and speak." (or "I did not catch that…"). No crash, no stuck spinner |
| 10 | Deny the permission instead (clear app data first to re-ask) | **FAILED** with "ARIA needs the microphone to hear you…" and **no** listening |
| 11 | Deny twice in a row | Same card plus **Open app settings**; tapping it opens ARIA's system page |
| 12 | Start listening, then press **Home** | Mic closes immediately; reopening the app is idle, not stuck in LISTENING |
| 13 | **Rotate** the phone during listening | The screen follows the rotation; the session is not duplicated |
| 14 | **Rotate** while the permission dialog is open | The answer is still delivered (the request survives configuration change) |
| 15 | Settings ▸ Display ▸ font size **largest** | New labels wrap, nothing overlaps |

Rows 3-8 and 10-12 are the ones that matter most: they cover the permission
rules, the honest "captured, not understood" wording, and the privacy rule.

---

## 6. Dependencies added (both justified)

1. **`org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1`** — `testImplementation`
   only. Without `Dispatchers.setMain` the ViewModel cannot be unit-tested at all,
   because `viewModelScope` requires a main looper that a JVM test lacks. Adds
   nothing to the APK.
2. **`androidx.compose.material:material-icons-core`** — declared explicitly now
   that Compose Material3 no longer pulls it in transitively. It is the *small*
   icon set (the settings gear already used in Phase 1), not the thousands-of-
   vectors `material-icons-extended` that the project rule forbids.

---

## 7. Known limits (deliberate, for later phases)

- **No waveform or volume meter.** `onRmsChanged` is deliberately ignored; a level
  indicator is Phase 15 polish.
- **Cloud vs on-device depends on the device.** ARIA uses whatever recogniser the
  phone provides. `preferOfflineRecognition` exists on the manager but defaults to
  `false`, because a device with no offline model installed fails instead of
  falling back. Nothing is stored or sent by ARIA itself.
- **No language picker.** Recognition follows the device locale.
- **History is still in memory.** Rotating keeps it, killing the app does not —
  Phase 12 is where persistence lands.
- **`dismissResult()` is still unused** (as in Phase 1). It becomes useful when
  tapping outside the card should clear it.

---

## 8. Next phase

**Phase 3 — Text-to-Speech.** Planned scope:

1. `voice/TextToSpeechManager.kt` behind an interface, exactly like
   `SpeechRecognizerManager` (Android-free events: `Speaking`, `Finished`,
   `Failed`), owned by `AriaApplication`.
2. A short spoken reply for the captured sentence — *"You said: turn the volume
   down"* — so the loop feels closed before any action exists.
3. Language fallback handling when the device has no TTS data installed.
4. Still **no** command parsing and **no** actions.
5. Release the engine in `onCleared`, alongside the recogniser.

Awaiting confirmation that Phase 2 builds and passes the checklist in §5.
