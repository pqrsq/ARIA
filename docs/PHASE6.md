# PHASE 6 — Command Execution Layer

**Status: implemented, NOT compiled.** Read §10 before trusting anything below.

Phase 6 connects the parser (Phase 5) to real Android actions. It is the first
phase that touches system services, and it is deliberately structured so that as
much of that as possible stays testable on a plain JVM.

```
SpeechRecognizer → RuleBasedCommandParser → AssistantCommand → ActionExecutor
   → AndroidActionExecutor → *ActionHandler → ExecutionResult
       → AssistantViewModel → AssistantUiState → TextToSpeechManager
```

---

## 1. What changed, in one table

| Kind | File | Why |
|---|---|---|
| new | `actions/ExecutionResult.kt` | The only thing a handler may return (5 outcomes). |
| new | `actions/ActionExecutor.kt` | The interface the ViewModel depends on. |
| new | `actions/AndroidActionExecutor.kt` | One exhaustive `when` → 7 handlers + error boundary. |
| new | `actions/VolumeActionHandler.kt` | Volume up/down/set/mute, with read-back. |
| new | `actions/SoundModeActionHandler.kt` | Silent / vibrate / normal, gated on DND access. |
| new | `actions/MediaActionHandler.kt` | Open a music app; dispatch transport keys. |
| new | `actions/MapsActionHandler.kt` | `geo:` and `google.navigation:` hand-offs. |
| new | `actions/AppActionHandler.kt` | "open <app>" via `PackageManager`. |
| new | `actions/SettingsActionHandler.kt` | The four settings screens. |
| new | `actions/UnknownActionHandler.kt` | The "I didn't understand" outcome. |
| new | `actions/ActionMessages.kt` | Every sentence, one definition. |
| new | `actions/VolumeLevelCalculator.kt` | Percentage ↔ stream index (pure). |
| new | `actions/UriEncoding.kt` | RFC 3986 encoding (pure; `Uri.encode` is Android). |
| new | `actions/AppNameMatcher.kt` | Exact → starts-with → contains ranking (pure). |
| new | `actions/HandlerNames.kt` | Command → handler *name* for the history row. |
| new | `actions/CommandTypeNaming.kt` | Command → `UPPER_SNAKE_CASE` history id. |
| new | `actions/SettingsActions.kt` | The exact `Settings.ACTION_*` strings. |
| new | `actions/MediaKeyPresses.kt` | Down+up pairs per media key (pure). |
| new | `actions/AudioStreamMapping.kt` | `AudioStream` → `AudioManager.STREAM_*`. |
| new | `actions/AudioVolumeController.kt` + `AndroidAudioVolumeController.kt` | Volume seam. |
| new | `actions/RingerController.kt` + `AndroidRingerController.kt` | Ringer seam. |
| new | `actions/InstalledAppsProvider.kt` | `PackageManager` query seam. |
| new | `actions/LaunchSpec.kt` | "What to open" as data + `Launcher` seam. |
| new | `actions/AndroidLauncher.kt` | The only place an `Intent` is built. |
| new | `actions/ActionExecutorFactory.kt` | Wiring, from one `Context`. |
| new | `permissions/PolicyAccessChecker.kt` | DND access as a testable port. |
| **modified** | `permissions/PermissionManager.kt` | Added `DndAccessChecker` (appended; existing symbols untouched). |
| **modified** | `ui/AssistantViewModel.kt` | Parser + executor wired, dedupe, dispatchers, clock. |
| **modified** | `ui/AssistantUiState.kt` | One new field: `pendingSettingsAction`. |
| **modified** | `ui/ViewModelFactory.kt` | The two new constructor arguments. |
| **modified** | `AriaApplication.kt` | Owns the parser + executor. |
| **modified** | `AndroidManifest.xml` | Four narrow `<queries>` entries. |
| **modified** | `ui/AssistantScreen.kt` | Preview data updated to a real result. |
| **modified** | `androidTest/StatusCardTest.kt` | Sample data updated to a real result. |
| new tests | `actions/ExecutionResultTest`, `VolumeActionHandlerTest`, `SoundModeActionHandlerTest`, `MediaActionHandlerTest`, `MapsActionHandlerTest`, `AppActionHandlerTest`, `SettingsActionHandlerTest`, `AndroidActionExecutorTest`, `Fakes.kt` (holds `VolumeLevelCalculatorTest`), `ui/AssistantViewModelPhase6Test.kt` | 117 new test methods |

---

## 2. Two architectural corrections to the brief (approved beforehand)

### 2.1 `UnknownActionHandler` returns `NotUnderstood`, not `Failure`

The brief said the unknown handler returns `Failure("I didn't understand that
command.")`. That would have made an ordinary, expected outcome look like a
malfunction: the result card is painted from the `ExecutionResult` type, so a
`Failure` would show a red **FAILED** card and write a `FAIL` pill in the history
for a sentence the parser simply did not recognise.

`ExecutionResult.NotUnderstood` was added instead, and the ViewModel maps it to
`AssistantStatus.SUCCESS`. **The user-visible sentence is unchanged and is still
exactly `"I didn't understand that command."`** — only the colour semantics
differ. This matches the project rule already stated in Phase 4's
`AssistantCommand.Unknown` KDoc ("a normal, expected outcome … not an error
state").

### 2.2 `permissions/PermissionManager.kt` was extended, not replaced

That file already existed and holds `Permissions`, `MicrophonePermissionChecker`
and `AndroidPermissionChecker`. Creating a second `PermissionManager` class would
have produced two competing permission abstractions in one package, so Phase 6:

- adds `permissions/PolicyAccessChecker.kt` — the `fun interface PolicyAccessChecker`
  plus `NotificationPolicyAccessChecker` (the Android implementation);
- **appends** a `DndAccessChecker` object to `PermissionManager.kt`, so the
  "every permission question lives in `permissions/`" convention holds.

Nothing was renamed and no existing symbol changed, so Phase 2's microphone flow
is untouched.

---

## 3. Why every handler is behind a port

`AudioManager`, `PackageManager`, `NotificationManager` and `startActivity` are
exactly the things that cannot run in a JVM unit test. Each handler therefore
takes a small interface (`AudioVolumeController`, `RingerController`,
`InstalledAppsProvider`, `Launcher`, `MediaKeyController`,
`PolicyAccessChecker`) and the Android class behind it is mechanical — one call
per method, no decisions.

Two benefits that are worth stating:

1. **The awkward device behaviours are testable.** "The system accepted the call
   but the volume did not move" is the bug that produces a false success; a fake
   can reproduce it in one line.
2. **The *list of Intents* side is the only Android-specific part.** `LaunchSpec`
   is data, so tests assert the exact `geo:0,0?q=Eiffel%20Tower` string instead of
   trusting an untestable `Intent` constructor call.

---

## 4. Command-by-command behaviour (what the user actually sees and hears)

| Said | Command | Android call | Message |
|---|---|---|---|
| "volume up" | `VolumeUp` | `adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE, FLAG_SHOW_UI)` | "Media volume up to 60%." (the read-back value) |
| "volume down" | `VolumeDown` | `…ADJUST_LOWER…` | "Media volume down to 47%." |
| at the ceiling | `VolumeUp` | same | "Media volume is at maximum." |
| "set volume to 40" | `SetVolume(40)` | `setStreamVolume(STREAM_MUSIC, 6, 0)` | "Media volume is now 40%." |
| "set ring volume to 50" | `SetVolume(50, RING)` | `STREAM_RING` | "Ring volume is now 53%." (15-step stream) |
| "mute the music" | `MuteMedia` | `ADJUST_MUTE` on `STREAM_MUSIC` | "Media muted." |
| fixed-volume device | any volume | **nothing** | NotSupported: "This device keeps its volume fixed…" |
| "silent mode" | `SilentMode` | `ringerMode = RINGER_MODE_SILENT` | "Silent mode is on." (only after read-back) |
| "silent mode", no DND access | `SilentMode` | **nothing** | RequiresPermission: "ARIA needs Do Not Disturb access to change sound modes." + `android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS` |
| "vibrate mode" | `VibrateMode` | `RINGER_MODE_VIBRATE` | "Vibrate mode is on." |
| "normal ring mode" | `NormalRingMode` | `RINGER_MODE_NORMAL` | "The ringer is back on." (no permission needed) |
| "play jazz" | `PlayMusic("jazz")` | `makeMainSelectorActivity(ACTION_MAIN, CATEGORY_APP_MUSIC)` | "Opening your music app." |
| "pause the music" | `PauseMusic` | `dispatchMediaKeyEvent` PAUSE **down + up** | "Sent a pause request to the media app." |
| "next track" | `NextTrack` | KEYCODE_MEDIA_NEXT down + up | "Sent a next track request…" |
| "open maps" | `OpenMaps` | `VIEW geo:0,0` | "Opening your map app." |
| "find a cafe" | `SearchLocation("cafe")` | `VIEW geo:0,0?q=cafe` | "Opening a map search for \"cafe\"." |
| "navigate to Milan" | `NavigateTo("Milan")` | `VIEW google.navigation:q=Milan` | **"Opening navigation to Milan in your map app."** |
| no map app | any maps command | `ActivityNotFoundException` caught | Failure: "No map app is installed" |
| "open Chrome" | `OpenApp("Chrome")` | `getLaunchIntentForPackage` | "Opening Chrome." |
| "open Photoshop" | `OpenApp("Photoshop")` | nothing matched | Failure: "Could not find an app named \"Photoshop\"." |
| "open wifi settings" | `OpenWifiSettings` | `ACTION_WIFI_SETTINGS` | "Opening Wi-Fi settings." |
| "open battery settings" | `OpenBatterySettings` | `ACTION_POWER_USAGE_SUMMARY` | "Opening battery settings." |
| a screen the device lacks | any settings | `ActivityNotFoundException` caught | NotSupported: "This device does not offer that settings screen." |
| anything unrecognised | `Unknown(text)` | nothing | NotUnderstood: "I didn't understand that command." |

**Wording rule used throughout:** ARIA says what it *did*. It "opens" things
because it cannot see past the hand-off, it reports read-back values because the
request may be ignored, and it never says "playing", "navigated" or "searched".

---

## 5. Volume arithmetic

`VolumeLevelCalculator.indexForPercentage(percentage, maxIndex)`:

```
roundToInt(percentage.coerceIn(0,100) / 100.0 * maxIndex).coerceIn(0, maxIndex)
```

- 50% of a 15-step stream → `roundToInt(7.5)` = **8** (truncation would give 7).
- `maxIndex <= 0` → `0` instead of a division by zero.
- Read-back uses the inverse, and messages always quote the *inverse of the value
  actually read*, never the requested percentage — a 15-step stream cannot
  represent every percentage, so "50%" legitimately reads back as "53%".

Because `AudioManager.setStreamVolume`/`adjustStreamVolume` return `void`, a
change can be silently refused (policy, managed profile, OEM quirk). Every branch
reads the volume back; if nothing moved, the result is a `Failure` that states the
real level.

---

## 6. Coroutine, threading and duplicate handling

- `onFinalResult` cancels any in-flight execution, sets `PROCESSING`, then runs:
  `parse` on `Dispatchers.Default` → duplicate check → acknowledgement spoken →
  `execute` on `Dispatchers.IO` → publish + speak the result.
- State is only mutated in the `viewModelScope` coroutine **after** the
  `withContext` hops complete, so the `MutableStateFlow` is never written
  concurrently from two threads.
- `Dispatchers.Default`/`IO` and a `clockMillis` lambda are **constructor
  parameters with production defaults**, so tests drive them deterministically
  (`UnconfinedTestDispatcher` + a counter clock) without any dependency change.
- `CancellationException` is rethrown; any other `RuntimeException` from the
  executor becomes `Failure(ActionMessages.UNEXPECTED)`. `AndroidActionExecutor`
  has its own outer `try/catch` for the same reason.
- **Duplicate suppression:** the same command *value* within 1 000 ms is ignored
  (nothing executed, no second history row). Different commands are never
  suppressed, so a deliberate "volume up, volume up" after a second still works.
- `onAppBackgrounded()` and `onCleared()` cancel the in-flight execution, so a
  result can never land on a screen the user already left.
- Speech flow is unchanged: one recogniser, one TTS engine, no second pipeline.

---

## 7. Permissions and package visibility

**Permissions declared: still only `RECORD_AUDIO`.** Phase 6 adds **no** new
`<uses-permission>`. Volume, ringer mode, media keys, `geo:` hand-offs and
settings screens all work without one.

Do Not Disturb access is a *settings toggle*, not a declared permission:

- ARIA checks it with `NotificationManager.isNotificationPolicyAccessGranted`.
- It never requests, grants or toggles it — Android offers no such API.
- If missing, the handler returns `RequiresPermission(…)` and **changes nothing**.
- The state carries `pendingSettingsAction` for the UI. ARIA does not launch it.
- `setInterruptionFilter()` is **not** used: it needs the same access, and it
  would put the phone in Do Not Disturb, muting alarms nobody asked to mute.

**`<queries>` (four narrow entries — no `QUERY_ALL_PACKAGES`):**

| Entry | Needed for |
|---|---|
| `MAIN` + `CATEGORY_LAUNCHER` | "open <app>" can see launcher apps |
| `VIEW` + `scheme="geo"` | maps search |
| `VIEW` + `scheme="google.navigation"` | turn-by-turn hand-off |
| `MAIN` + `CATEGORY_APP_MUSIC` | "play music" resolves a music app |

The launch path deliberately **does not** pre-check with `resolveActivity()`:
under Android 11+ visibility that returns `null` for apps the launch would
actually reach, producing false "no app installed" answers. ARIA attempts the
launch and catches `ActivityNotFoundException` (and `SecurityException`, which
some OEM builds throw for gated system screens).

---

## 8. Limitations (all of them deliberate, none hidden from the user)

1. **"Play <song>" opens a music app; it does not play the song.** No public API
   starts playback of a named track without a `MediaSession` or a provider
   search, and `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` is deprecated and
   would let ARIA imply it started playback. The query is preserved in the
   command, the history row and the debug block.
2. **Transport keys are dispatched, not confirmed.** No notification-listener or
   `MediaSession` access is used (both invasive), so ARIA cannot know whether a
   player reacted. Messages say "Sent a … request".
3. **Navigation is a hand-off.** `google.navigation:` is a Google-specific scheme;
   a device without a handler gets "No map app is installed". ARIA never claims
   navigation started — the user may cancel, GPS may be off, the app may show a
   preview.
4. **App visibility is partial by design.** Only apps visible under the four
   `<queries>` entries are candidates. An app that hides from launcher queries
   cannot be opened by name; ARIA says "Could not find an app named X" rather than
   guessing or asking for `QUERY_ALL_PACKAGES`.
5. **Settings screens are not guaranteed.** OEMs rename and remove them. A missing
   screen becomes `NotSupported`, never a crash and never a false "opened".
6. **Sound modes can be refused.** `setRingerMode` is a request; a policy, a
   managed profile or an unsupported mode makes it a no-op, which read-back
   catches. A vendor that silently converts SILENT into VIBRATE is reported as a
   failure rather than as success.
7. **Fixed-volume devices** (some TVs, kiosk builds) refuse every volume command
   with a plain explanation.
8. **One second of duplicate suppression** also means a user who says "volume up"
   twice within a second gets one step. That is the specified trade-off; the
   alternative is double-executing a duplicated recogniser callback.
9. **English only**, per Phase 5. No LLM interpretation, no Persian parsing.
10. **No history persistence** (Phase 12). History is in memory, as before.

---

## 9. Test coverage

**132 new test methods**, all JVM-only, no device, no Robolectric. (The Phase 5
suite was 125; the total is now **257**.)

| File | Tests | Covers |
|---|---|---|
| `actions/ExecutionResultTest.kt` | 17 | result semantics, command ids, handler names, settings constants, defensive branches |
| `actions/VolumeActionHandlerTest.kt` | 17 | up/down/set/mute, all three streams, fixed volume, read-back verification |
| `actions/SoundModeActionHandlerTest.kt` | 10 | three modes, DND gate, refusals, "wrong mode" detection |
| `actions/MediaActionHandlerTest.kt` | 11 | four key presses, key pairs, play-music hand-off, no overclaiming |
| `actions/MapsActionHandlerTest.kt` | 11 | three URIs, encoding, missing map app, wording |
| `actions/AppActionHandlerTest.kt` | 15 | exact/starts-with/contains, determinism, miss wording, launch refusal |
| `actions/SettingsActionHandlerTest.kt` | 4 | the four screens, missing screen |
| `actions/AndroidActionExecutorTest.kt` | 11 | every command routed, error boundary, permission pass-through |
| `actions/Fakes.kt` (`VolumeLevelCalculatorTest`) | 13 | rounding, clamping, round-trip, stream mapping, fake contract |
| `ui/AssistantViewModelPhase6Test.kt` | 23 | pipeline, dedupe window, clock, errors, lifecycle, muting, failure-card regression |

`AssistantViewModelTest` (32 tests) was **edited but not extended**: it needed the
two new constructor arguments, and a handful of assertions in it were adjusted
where Phase 6 intentionally changed behaviour — the card now shows a parsed
command instead of `NO_COMMAND_YET`, and each turn speaks an acknowledgement
**and** the result sentence. Its test count is unchanged at 32.

Existing tests that were **not** touched: `AssistantCommandTest` (20),
`RuleBasedCommandParserTest` (60), `AssistantUiStateTest` (4),
`SpeechMessagesTest` (2), `TtsAcknowledgementsTest` (7).

**Totals: 257 unit test methods** (125 before + 132 new), 6 instrumented.

> **Counting correction.** Earlier revisions of this document said 242/114. That
> was wrong: the count used a `*Test.kt` glob, which missed the 13 methods in
> `actions/Fakes.kt` (they live in `VolumeLevelCalculatorTest`, declared in the
> fakes file). The figure above is counted across every file under `app/src/test`

> These numbers are counted from the source, **not** from a test run — see §10.

---

## 10. Build status — READ THIS

⚠️ **The agent that wrote this phase could not compile it.** That environment has
no JDK, no Android SDK and no route to Google's Maven repository (`java`, `javac`,
`kotlinc` and `gradle` are all absent).

**Update — the first real build has since been run by the project owner** (see
`docs/PHASE6-fixes.md` for the four defects it exposed and their fixes).

What was done instead: a manual static review of every new file for unused
imports, unbalanced braces, nullable smart-casts, `suspend` calls in
non-suspending functions, JUnit signature mistakes and Kotlin rounding. Several
real defects were found and fixed this way, including a shared-scheduler mistake
in the new ViewModel test and an expected-value error in a volume test
(7/15 is 47%, not 46%).

Still unverified **at the time this section was written** (all four have since been
run by the owner, several times, with the results in `PROJECT_STATUS.md`):

- `:app:assembleDebug` — **not run (by the agent)**
- `:app:testDebugUnitTest` — **not run (by the agent)**; 257 methods expected
- `:app:assembleDebugAndroidTest` — **not run (by the agent)**
- Any behaviour on a real device — **not run (by the agent)**

> **R2 note:** the last item used to read "never has been, in any phase". That was
> true only of the agent's environment, and reading it as a statement about the
> project is what made an external reviewer conclude that device testing had never
> happened. It has: every merged phase after Phase 1 was verified by the owner on a
> Samsung SM-A566B. `PROJECT_STATUS.md` separates the two records.

Run these before believing any of this:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
```

The first command is the important one: Phase 6 is the first phase whose main
sources import `android.*` in new places, so compile errors are most likely to be
API-signature mistakes rather than logic bugs.

---

## 11. Manual testing on a phone

Run these in order; each row is one acceptance check.

| # | Do | Expect |
|---|---|---|
| 1 | Install, make a call volume audible, say **"volume down"** | Media volume drops one step, the system OSD appears, card says "Media volume down to …%" |
| 2 | Say **"set volume to 30"** | Volume lands near 30%, card says "Media volume is now ~30%" |
| 3 | Say **"mute the music"** while music plays | Media goes silent, **the ringer is unchanged** (call yourself to check) |
| 4 | Say **"volume up"** repeatedly at maximum | "Media volume is at maximum." — never a false step count |
| 5 | Say **"silent mode"** the first time | Card asks for Do Not Disturb access; the phone does **not** go silent; nothing opens by itself |
| 6 | Grant DND access in Settings, say **"silent mode"** again | Ringtone+notification silent; card says "Silent mode is on."; verify by calling the phone |
| 7 | Say **"vibrate mode"**, then **"normal ring mode"** | Vibrate, then ringing again; verify with a call |
| 8 | Say **"play jazz"** with a music app installed | The music app opens; card says "Opening your music app." and shows the query in the debug block |
| 9 | Say **"pause the music"** while a player is playing | Playback pauses; card says "Sent a pause request…" (not "paused") |
| 10 | Say **"next track"**, **"previous track"** | Track changes; wording stays a "request" |
| 11 | Say **"open maps"** | A map app opens |
| 12 | Say **"navigate to Milan"** | The map app opens on navigation to Milan; card says exactly "Opening navigation to Milan in your map app." |
| 13 | Say **"find a cafe"** | The map app opens on a search for cafe |
| 14 | Disable/uninstall every map app, repeat #12 | "No map app is installed"; no crash |
| 15 | Say **"open Chrome"** | Chrome opens; card says "Opening Chrome." |
| 16 | Say **"open Photoshop"** (not installed) | `Could not find an app named "Photoshop".`; nothing opens |
| 16b | Watch that same failure while ARIA speaks, then after it stops | The card stays **FAILED/red** the whole time and afterwards — a spoken failure must never turn green (a real bug found in the first build; see `docs/PHASE6-fixes.md`) |
| 17 | Say **"open wifi settings"**, then **"open battery settings"** | The right screens open (not always the same one) |
| 18 | Say **"make me a sandwich"** | "I didn't understand that command."; the card is **green/COMPLETED**, not red |
| 19 | Say **"volume up"** twice quickly (~½ s apart) | The volume moves **once**; the second utterance adds no history row |
| 20 | Say **"volume up"**, wait 2 s, say it again | It moves twice |
| 21 | Mute ARIA in ⚙, then say **"volume up"** | The volume still changes; nothing is spoken; the reply is still shown as text |
| 22 | Rotate the phone mid-command, background the app mid-command | No crash, no result card popping up after returning |
| 23 | Enable **Debug mode** and repeat any command | `command`, `handler` and `result` show real values (`VOLUME_UP`, `VolumeActionHandler`, `SUCCESS`) |

Version-specific notes to keep in mind while testing: sound-mode changes are
gated by DND access from Android 6 onward (ARIA targets 26+), and app discovery
is limited by the Android 11+ `<queries>` rules listed in §7.
