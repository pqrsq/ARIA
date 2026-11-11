# ARIA — Project Guide

A personal Android voice assistant. **Native Kotlin + Jetpack Compose.**
This document is the handover file for both the human owner and any future AI
coding agent. Keep it updated at the end of every phase.

---

## 1. Where the project stands

| Phase | Scope | State |
|---|---|---|
| **1** | Project setup, Compose, structure, base UI, builds + tests | ✅ **done** (this phase) |
| 2 | Speech-to-text | ⬜ next |
| 3 | Text-to-speech | ⬜ |
| 4 | `AssistantCommand` model | ⬜ |
| 5 | Rule-based parser + unit tests | ⬜ |
| 6 | `ActionExecutor` | ⬜ |
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

**Verified in this sandbox (real build, not a guess):**
- `:app:assembleDebug` → BUILD SUCCESSFUL, APK produced (`artifacts/aria-phase1-debug.apk`)
- `:app:testDebugUnitTest` → 4 tests, 0 failures
- `:app:assembleDebugAndroidTest` → instrumented test APK compiles
- Toolchain used: JDK 17, Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01

---

## 2. What is intentionally NOT built yet

No microphone code, no speech recognition, no TTS, no parser, no actions,
no persistence. The microphone button is wired to a *state-only* placeholder so
the UI is honest about what works. Phase 1 exists to prove the skeleton builds
and the screen can render every state.

---

## 3. Architecture rules (do not break these)

```
SpeechRecognizer ──► String ──► CommandParser ──► AssistantCommand ──► ActionExecutor ──► Android API
                                   (Phase 5)          (Phase 4)          (Phase 6)         (Phases 7-11)
```

- **Never** let a UI or voice class call an Android action API directly.
- **Never** let the parser execute anything — it only returns a typed command.
- Every handler is independent and receives only what its command carries.
- `CommandParser` stays an **interface** so Phase 2 (future) can add an
  `LlmCommandParser` without touching the action layer.
- No root, no hidden APIs, no accessibility abuse, no continuous recording.

### Where each concern goes

| Concern | Location |
|---|---|
| One screen + layout | `ui/AssistantScreen.kt` |
| State model (`AssistantUiState`, `CommandRecord`) | `ui/AssistantUiState.kt` |
| State ownership / coroutines | `ui/AssistantViewModel.kt` |
| Reusable widgets | `ui/components/` |
| Colours / type / theme | `ui/theme/` |
| Speech recognition (Phase 2) | `voice/SpeechRecognizerManager.kt` |
| TTS (Phase 3) | `voice/TextToSpeechManager.kt` |
| Command model (Phase 4) | `command/AssistantCommand.kt` |
| Parser interface + rules (Phase 5) | `command/CommandParser.kt`, `command/RuleBasedCommandParser.kt` |
| Execution fan-out (Phase 6) | `actions/ActionExecutor.kt` + one handler per category |
| Installed-app discovery (Phase 10) | `data/AppRepository.kt` |
| History persistence (Phase 12) | `history/CommandHistoryRepository.kt` |
| Permission helpers (Phases 2, 11) | `permissions/PermissionManager.kt` |

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
7. **No permissions in the manifest yet.** Phase 1 needs none. Microphone is
   requested in Phase 2, at the moment of use — which is also what Android's
   guidelines require.
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
./gradlew :app:testDebugUnitTest    # unit tests, no device needed
./gradlew :app:installDebug         # install on a connected device
```

If `JAVA_HOME` is not set to a JDK 17, Android Studio's bundled JBR is fine, or:

```bash
export JAVA_HOME=/path/to/jdk-17
```

---

## 6. Definition of done for a phase

A phase is finished only when *all* of these are true:

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

---

## 8. Known environment notes

- This sandbox has ~2 GB RAM. Heavy Gradle steps must run with a reduced heap
  (`-Xmx720m`, `--max-workers=1`, `kotlin.compiler.execution.strategy=in-process`).
  **Your own machine does not need any of that** — the committed
  `gradle.properties` uses normal settings.
- Samsung devices: sound-mode changes (Phase 11) may require
  "Do Not Disturb access"; this will be detected and explained to the user then.
