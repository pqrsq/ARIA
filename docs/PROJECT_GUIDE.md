# ARIA — Project Guide

A personal Android voice assistant. **Native Kotlin + Jetpack Compose.**
This document is the handover file for both the human owner and any future AI
coding agent. Keep it updated at the end of every phase.

---

## 1. Where the project stands

| Phase | Scope | State |
|---|---|---|
| **1** | Project setup, Compose, structure, base UI, builds + tests | ✅ **done** |
| **2** | Speech-to-text (microphone → `String`) | ✅ **done** |
| **3** | Text-to-speech (reply out loud) | ✅ **done** |
| **4** | `AssistantCommand` model | ✅ **done** (this phase) |
| **5** | Rule-based parser + unit tests | ✅ **done** (this phase) |
| 6 | `ActionExecutor` | ⬜ next |
| 7 | Volume actions | ⬜ |
| 8 | Music actions | ⬜ |
| 9 | Maps actions | ⬜ |
| 10 | App launching | ⬜ |
| 11 | Sound modes (+ permissions) | ⬜ |
| 12 | History + debug mode (persistence) | ⬜ |
| 13 | Error handling pass | ⬜ |
| 14 | Full regression on a physical device | ⬜ |
| 15 | UI polish | ⬜ |
| 16 | Release build | ⬜ |

**Verified for Phase 1 (real build, on a physical device):**
- `:app:assembleDebug` → BUILD SUCCESSFUL, APK produced (`artifacts/aria-phase1-debug.apk`)
- `:app:testDebugUnitTest` → 4 tests, 0 failures
- `:app:assembleDebugAndroidTest` → instrumented test APK compiles
- Toolchain used: JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

**Phase 2** was merged (`PR #1`) and verified by the owner on a physical Samsung
device: speech-to-text works, permission is requested on tap, no crashes.

**Phase 3** was merged (`PR #2`, `PR #3`) and verified by the owner on a physical
Samsung device after two build fixes (a shadowed `val` in `shutdown()`, and one
stale Phase 2 assertion). Speech round-trips and TTS is mute-able.

**Phase 4** was merged (`PR #4`) and verified by the owner on a physical Samsung
device.

**Phase 5 status — read this before trusting the build:**
- 125 unit tests and 6 instrumented tests are written.
- **Phase 5 is purely additive**: three new files in `command/` and one new test
  file. `AssistantViewModel`, `AssistantUiState`, every UI file, every Gradle
  file and the Phase 4 model are **unchanged**.
- ⚠️ Phase 5 has **NOT** been compiled. Same sandbox limitation (no JDK/SDK, no
  access to Google's Maven repo). Run
  `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`
  and expect **125 tests, 0 failures**.

---

## 2. What is intentionally NOT built yet

Speech recognition (Phase 2), speech output (Phase 3), the command vocabulary
(Phase 4) and the parser (Phase 5) **are** built. Still absent, on purpose: no
executor or actions (Phases 6-11), no persistence (Phase 12). The parser exists
and is fully tested but **is not wired in**: `AssistantViewModel` never calls it,
so the UI still records `NO_COMMAND_YET`. That is correct — Phase 6 performs the
wire-up, and wiring it earlier would have meant editing the ViewModel in Phase 5,
which the phase brief forbade. A recognised sentence therefore ends as
a history row labelled `NO_COMMAND_YET`, with the card saying
*"Speech captured. Understanding commands and performing actions comes in
Phase 5."* and ARIA answering only with a neutral acknowledgement ("I heard you."
/ "Done." / "Got it.") — the UI never pretends a command was understood.

---

## 3. Architecture rules (do not break these)

```
SpeechRecognizer ──► String ──► CommandParser ──► AssistantCommand ──► ActionExecutor ──► Android API
   (Phase 2 ✅)      (Phase 2 ✅)  (Phase 5 ✅)        (Phase 4 ✅)        (Phase 6)         (Phases 7-11)

                        ▲                                              │
                        └──────── TextToSpeechManager ◄───────────────┘
                                     (Phase 3 ✅)
```

Everything left of the executor exists: `SpeechRecognizerManager` produces the
`String`, `RuleBasedCommandParser` turns it into a typed command, and
`TextToSpeechManager` speaks back. **The links are not yet joined** — nothing calls
the parser and nothing executes a command. Phase 6 closes the right-hand gap.

- **Never** let a UI or voice class call an Android action API directly.
- **Never** let an `android.*` type cross into `AssistantViewModel` or
  `AssistantUiState`. Three seams enforce it: `SpeechRecognizerManager` (events,
  not `Bundle`s), `TextToSpeechManager` (events, not status codes) and
  `MicrophonePermissionChecker` (a boolean, not a `Context`). That is what makes
  the whole speech round-trip unit-testable on the JVM.
- **Audio callbacks may only touch audio fields.** `onSpeechEvent` and
  `onTtsEvent` must never rewrite a finished result or a history row; a late
  callback that flips the card back to an earlier state is the classic bug here.
- **Never** let the parser execute anything — it only returns a typed command.
- Every handler is independent and receives only what its command carries.
- `CommandParser` stays an **interface** so a later phase can add an
  `LlmCommandParser` without touching the action layer.
- **The command model stays pure Kotlin.** `command/` may not import `android.*`
  (it currently imports nothing at all). Android concepts enter as domain enums
  (`AudioStream`) and are mapped to platform constants only inside the executor.
- **Nothing constructs an `AssistantCommand` except the parser.** The parser
  exists now but is deliberately unwired; the UI keeps recording
  `NO_COMMAND_YET` until Phase 6 connects the pipeline. Do not shortcut that by
  calling the parser from a composable.
- **The parser is a total, pure function.** `parse` never throws, never returns
  null, never logs, has no state, and always falls back to
  `AssistantCommand.Unknown` — precision over recall, always.
- **Normalisation strips leading filler from BOTH the original and the normalised
  text** (`TextNormalizer`). Stripping it from only one breaks entity slicing by
  word position; that bug existed and is documented in `docs/PHASE5.md` §2.
- No root, no hidden APIs, no accessibility abuse, no continuous recording.

### Where each concern goes

| Concern | Location |
|---|---|
| One screen + layout | `ui/AssistantScreen.kt` |
| State model (`AssistantUiState`, `CommandRecord`) | `ui/AssistantUiState.kt` |
| State ownership / coroutines | `ui/AssistantViewModel.kt` |
| Reusable widgets | `ui/components/` |
| Colours / type / theme | `ui/theme/` |
| Speech recognition (Phase 2 ✅) | `voice/SpeechRecognizerManager.kt` (+ `SpeechMessages.kt`) |
| App-scoped singletons (Phase 2 ✅) | `AriaApplication.kt` (owns the one recogniser) |
| Permission *check* (Phase 2 ✅) | `permissions/PermissionManager.kt` |
| Permission *dialog* (Phase 2 ✅) | `ui/MicrophonePermissionController.kt` (needs an Activity) |
| TTS (Phase 3 ✅) | `voice/TextToSpeechManager.kt` (+ `TtsAcknowledgements.kt`, `TtsMessages.kt`) |
| Command model (Phase 4 ✅) | `command/AssistantCommand.kt` (+ `command/AudioStream.kt`) |
| Parser interface + rules (Phase 5 ✅) | `command/CommandParser.kt`, `command/RuleBasedCommandParser.kt` |
| Text normalisation (Phase 5 ✅) | `command/TextNormalizer.kt` |
| Execution fan-out (Phase 6) | `actions/ActionExecutor.kt` + one handler per category |
| Installed-app discovery (Phase 10) | `data/AppRepository.kt` |
| History persistence (Phase 12) | `history/CommandHistoryRepository.kt` |
| Permission helpers (Phases 2, 11) | `permissions/PermissionManager.kt` |
| Speech event/error vocabulary (Phase 2 ✅) | `voice/SpeechRecognizerManager.kt` |
| Speech sentences shown to the user (Phase 2 ✅) | `voice/SpeechMessages.kt` |

---

## 4. Key technical decisions (and why)

1. **Single Activity, all Compose.** Standard modern Android pattern. Future
   screens are composables, not Activities.
2. **MVVM with plain `StateFlow`, no DI framework.** The dependency graph is
   tiny; a factory (`ui/ViewModelFactory.kt`) is enough and keeps builds fast and
   readable. Swapping in Hilt later touches only the factory.
3. **One immutable `AssistantUiState`.** Impossible to render contradictory
   things (e.g. "listening" + "error"). Also makes UI tests trivial.
4. **`minSdk 26`, `targetSdk 35`, `compileSdk 35`.** Broad device coverage, and
   audio/sound-mode APIs behave consistently from API 26 up. `targetSdk 35`
   satisfies Play requirements and keeps Samsung's foreground/permission
   behaviour predictable.
5. **Dark-only theme.** A voice utility is glanceable; one good dark palette
   removes a whole class of visual bugs. Extending to light mode is a one-file change.
6. **No `material-icons-extended`.** That artifact ships thousands of vectors,
   inflated APK size and *broke the memory-limited build here*. The single custom
   icon needed lives in `ui/theme/AriaIcons.kt`; the settings gear comes from
   Material's small core icon set.
7. **One permission, requested at the moment of use.** The manifest declares
   `RECORD_AUDIO` (Android requires the declaration) but nothing prompts until
   the microphone button is tapped. A `<queries>` entry for
   `android.speech.RecognitionService` is required from API 30 on, otherwise
   `isRecognitionAvailable()` can wrongly report `false`; it grants no data
   access. Phase 11 adds the sound-mode permissions.
9. **Speech is on-device first.** Recognition uses the phone's own
   `SpeechRecognizer`; `EXTRA_PREFER_OFFLINE` is available as an opt-in flag
   (`preferOfflineRecognition`) but defaults to `false`, because devices without a
   downloaded offline model answer with an error instead of falling back. ARIA
   stores no audio and sends none.
10. **One recogniser, owned by the `Application`.** `SpeechRecognizer` must be
    created once and reused; creating it per tap rebuilt the platform object on
    every recomposition. `AriaApplication` holds it, the factory injects it.
11. **The permission dialog lives in the UI layer.** A ViewModel must not hold an
    `Activity`, so it only raises a flag (`isAwaitingPermission`); the screen
    performs the request through an activity-result contract, which also
    survives rotation.
12. **TTS is Application-scoped, not ViewModel-scoped.** Binding to the speech
    engine is expensive; shutting it down in `onCleared()` would rebind on every
    launch. The ViewModel only *stops* speech; the Application owns the engine.
13. **`SPEAKING` is a status, not a boolean flag.** One immutable state must be
    able to say "audio is playing while the transcript stays on screen" without
    contradicting itself — the same reason `AssistantStatus` exists at all.
14. **A broken engine is never a failed command.** TTS problems set
    `ttsMessage` and leave the card green; the reply is always written as text
    as well, so muting or a dead engine can never look like data loss.
15. **Acknowledgements are a separate, pure object.** `TtsAcknowledgements` has no
    Android and no state, so the one part of TTS that can be exhaustively tested
    on the JVM is, and Phase 5 swaps its contents without touching the call site.
16. **`AssistantCommand` is a `sealed interface`, not a `sealed class`.** Its
    members share no implementation, so a sealed class would imply a base that
    carries nothing. Members are `data object` (singleton) or `data class` (value).
17. **The model carries `displayName` itself.** One fewer exhaustive `when` in the
    UI, and a new command cannot ship without wording.
18. **`SetVolume` validates in `init`; nothing else validates.** A numeric range is
    objectively wrong at any layer, whereas "is this good enough speech to make a
    command?" belongs to the parser.
19. **Android audio streams are a domain enum (`AudioStream`).** The executor maps
    it to `AudioManager` constants in one place, so the model stays JVM-testable.
20. **The parser is an ordered chain of small rule stages, not one giant `when`.**
    Each stage is independently readable, testable and re-orderable, and the order
    itself is documented as a design decision (see `docs/PHASE5.md` §3).
21. **`NormalizedText` carries three views: `original`, `entitySource`,
    `normalized`.** Matching needs lowercase; entities need the user's
    capitalisation; `Unknown` needs the untouched input.
22. **The parser clamps, the model validates.** `SetVolume` refuses values outside
    0..100, so `"volume 150"` is clamped to 100 *before* construction. Shouting a
    number should give maximum volume, not an exception.
8. **`local.properties` is not committed.** It contains the SDK path of the
   machine that built the project. Android Studio regenerates it automatically.

---

## 5. Build & run

**In Android Studio (recommended for you):** see "How to run" in the chat
response / `PHASE1.md`.

**From the command line**, if you have JDK 17 + Android SDK installed:

```bash
cd ARIA
./gradlew :app:assembleDebug        # APK → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # 45 unit tests, no device needed
./gradlew :app:installDebug         # install on a connected device

# Phase 3 acceptance (all three must pass before calling it done):
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

If `JAVA_HOME` is not set to a JDK 17, Android Studio's bundled JBR is fine, or:

```bash
export JAVA_HOME=/path/to/jdk-17
```

---

## 6. Definition of done for a phase

A phase is finished only when *all* of these are true:

0. **Runnable Android work happens in an environment that can compile it.** If the
   agent has no JDK/Android SDK, say so explicitly and hand over a review-only
   build — Phase 2 was written that way and that transcript must not repeat.
1. `./gradlew :app:assembleDebug` succeeds.
2. `./gradlew :app:testDebugUnitTest` succeeds (add tests for new logic).
3. New behaviour is explained to the owner in plain language.
4. Owner is told exactly what to tap/say to verify it on the phone.
5. This guide's phase table is updated.
6. No unrelated changes were bundled in.

---

## 7. Style rules for the next agent

- Kotlin, 4-space indent, official Kotlin code style.
- Public classes/functions get a short KDoc **explaining why**, not what.
- User-facing strings go in `res/values/strings.xml`.
- No new dependency without a one-line justification in the commit/response.
- Prefer `StateFlow` + immutable state over mutable fields.
- Tests: parser/handler logic as **JUnit unit tests**; screen behaviour as
  **Compose UI tests** (`app/src/androidTest/`). Only put Android APIs behind
  thin, mockable wrappers so the logic underneath stays unit-testable.
- Any ViewModel that collects a flow needs `MainDispatcherRule`
  (`src/test/.../MainDispatcherRule.kt`) — `viewModelScope` is bound to
  `Dispatchers.Main`, which does not exist on a plain JVM.
- When you need a `Context`, ask for the interface instead (see
  `MicrophonePermissionChecker`) so the fake stays trivial.
- New Android capability = new interface + fake in `src/test`, never a direct
  `android.*` call from the ViewModel. `SpeechRecognizerManager` and
  `TextToSpeechManager` are the templates.
- Anything the ViewModel must show as text lives in a `*Messages` object
  (`SpeechMessages`, `TtsMessages`), because the ViewModel cannot call
  `stringResource`. Phase 15 moves them all into `strings.xml` in one pass.

---

## 8. Known environment notes

- This sandbox has ~2 GB RAM. Heavy Gradle steps must run with a reduced heap
  (`-Xmx720m`, `--max-workers=1`, `kotlin.compiler.execution.strategy=in-process`).
  **Your own machine does not need any of that** — the committed
  `gradle.properties` uses normal settings.
- Samsung devices: sound-mode changes (Phase 11) may require
  "Do Not Disturb access"; this will be detected and explained to the user then.
- The agent sandbox used for Phase 2 had **no JDK and no Android SDK**, and its
  network allowlist does not include `services.gradle.org` or Google's Maven repo,
  so Gradle cannot provision itself either. Android phases there can only be
  written and reviewed. Plan the build/verification step for a machine that has
  Android Studio.
- Phase 2 added exactly two dependencies: `kotlinx-coroutines-test` (test-only,
  `1.8.1`) and an explicit `material-icons-core` (it no longer arrives
  transitively from material3, and the settings gear needs it).
- **Phase 3 added no dependencies at all** and changed no toolchain versions.
- Hand-reviewing Kotlin without a compiler is not free: Phase 3 shipped three real
  compile bugs that only a careful read caught (`event.error` accessed on the
  `when` subject instead of the smart-cast value, `Lazy.isInitialized` used on a
  `by lazy` delegated property, and `@Deprecated` applied to an override). Treat
  "reviewed by hand" as *unverified*, not as *likely fine*.
