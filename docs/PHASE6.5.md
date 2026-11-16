# PHASE 6.5 — Hardening the action layer

Phase 6 shipped and was exercised on a physical Samsung device. An external audit
then listed eleven defects (D1–D11) and three follow-ups (A1–A3) in the
action layer, the parser and the speech/UI seam. **Phase 6.5 fixes exactly those
and adds nothing else.** No new command category, no architecture change, no new
dependency, no Gradle/AGP/Kotlin change.

> ⚠️ **Nothing in Phase 6.5 was compiled or tested by the authoring agent.** That
> sandbox had no JDK, no Android SDK and no route to Google's Maven repository. Every
> "after" statement below is a statement about the *code* as written by the agent.
> The exact commands are in §7, and the owner ran them on hardware: the defects this
> phase was audited for were confirmed gone. See `PROJECT_STATUS.md`.

---

## 1. The one-sentence version

Phase 6 could do the right thing and report it wrongly (a spoken failure turned
the card green, a duplicated command froze the card on "Processing…", a clamped
`-20` muted the phone). Phase 6.5 makes ARIA **refuse and explain** instead of
guessing, and makes the card keep telling the truth after it has been spoken.

---

## 2. Before / after, defect by defect

| # | Before (Phase 6, confirmed in the code) | After (Phase 6.5) |
|---|---|---|
| **D1** | `onFinalResult` reset the card to `PROCESSING` and cleared the card fields **before** the duplicate check; a suppressed duplicate then returned early, so the UI stayed on "Processing…" with no result | Parse and duplicate-check first. The card is reset **only when there is work to do**. A suppressed duplicate **restores** the previous settled card from a snapshot taken at publish time — see §4, because "leave it untouched" is not enough: the recogniser has already overwritten it with LISTENING/PROCESSING for the repeated utterance |
| **D2** | `pendingSettingsAction` was stored and never used: the user was told a permission was missing and had no way to act on it | `StatusCard` shows an **"Open settings"** button when the state carries an action. Tapping it launches `Intent(action)` and catches `ActivityNotFoundException`. On `ON_RESUME` the UI calls `viewModel.onReturnedFromSettings()`, which re-asks the injected `PolicyAccessChecker` and, if granted, **retries the stored command**, then clears the hint. ARIA never opens Settings by itself and never clears the hint merely because a screen was launched |
| **D3** | `looksLikeAppName` required a capital letter, so Samsung's all-lowercase recogniser output turned "open chrome" into `Unknown` | The capitalisation requirement is gone; `AppNameMatcher` (which sees the real installed list) is the authority. Leading "the" and trailing "app" are stripped. `NON_ENTITIES`, `STOP_WORDS`, ordering and "open settings" → `OpenSettings` are preserved |
| **D4** | `\blouder\b` / `\bquieter\b` used `containsMatchIn`, so "the movie was louder than I expected" changed the volume | Those two forms are whole-utterance patterns (plus optional "please"). Bare "louder"/"quieter" still work, and "make it louder" keeps its own anchored pattern |
| **D5** | Transport rules were full-match only, so "play next song" fell through to `PlayMusic("next song")` and opened the music app | `play next/previous/last song|track` are transport patterns. Transport is evaluated **before** `parsePlayMusic`, so "play Shape of You" is still a search |
| **D6** | An explicit but unsupported stream ("notification volume") was ignored and the **media** stream changed — a silent wrong action | A named unsupported stream (`notification`, `call`, `voice`, `system`, `speaker`, …) → `Unknown`, never a redirect. MEDIA is the default only when **no** stream is named. RING/ALARM still work |
| **D7** | (a) every utterance used `QUEUE_FLUSH`, so the result sentence cut the acknowledgement off; (b) no utterance ids, so a stale `SpeakFinished` settled a newer card | (a) the result is spoken with `QUEUE_ADD`, the acknowledgement still uses `QUEUE_FLUSH` so barge-in (new utterance stops prior speech) is preserved; (b) `SpeakStarted/SpeakFinished/SpeakFailed` carry `utteranceId` (`Ready` does **not** carry one); `speak()` returns `String?`; the ViewModel ignores events for an utterance it is not awaiting |
| **D8** | A `SecurityException` from a DND-protected `STREAM_RING` adjustment became a generic failure ("Something went wrong") | Caught in `VolumeActionHandler` and mapped to `RequiresPermission(ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)` with an explanatory sentence. **No blanket DND pre-check** — devices with DND off keep working, and media is unaffected |
| **D9** | One immediate read-back after a change. Devices that apply volume asynchronously reported the old level → a real success was reported as a failure | Read-back retried up to 3 attempts, ~50 ms apart (both injected). The first matching read wins. **Honesty limit kept:** a device that genuinely refuses (EU safe-volume dialog, policy, fixed volume) still fails, because retrying cannot make a refusal into a success |
| **D10** | No compound detection: "set the volume to 30 and turn off wifi" executed the volume change and silently dropped the rest while reporting success | Tail-analysis, **never a split**: after the last conjunction, if the tail parses as a command *or* opens with a command verb-anchor plus meaningful content, the whole utterance becomes `Rejected(COMPOUND_SENTENCE)` → neutral card, "I can only handle one command at a time." Entities stay intact: "Rock and Roll", "Bath and Bristol", "Berlin, Germany" |
| **D11** | Out-of-range volumes were **clamped**: `-20` became 0 (a silent mute), 120 became 100 | No clamping. Anything outside 0..100 (including negatives and >4 digits) → `Rejected(VOLUME_OUT_OF_RANGE)`, nothing is executed, card says "Volume must be between 0 and 100 percent." The model's `require` stays as a backstop; there is no `clampedFrom` field |
| **A1** | `Failure` carried only a sentence | `Failure(message, reason: FailureReason = UNKNOWN)`; AppNotFound, LaunchFailed, PermissionDenied, NotSupported, InvalidParameter and Timeout are annotated at their sites. **No behaviour change** — the enum is metadata |
| **A2** | *Verified only.* `AndroidMediaKeyController.dispatchMediaKeyEvent` already sends `ACTION_DOWN` then `ACTION_UP` in a loop over `keyCode`/`eventTime` | **No change made** — the audit item was accurate about the risk but the code was already correct |
| **A3** | No way to undo a media mute (media keys cannot raise a volume on every OEM) | `UnmuteMedia` (display name "Unmute Media", id `UNMUTE_MEDIA`), parser forms "unmute" / "unmute the media", handler direction `ADJUST_UNMUTE` with the same read-back. "unmute the phone" → `NormalRingMode` (the object of the verb decides, mirroring "mute the phone"). "turn the sound back on" is deliberately left `Unknown` |

---

## 3. The two sentences that were chosen with care

* `"I can only handle one command at a time."` — states ARIA's limit, not the
  user's mistake. It is also the honest reason nothing was done.
* `"Volume must be between 0 and 100 percent."` — says the range, which is the
  only information that lets the user retry successfully.

Both live in `actions/ActionMessages.kt` and are reached through
`actions/RejectionMessages.kt` (`RejectionReason` → sentence), so the parser can
stay free of user-facing prose while the wording stays in one place.

---

## 4. Decisions worth remembering

**Why `Rejected` is a command *and* a result.** The parser must be able to say "I
understood you and will not do it" without executing anything, and the executor
must be able to report that without inventing a failure. `AssistantCommand.Rejected`
travels through the normal executor path (routed to the fallback handler) and comes
back as `ExecutionResult.Rejected`, which maps to the new neutral
`AssistantStatus.REJECTED` — neither green nor red.

**Why D2 splits the work between UI and ViewModel.** Launching Settings needs an
`Activity`; noticing that the user came back needs a lifecycle. Both belong to the
UI. Deciding whether the permission is now granted must be JVM-testable, so it sits
behind the existing `PolicyAccessChecker` port, injected into the ViewModel.
`AssistantViewModel` still imports no Android UI type.

**Why a suppressed duplicate RESTORES rather than does nothing (D1, second
round).** The first version simply returned early, on the theory that "the card is
still the old one". It is not: the recogniser has already driven it through
`LISTENING` and `PROCESSING` for the repeated utterance, and `startListening()`
cleared the visible fields. The device run showed `a suppressed duplicate leaves
the card settled` failing for exactly that reason. The ViewModel now snapshots the
card at publish time (`settledState`) and restores its visible fields when a
duplicate is suppressed, so the user is looking at the first result again. The
snapshot is dropped by `clearHistory()` so a cleared card cannot come back.

**Why the retry stores the raw text too.** The stored command is retried as the
command it was (a re-parse could, in principle, produce something different); the
raw text is kept so the retried card still shows what the user said.

**Why `SpeakFinished` keeps a nullable id.** `stop()` emits an id-less
`SpeakFinished` — that event means "audio stopped", not "utterance N finished", and
dropping it would leave a card stuck on `SPEAKING`. A null id is therefore accepted
as "about the current audio"; a *different* id is ignored.

---

## 5. Limitations, stated out loud

1. **`ADJUST_UNMUTE` on an already-unmuted stream does nothing.** If the index is
   already > 0, the platform's unmute is a no-op. The handler reports
   "Media is already at N%." — it never claims the level rose.
2. **Index 0 vs 1.** ARIA cannot know which index counts as "audible" on every
   OEM, so `unmute` accepts any non-zero index as success. A device whose streams
   start at index 1 will therefore report success for a stream that was already
   audible.
3. **D9 retries asynchronous apply, not refusal.** The EU safe-volume dialog, a
   device-policy restriction, or genuinely fixed volume still fail — deliberately.
4. **D10 is a heuristic, not a grammar.** A second request phrased without a
   conjunction ("open chrome play music") is not detected. Tail-analysis was chosen
   over splitting precisely because splitting destroys entities.
5. **"turn the sound back on" stays `Unknown`.** It is genuinely ambiguous between
   media and ringer; guessing would change the wrong stream.
6. **D2 cannot know *which* permission was refused.** It re-asks the one port it
   has (Do Not Disturb access). A different permission hint would be re-checked by
   the same call and cleared only if that call answers true.

---

## 6. Files touched

**Model / result vocabulary**
`command/AssistantCommand.kt` (`UnmuteMedia`, `Rejected`, `RejectionReason`),
`actions/ExecutionResult.kt` (`Failure.reason`, `FailureReason`, `Rejected`),
`actions/ActionMessages.kt`, `actions/RejectionMessages.kt` (new).

**Parser** — `command/RuleBasedCommandParser.kt` only (no rule-chain reordering
beyond the transport-before-play note in D5).

**Action layer** — `actions/VolumeActionHandler.kt` (rewritten read-back, D8, A3),
`actions/AudioVolumeController.kt` + `actions/AndroidAudioVolumeController.kt`
(`ADJUST_UNMUTE`), `actions/UnknownActionHandler.kt`, `actions/AndroidActionExecutor.kt`,
`actions/HandlerNames.kt`, `actions/AppActionHandler.kt`,
`actions/MediaActionHandler.kt`, `actions/MapsActionHandler.kt` (A1 annotations).

**Voice** — `voice/TextToSpeechManager.kt` (ids, `SpeakPriority`, `speak(): String?`).

**UI** — `ui/AssistantViewModel.kt` (D1, D2 retry, D7 id matching),
`ui/AssistantUiState.kt` (`REJECTED`, `pendingCommand`, `pendingRawSpeech`),
`ui/AssistantScreen.kt` (button launch, `ON_RESUME`),
`ui/components/StatusCard.kt`, `ui/components/HistoryList.kt`,
`ui/ViewModelFactory.kt`, `AriaApplication.kt` (policy port),
`res/values/strings.xml` ("Open settings").

**Tests** — `command/ParserHardeningTest.kt` (new),
`actions/VolumeHardeningTest.kt` (new), plus updates to
`AssistantViewModelPhase6Test`, `AssistantViewModelTest`, `AssistantCommandTest`,
`RuleBasedCommandParserTest`, `ExecutionResultTest`, `AndroidActionExecutorTest`,
`FakeTextToSpeechManager`, `FakeActionExecutor`, `actions/Fakes.kt`.

---

## 7. How to verify (not executed in the sandbox)

```bash
cd /home/user/ARIA
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Nothing in this phase was run: there is no JDK, no Android SDK and no route to
Google's Maven repository in the sandbox. **The first real compile is expected to
be the moment any remaining mechanical error surfaces** (an exhaustive `when`, a
renamed symbol); the logic itself is covered by the unit tests listed above.

### Phone checks (physical device)

1. Say "volume up" twice quickly → the volume rises **once** and the card shows a
   finished result, never a stuck "Processing…" (D1).
2. Say "silent mode" with Do Not Disturb access denied → the card offers **Open
   settings**; grant it, come back → the command runs by itself and the button
   disappears (D2).
3. Say "open chrome" in lower case → Chrome opens (D3).
4. Say "the movie was louder than I expected" → nothing happens (D4).
5. Say "play next song" with a player running → the track skips instead of a music
   app opening (D5).
6. Say "set the notification volume to 50" → nothing changes; the card explains
   (D6).
7. Start a command, then immediately say another → ARIA stops mid-sentence, the
   result of the new command is read **after** its acknowledgement (D7).
8. Deny DND access and say "set ring volume to 40" → the card asks for the
   permission instead of "Something went wrong" (D8).
9. On a slow device, "volume up" → success, not a false "did not change" (D9).
10. Say "set the volume to 30 and turn off wifi" → neutral card, "I can only handle
    one command at a time." (D10).
11. Say "volume 120" → neutral card, "Volume must be between 0 and 100 percent."
    (D11).
12. Say "unmute" after muting the media → the level comes back (A3).

---

## 8. Phase 7 not started

Phase 6.5 stopped here. No work on Phase 7 (or any later phase) was begun.
