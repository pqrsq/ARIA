# Phase 1 — Project setup, Compose, base UI

**Goal:** a real, buildable, installable Android app with the ARIA screen and the
project skeleton that later phases drop into. No voice features yet.

**Status: complete and verified.** `assembleDebug`, unit tests and the
instrumented-test build all pass.

---

## 1. What you get

A dark, single-screen app:

```
        ● ARIA            ⚙
          ARIA
      PERSONAL ASSISTANT

         ( 🎙️ )            ← big circular mic button (pulsing when listening)
        Tap to speak
   Simulate a command (Phase 1 only)

   ┌───────────────────────────────┐
   │ ● COMPLETED                   │
   │ YOU SAID                      │
   │ “Turn the volume down”        │
   │ COMMAND                       │
   │ Volume Down                   │
   │ DEBUG (only in debug mode)    │
   │ raw speech / normalized /     │
   │ command / handler / result    │
   └───────────────────────────────┘

   HISTORY
   20:14  Turn the volume down   OK
          VOLUME_DOWN
```

Everything is driven by one immutable `AssistantUiState`, so the screen can
already display **idle / listening / processing / success / error** — the five
states Phase 2 onwards will produce.

---

## 2. Files created

```
ARIA/
├── settings.gradle.kts            Repositories + ":app" module
├── build.gradle.kts               Root: declares plugins, no versions inline
├── gradle.properties              AndroidX + JVM settings (normal, for your PC)
├── gradle/libs.versions.toml      ★ Version catalog: every version in one file
├── gradlew / gradlew.bat          Wrapper → Gradle 8.9
├── .gitignore
├── docs/
│   ├── PROJECT_GUIDE.md           ★ Handover doc for you + future AI agents
│   └── PHASE1.md                  This file
├── artifacts/aria-phase1-debug.apk Prebuilt debug APK (installable)
└── app/
    ├── build.gradle.kts           Compose, SDK levels, deps
    ├── proguard-rules.pro         Empty (minification off in v1)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml          No permissions yet, by design
        │   ├── java/com/example/aria/
        │   │   ├── MainActivity.kt           Single activity, edge-to-edge
        │   │   └── ui/
        │   │       ├── AssistantScreen.kt    The screen (stateless content + preview)
        │   │       ├── AssistantUiState.kt   ★ UI state + CommandRecord
        │   │       ├── AssistantViewModel.kt ★ StateFlow owner, mic seam
        │   │       ├── ViewModelFactory.kt   Dependency injection without Hilt
        │   │       ├── components/
        │   │       │   ├── MicrophoneButton.kt  Big tap target, pulse, a11y label
        │   │       │   ├── StatusCard.kt        You said → Command → Status (+debug)
        │   │       │   ├── StatusDot.kt
        │   │       │   ├── HistoryList.kt       Time · speech · command · OK/FAIL
        │   │       │   ├── SectionHeader.kt
        │   │       │   └── DebugSettingsSheet.kt  Debug toggle + reset history
        │   │       └── theme/
        │   │           ├── Color.kt  Type.kt  Theme.kt  AriaIcons.kt
        │   └── res/  strings, colors, themes, backup rules, adaptive launcher icon
        ├── test/java/.../AssistantUiStateTest.kt       4 JUnit tests
        └── androidTest/java/.../AssistantScreenTest.kt 2 Compose UI tests
```

★ = the files that matter most.

---

## 3. How to run it (first time, step by step)

1. **Install Android Studio** (Ladybug / 2024.2 or newer) from
   <https://developer.android.com/studio>. Accept the default installer options.
   It bundles the JDK and the Android SDK, so you don't install those separately.

2. **Copy the project folder** `ARIA/` somewhere permanent, e.g.
   `Documents/AndroidProjects/ARIA`. (Don't run it from a ZIP file.)

3. **Open it:** Android Studio → *File ▸ Open* → select the **`ARIA`** folder
   (the one containing `settings.gradle.kts`) → *Open*.
   Wait for "Gradle sync" in the bottom status bar to finish. First sync
   downloads dependencies; 2–10 minutes on a normal connection is normal.

   *If a banner says "SDK location not found" or offers to install a
   "SDK Platform / Build Tools", click the offered **Install** link and let it run.*

4. **Prepare your phone** (physical device — this project targets real devices):
   - Settings ▸ About phone ▸ tap **Build number** 7× → "Developer options" appears.
   - Settings ▸ Developer options ▸ enable **USB debugging**.
   - Connect by USB → on the phone choose **Allow USB debugging** (check
     "Always allow").

5. **Select the device** in Studio's toolbar dropdown (it shows your phone's
   model, e.g. "SM-S918B"). Avoid emulators for later phases; Phase 1 is fine on
   either.

6. **Run:** the green ▶ button (or `Shift+F10`). First build takes a few
   minutes; the app opens automatically on the phone.

   APK location after a build:
   `app/build/outputs/apk/debug/app-debug.apk`
   (A prebuilt copy is in `artifacts/aria-phase1-debug.apk` — you can copy it to
   the phone and tap it, allowing "install unknown apps", if you prefer.)

---

## 4. How to test Phase 1

**A. Automated (no phone needed)**

- In Android Studio: *Run ▸ Run 'test'*, or in the Gradle panel:
  `app ▸ Tasks ▸ verification ▸ test`.
- Command line: `./gradlew :app:testDebugUnitTest` → **4 tests, 0 failures**.

**B. On the phone — manual checklist**

| # | Action | Expected result |
|---|---|---|
| 1 | Open **ARIA** from the app drawer | Dark screen, "ARIA", "Tap to speak", big mic button, empty-history hint |
| 2 | Tap the **Settings gear** (top right) | Sheet opens: *Debug mode* switch, *Reset command history* |
| 3 | Turn **Debug mode ON**, close the sheet | — |
| 4 | Tap the **microphone** | A message appears: *"Voice input arrives in Phase 2."* — **no crash**, no microphone prompt (correct: none requested yet) |
| 5 | Tap **"Simulate a command (Phase 1 only)"** | Mic pulses ~0.7 s, status goes LISTENING → PROCESSING → **COMPLETED**; card shows *You said / Command: Volume Down*, plus the DEBUG block (raw speech, normalized, command, handler, result) |
| 6 | Look at **HISTORY** | New row on top: time, "Turn the volume down", `VOLUME_DOWN`, `OK` |
| 7 | **Rotate** the phone with a result on screen | New row disappears from the card but the **history stays** (ViewModel survives rotation) |
| 8 | **Reset command history** in settings | History becomes empty with the hint text |
| 9 | Press Home → reopen ARIA | App relaunches cleanly; history is empty (in-memory only in Phase 1 — persisted in Phase 12) |
| 10 | Settings ▸ Display ▸ font size **largest**, reopen app | Text scales, screen scrolls, nothing overlaps or is cut off |

If all ten pass, Phase 1 is verified. Anything else → tell me what you saw.

---

## 5. What is deliberately fake in Phase 1

The **"Simulate a command"** link and the hard-coded `Volume Down` result.
They exist so the state machine and the UI can be proven *before* adding voice.
Both are deleted in Phase 2 when the real pipeline fills the very same fields:

```kotlin
// Phase 2 replaces this call site, not the state model:
viewModel.onMicrophoneTapped()   // → SpeechRecognizer → String → parser → executor
```

---

## 6. Next phase

**Phase 2 — Speech-to-Text.** Planned scope:

1. `RECORD_AUDIO` permission requested only when the mic is tapped
   (`permissions/PermissionManager.kt`).
2. `voice/SpeechRecognizerManager.kt` wrapping Android's `SpeechRecognizer`,
   exposed to the ViewModel as results/errors — no Android types leak into UI.
3. Recognised text shown in the card ("You said …") and written to history.
4. `QUERY_ALL_PACKAGES`-free, cloud-free: recognition uses the on-device
   recogniser when available.
5. Still **no** command parsing — text stops at the UI, exactly like the diagram.

I'll wait for your confirmation (and your Phase 1 test result) before starting.
