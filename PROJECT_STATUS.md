# PROJECT STATUS — the single source of truth

**This file is the authority on what has been verified, by whom, and when.**
If any other document disagrees with it — including `docs/PROJECT_GUIDE.md`, which
keeps the phase table purely as a summary — **this file wins.** Update it at the end
of every phase, before anything else.

Version: **1.0.0** (`versionName`), `versionCode` 1.

---

## THE SPLIT THAT MATTERS

There are **two separate records** in this project, and they must never be merged
into one sentence:

| | Who | What they could do |
|---|---|---|
| **A. Environment verified** | the **owner**, on real hardware | built the app, ran the unit suite, ran the instrumented suite on a device, walked the manual checklist |
| **B. Agent execution status** | the **authoring agent**, in a sandbox | read and wrote code; **could not compile or execute anything** |

Both are true at the same time. The project is **built, tested and device-verified**,
*and* the agent that wrote most of it never ran a compiler. Every "⚠️ not executed"
note in `docs/PHASE*.md` refers to **B**. It does **not** mean the phase is unverified.

> **This is exactly the confusion this file exists to prevent.** An external reviewer
> read the "not executed" warnings in the phase documents, concluded that device
> testing had never happened at all, and reported the project as unverified. The
> warnings were accurate about the agent and silent about the owner, and the record
> read as a whole said the wrong thing. From now on: **the agent's limitations are
> described as the agent's limitations, and the owner's verification is recorded here,
> by name, with counts and a date.**

---

## 1. A — ENVIRONMENT VERIFIED (what the owner actually ran)

| | |
|---|---|
| **Device** | Samsung **SM-A566B**, **Android 17** |
| **Unit tests** | **PASSING** — `./gradlew :app:testDebugUnitTest --rerun-tasks`, **390** tests |
| **Instrumented tests** | **30 / 30 PASSING** on the device — `./gradlew :app:connectedDebugAndroidTest` |
| **Manual regression checklist A–F** | **PASSED** |
| **R1 device checks** | **PASSED** — cold-start persistence · clear-during-load · rotation · barge-in |
| **Date of last verification** | **2026-11-16** |
| **Merged state** | `master` at `0f33025` (R1, PR #21) |

### R1 device checks, item by item

1. **Cold-start persistence.** Debug text box ON → cold start → a command typed
   *immediately* → force-stop → reopen → the older history **and** the new command are
   both present. (This was the data-loss race R1 fixed; it failed before.)
2. **Clear during load.** Clear history → force-stop → reopen → still empty.
3. **Rotation mid-session.** History intact.
4. **Barge-in.** TTS unmuted → a command, then immediately another → the card is not
   repainted by the previous utterance's late callback.

### How to reproduce the verification

From the repo root, on a machine with JDK 17 and the Android SDK:

```powershell
.\gradlew :app:testDebugUnitTest --rerun-tasks
.\gradlew :app:connectedDebugAndroidTest     # needs a connected device
```

The instrumented suite includes the **Compose UI tests**, which are the ones that
historically failed and are now fixed (`createAndroidComposeRule<ComponentActivity>()`
plus non-vacuity `onRoot().assertExists()` guards).

---

## 2. B — AGENT EXECUTION STATUS (what the authoring environment could not do)

- **No JDK, no Android SDK, no device.** Every phase was authored in a sandbox where
  `java`, `javac`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `adb` were all absent, and
  where Gradle could not provision itself either.
- **Consequence: nothing was compiled or executed by the agent.** Not once, in any
  phase. Test counts in the phase documents were **counted from the source**
  (`@Test` annotations), not observed from a run.
- **What the agent did instead:** wrote the code, read it back, and replayed state
  machines and the test assertions by hand. That method has a real and documented
  failure rate — see `docs/PHASE6-fixes.md`, and note that across seven build rounds
  the failures were **all in the agent's tests, none in the shipped feature**.
- **"Reviewed by hand" means UNVERIFIED, not "probably fine".** Phase 3 shipped three
  compile errors that only a real compiler caught.

**None of this is a statement about the project.** It is a statement about one
environment. Section 1 is the statement about the project.

---

## 3. Where the work is

**Current milestone: V1.0 release.** Code is correct and verified; R2 fixed the
record. R3 is the release build.

### DONE

| Phase | What |
|---|---|
| 1 | Project setup, Compose, structure, base UI, build + tests |
| 2 | Speech-to-text (microphone → `String`) |
| 3 | Text-to-speech (reply out loud) |
| 4 | `AssistantCommand` model |
| 5 | Rule-based parser + unit tests |
| 6 | Execution layer — volume, sound modes, media, maps, apps, settings. **Phases 7–11 were folded in here** |
| 6.5 | Hardening pass: audit defects D1–D11, A1–A3 |
| P1 | Parser coverage & precision |
| P1.6 | Debug text input — a second input into the existing pipeline |
| 12 | History persistence — JSON file, capped at 200, excluded from backup |
| Regression | Full suite green on hardware |
| R1 | Correctness: startup-race data loss, clear-during-load, stale TTS failure, non-atomic save. **Merged and device-verified** |
| R2 | Documentation truth + version metadata `1.0.0`. **No behaviour change** |

### NEXT — R3 → Release

1. **R3: the release build.** Signing config, `assembleRelease`, R8/ProGuard check
   against the reflection-free Compose + `org.json` paths, and a final
   `connectedDebugAndroidTest` on the release-equivalent build.
2. Confirm the on-device footer reads **"Version 1.0.0"**.
3. Phase **13** (error-handling pass) and **15** (UI polish, including moving the
   `*Messages` objects into `strings.xml`) remain open as *work*, but they are **not**
   release blockers and are not part of R3.

### DEFERRED, deliberately — V1.1 / V2

Not forgotten, not defects. Each was a decision, and reversing one is a phase of its
own:

- **Better / offline speech recognition.** ARIA uses the phone's own
  `SpeechRecognizer`; `EXTRA_PREFER_OFFLINE` exists as an opt-in flag
  (`preferOfflineRecognition`) but defaults to `false`, because devices without a
  downloaded model answer with an error instead of falling back.
- **Persian language support.** Recognition and every parser rule are English-only,
  and the TTS voice selection assumes en-US.
- **Capability registry.** Today each handler independently discovers what the device
  can do. A registry that answers "can this phone change the ringer?" up front would
  replace several `NotSupported` paths.
- **Risk / confirmation model.** Destructive-sounding commands are not gated behind a
  confirmation step. Deliberate: the current command set changes volume, ringer, media
  transport and opens things — nothing irreversible.
- **Conversation memory.** Each utterance is parsed independently; there is no
  multi-turn context ("turn it down a bit more").
- **Dedupe redesign by interaction id.** Duplicate suppression is one second and
  command-value based (D1). An interaction id would be more precise; the current rule
  is simple, tested, and never merges two *different* commands.
- **Record-id collision noted in `docs/PHASE-R1.md` §8.** A command that completes
  while the initial history load is still in flight can receive an id that collides
  with a restored row. Pre-existing, not caused by R1, harmless today (`HistoryList`
  does not use Compose `key()`), and fixing it means rewriting history rows inside a
  coroutine a `clear()` may already have invalidated. **Left open by decision** —
  recorded rather than silently patched.

---

## 4. The honest summary

- The **code** is correct against the audits it has been through (Phase 6's four
  defects, Phase 6.5's D1–D11/A1–A3, R1's four concurrency/durability findings), and
  every one of those fixes was verified **on the device**.
- The **documentation** had drifted — phase tables disagreeing with themselves, KDoc
  promising guarantees the code did not deliver, and "not executed by the agent"
  reading as "never executed". R2 corrected that; this file is the result.
- The **agent** never compiled anything. That is a limitation of one environment, and
  it is now recorded as exactly that.

**Date of last verification: 2026-11-16.** Next update due: after R3.
