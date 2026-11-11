# Phase 3 — Text-to-Speech

**Goal:** ARIA answers out loud. Speech in (Phase 2) → short spoken reply out
(Phase 3). A new `SPEAKING` state, a mute switch, and graceful behaviour when the
device has no usable TTS engine.

**Status: written and reviewed; not compiled.** No JDK or Android SDK was
available in the environment that wrote this phase, so the code was reviewed by
hand but **not built**. Run §5 before trusting it, then the phone checklist in §6.
If anything fails to compile, the fix belongs in this phase.

---

## 1. What the user gets

```
tap 🎙 → speak "turn the volume down"

   ┌───────────────────────────────┐
   │ ● SPEAKING                    │   ← pulsing dot, accent colour
   │ YOU SAID                      │
   │ “turn the volume down”        │
   │ ARIA SAID                     │
   │ “I heard you.”                │   ← the reply, as text too
   └───────────────────────────────┘
              🔊 (phone speaks "I heard you.")
   ┌───────────────────────────────┐
   │ ● COMPLETED                   │   ← back to normal when audio ends
   └───────────────────────────────┘

   HISTORY
   20:14  turn the volume down   OK
          NO_COMMAND_YET
```

Replies rotate through `"I heard you."`, `"Done."`, `"Got it."` so ARIA does not
repeat itself after every sentence. None of them claims an action happened —
there is still no parser.

**The settings sheet gains a "Speak replies" switch.** Turning it off mutes ARIA
immediately (even mid-sentence) and the reply is still shown as text, so "quiet"
never looks like "broken".

---

## 2. Files

### New

| File | Why it exists |
|---|---|
| `voice/TextToSpeechManager.kt` | The **only** code that touches Android's `TextToSpeech`. Exposes an interface (`isReady`, `speak`, `stop`, `shutdown`) and emits `TtsEvent`s with a `TtsError` enum. Integer status codes, `OnInitListener` and `UtteranceProgressListener` never leave this file. |
| `voice/TtsAcknowledgements.kt` | Which phrase to say for turn *n*. Pure logic — no Android, no state — so it is fully unit-tested. |
| `voice/TtsMessages.kt` | User-facing wording per `TtsError` (the Android-free ViewModel cannot call `stringResource`). |
| `test/.../voice/FakeTextToSpeechManager.kt` | Scriptable fake: records what was spoken, can simulate a dead or refusing engine. |
| `test/.../voice/TtsAcknowledgementsTest.kt` | 7 tests: rotation, wrap-around, no action-implying words, error wording. |

### Modified (kept to the minimum)

| File | Change |
|---|---|
| `AssistantUiState.kt` | `AssistantStatus.SPEAKING`, plus `ttsEnabled`, `spokenReply`, `lastSpokenText`, `isTtsAvailable`, `ttsMessage`, and derived `isSpeaking` / `canSpeak`. Everything remains one immutable object. |
| `AssistantViewModel.kt` | Takes a `TextToSpeechManager`. After a successful transcript it calls `acknowledgeOutLoud()`. New `onTtsEnabledChanged()`; `onTtsEvent()` translates engine events; `onAppBackgrounded()`/`onCleared()` silence the voice. |
| `ViewModelFactory.kt` | Hands the manager in from `AriaApplication`. |
| `AriaApplication.kt` | Second lazy engine + a documented `onTerminate()` teardown. |
| `DebugSettingsSheet.kt` | The "Speak replies" switch (disabled with an explanation when the engine is unavailable). |
| `AssistantScreen.kt` | `SPEAKING…` status line, passes the new callbacks. |
| `StatusCard.kt` | `SPEAKING` label/colour, the "ARIA said" line, the TTS notice, three debug rows. |
| `HistoryList.kt` | One new `when` branch so a `SPEAKING` row still reads **OK**. |
| `strings.xml` | Six new strings; footer now says Phase 3. |
| `AssistantViewModelTest.kt` | 16 new tests (32 in that file). |
| `StatusCardTest.kt` | 2 new instrumented tests. |

**Not touched:** `SpeechRecognizerManager.kt`, `SpeechMessages.kt`,
`PermissionManager.kt`, `MicrophonePermissionController.kt`, all Gradle/AGP/Kotlin
versions, and the dependency list (see §7 — **zero new dependencies**).

---

## 3. How it is wired

```
FinalResult("turn the volume down")
  │
  ├─ history row + result card            (Phase 2, unchanged)
  │
  └─ acknowledgeOutLoud()
        ├─ ttsEnabled == false ────────► no audio; reply still shown as text
        ├─ engine unavailable ─────────► no audio; reply shown + "TTS engine not available"
        ├─ speak() returned true ──────► status = SPEAKING
        │                                 └─ onDone/onStop → status = SUCCESS
        └─ speak() returned false ─────► notice only; card stays COMPLETED
```

Engine lifecycle:

```
AriaApplication.textToSpeechManager (lazy, one per process)
  └─ TextToSpeech(context) ──async──► OnInitListener
        ├─ SUCCESS → setLanguage(en-US) → READY → Ready event
        └─ failure / en-US missing   → Unavailable(error) event
```

Boundaries that matter:

- **No `android.*` type crosses into the ViewModel or Compose.** Everything is
  `TtsEvent` / `TtsError`, exactly like Phase 2's `SpeechEvent` / `SpeechError`.
- **Audio events only touch speaking fields.** A late or duplicated callback can
  never rewrite a finished transcript or a history row.
- **A broken engine is never a command failure.** The card stays green
  (`COMPLETED`) with a small warning line; nothing crashes and nothing is lost.
- **en-US is set explicitly** on init, as required.

---

## 4. Lifecycle & leak safety

| Concern | Where it is handled |
|---|---|
| Engine creation | `AriaApplication`, lazily, one per process, **application context only** |
| Init failure / no engine | `TtsEvent.Unavailable` → notice in the card + disabled switch |
| Speaking from the background | `onAppBackgrounded()` calls `stop()` — a leaked utterance would talk over the next app |
| Mute | `onTtsEnabledChanged(false)` calls `stop()` immediately |
| ViewModel destroyed | `onCleared()` stops audio and releases the recogniser; the TTS engine is Application-scoped, so it is *not* shut down there |
| Process teardown | `AriaApplication.onTerminate()` shuts both engines down |

**Limitation, stated plainly:** Android documents `onTerminate()` as
emulator-only — a real phone normally kills the process instead of calling it.
That is safe here: both managers hold only the application context and wrap
system services the OS reclaims with the process, so **no Activity can be kept
alive**. Everything that must stop *promptly* stops in the ViewModel hooks above.

---

## 5. Build & test (do this first)

```bash
cd ARIA
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
./gradlew :app:installDebug      # optional, phone connected
```

Expected: **BUILD SUCCESSFUL** and `:app:testDebugUnitTest` reporting
**45 tests, 0 failures** (4 Phase 1 + 22 Phase 2-and-3 ViewModel + 2 speech
messages + 17 pure-logic Phase 3 — counted per annotation).

---

## 6. How to test Phase 3 on the Samsung phone

| # | Action | Expected result |
|---|---|---|
| 1 | Install, open ARIA, ⚙ | A new **Speak replies** switch, ON by default |
| 2 | Tap 🎙, say "turn the volume down" | Words appear (**HEARING**), then the phone **says "I heard you."** aloud |
| 3 | Watch the status while it talks | **SPEAKING** with a pulsing dot, returning to **COMPLETED** when the audio ends |
| 4 | Look at the card | **ARIA SAID “I heard you.”** is shown as text as well as spoken |
| 5 | Speak again twice | The phrase changes (`"Done."`, `"Got it."`) rather than repeating |
| 6 | ⚙ → switch **Speak replies OFF** | — |
| 7 | Say something again | **Silence.** The transcript and history still update, `ARIA SAID` still shows the text |
| 8 | ⚙ → switch it back **ON**, speak | Speaking resumes |
| 9 | Start a reply, then immediately toggle mute | Audio stops mid-sentence |
| 10 | Start a reply, then press **Home** | Audio stops; nothing keeps talking from the background |
| 11 | Samsung *Settings ▸ General management ▸ Language ▸ Text-to-speech*: set the engine or language to something without English, return to ARIA | Card shows **Text-to-Speech engine not available**; the switch is disabled; speech-to-text still works and nothing crashes |
| 12 | Rotate mid-reply, and rotate twice quickly | No crash, no duplicated speech; the result survives |
| 13 | Use ARIA for ~10 interactions, then check ⚙ → *Developer options ▸ Running services* (or `adb shell dumpsys meminfo com.example.aria`) | Memory stays flat rather than climbing — no per-utterance leak |
| 14 | Press Back to close the app, reopen, speak | Works normally on the second launch |

Rows 3-5, 7, 9-11 and 13 are the ones that prove the brief: the speaking state,
the mute switch, the unavailable-engine path, and no leak.

---

## 7. Dependencies

**None added.** Phase 3 uses only the platform (`android.speech.tts`) and what the
project already had. Gradle, AGP and Kotlin versions are untouched.

---

## 8. Known limitations

- **No voice/rate/pitch selection.** Version 1 uses the engine's default voice
  for en-US. A picker belongs with Phase 15 polish.
- **The mute preference is not persisted.** Rotating keeps it; killing the app
  resets it to ON. Phase 12 (persistence) fixes that.
- **Replies are fixed acknowledgements.** By design: there is no parser until
  Phase 5, so ARIA says "I heard you." and never claims an action.
- **Recognition and speech can overlap briefly** if the user taps the mic while
  ARIA is still talking; the mic is not auto-muted. Deliberate — Phase 6 owns
  interaction flow.
- **`onTerminate()` is emulator-only**, as described in §4.
- **TTS ignores the `SPEAKING` state if the utterance never starts.** Android
  always calls `onStart`/`onDone` (or `onError`), which the manager translates;
  if an OEM engine stays silent, the card remains on `SPEAKING` until the next
  interaction. Not observed on stock engines.

---

## 9. Next phase

**Phase 4 — `AssistantCommand` model.** Planned scope: a sealed `AssistantCommand`
type (volume / music / maps / launch app / sound mode / unknown), pure Kotlin with
no Android imports, plus unit tests. No parsing and no execution yet.

Awaiting confirmation that Phase 3 builds and passes §6.
