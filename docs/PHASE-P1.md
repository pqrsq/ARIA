# PHASE P1 — Parser coverage & precision

Phase 6.5 is built and device-verified. P1 closes the **last** parser coverage and
precision gaps from the original audit. Scope was deliberately narrow: only
`RuleBasedCommandParser.kt`, `TextNormalizer.kt` and their tests. No new commands,
no handler/ViewModel/UI/TTS/manifest/Gradle/dependency change, and **no
restructuring of the rule chain**.

Guiding principle unchanged: **reliability > features, precision over recall.**

> ⚠️ **Not executed.** The sandbox has no JDK, no Android SDK and no route to
> Google's Maven repository, so nothing here has been compiled or run. The exact
> commands are in §6. Verification of the expected outcomes was done by reading the
> code and by replaying every listed utterance against a model of the rule chain
> (77/77 expectations matched) — that is not the same as a green build.

---

## 1. Before / after, item by item

| # | Before (confirmed in the code) | After |
|---|---|---|
| **P1.1** | `"turn up the volume"`, `"up the volume"`, `"turn down the volume"`, `"down the volume"` → `Unknown` (`"volume up"`/`"volume down"` **already worked** — see §3) | Both word orders are commands. The new patterns are **ANCHORED** (`containsMatchIn`) and live in `VOLUME_UP_PATTERNS`/`VOLUME_DOWN_PATTERNS`, never in the BARE lists, so D4 is untouched |
| **P1.2** | Only leading fillers were stripped, so `"pause the music please"` → `Unknown` | `TextNormalizer.stripTrailingFillers` removes `please` / `thanks` / `thank you` from the **end of the matching view**. Entities are not touched by it (that would rename `"play Please Please Please"`); a comma-anchored `", please"` is stripped inside `entityAfterPrefix`, and app names are stripped explicitly in the parser |
| **P1.3** | `"turn on silent mode"`, `"mute my phone"`, `"silence my phone"`, bare `"vibrate"` → `Unknown` (`"turn my phone silent"` **already worked** — §3) | Stage-1 silent patterns accept `the` **or** `my`; `ELABORATE_SILENT_PATTERNS` gains `turn/switch on silent mode`; bare `vibrate` is a whole-utterance `VibrateMode` |
| **P1.4** | `"find my phone"` searched the map for the literal words *my phone* | A query starting with `my` is refused (`Unknown`). Navigation is a different intent and is unaffected |
| **P1.5** | bare `"skip"` → `Unknown` (`"skip track"`, `"skip song"`, `"go back"` **already worked** — §3) | `Regex("skip")` added to `NEXT_PATTERNS`. Matching stays full-utterance, so `"I want to skip"` is still `Unknown` |

---

## 2. The thing that mattered most: entities vs. the matching view

The naive version of P1.2 — strip trailing `please` everywhere — breaks the invariant
the brief singled out:

```
"play Please Please Please"  →  PlayMusic("Please Please Please")   ✅ required
```

So the two views are treated differently on purpose:

- **`normalized`** (the string every rule matches against) — trailing politeness
  removed. Safe: no rule needs a trailing `please` to make sense.
- **`entitySource`** (the string entities are cut from) — trailing politeness
  **kept**, except when the speaker supplied a comma: `"play Hello, please"` →
  `PlayMusic("Hello")`.
- **app names** — stripped without a comma, because the launcher list is a closed
  vocabulary where `please` is never part of a real name. The courtesy strip runs
  **before** the `app` strip, otherwise `"open the YouTube app please"` becomes
  `OpenApp("YouTube app")`.

**Documented limitation:** `"play hello please"` (no comma) keeps `please` in the
title. An odd query is better than silently renaming a song.

---

## 3. Items that were already satisfied (no change made)

The brief asked to confirm each gap before fixing it. Three sub-items were already
correct, and they have regression tests instead of fixes:

| Claimed gap | Reality | Now covered by |
|---|---|---|
| `"volume up"` / `"volume down"` → Unknown | Already `VolumeUp`/`VolumeDown` — `\bvolume\s+up\b` / `\bvolume\s+down\b` have been in the lists since Phase 5 | `volume up and volume down are commands` |
| `"turn my phone silent"` → Unknown | Already matched `\b(turn\|switch)\s+(the\s+\|my\s+)?phone\s+(to\s+\|on\s+)?silent\b` | `turn my phone silent` |
| `"go back"` → Unknown | `Regex("go\\s+back")` has been in `PREVIOUS_PATTERNS` since Phase 5 | `go back was already a command - guard only` |

`"skip track"` / `"skip song"` were **also** already matched (by
`(skip|play)\s+(this\s+|the\s+)?(song|track)`); only the one-word `skip` was
missing.

---

## 4. Invariants preserved (each has a test)

- **D4** — a bare comparative inside a sentence is still `Unknown`:
  `"the movie was louder than I expected"`. P1.1's additions are anchored
  (`up the volume` needs the noun; nothing new matches a loose `up`/`down`).
- **D6** — an unsupported stream is still refused: `"set the notification volume
  to 50"` → `Unknown`.
- **D10** — tail-analysis, never split: `"set the volume to 30 percent and turn
  off wifi"` → `Rejected(COMPOUND_SENTENCE)`, with or without a trailing `please`.
- **D11** — no clamping: `"volume 120 please"` → `Rejected(VOLUME_OUT_OF_RANGE)`.
- **Blank input** — `""`, `"   "`, `"please"`, `"thanks"` all still yield
  `Unknown("")`; the trailing strip collapses a politeness-only utterance to `""`.
- **Ordering** — `NORMAL_RING` before the new silent patterns, so
  `"turn off silent mode"` → `NormalRingMode`; `"mute the media"` → `MuteMedia`
  (stage 1 requires `the`/`my` + `phone`/`device`/`ringer`); `"unmute the phone"`
  → `NormalRingMode`; the sound-mode stage still refuses any sentence containing
  `volume`.

---

## 5. Files and tests

**Changed**
- `app/src/main/java/com/example/aria/command/RuleBasedCommandParser.kt`
- `app/src/main/java/com/example/aria/command/TextNormalizer.kt`
  (`stripTrailingFillers`, comma-anchored courtesy strip; `PUNCTUATION` and
  `WHITESPACE` are now `internal` so the top-level helper can use them)

**Added**
- `app/src/test/java/com/example/aria/command/ParserCoverageTest.kt` — 32 tests,
  one or more per item plus the regression guards above. No existing test pinned
  any of the old behaviour, so nothing had to be inverted.

Unit test count: **313 → 345**.

---

## 6. How to verify (not executed here)

```bash
cd ARIA
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Nothing in this phase was run in the sandbox. Expect 345/345 unit tests.

### Manual device script (debug mode ON; check the RAW transcript as well, STT can mishear)

| Say | Expect |
|---|---|
| "turn up the volume" | `VolumeUp` |
| "volume up" | `VolumeUp` |
| "pause the music please" | `PauseMusic` |
| "turn on silent mode" | `SilentMode` |
| "mute my phone" | `SilentMode` |
| "vibrate" | `VibrateMode` |
| "find my phone" | honest `Unknown` (no map search) |
| "skip" | `NextTrack` |
| "go back" | `PreviousTrack` |
| "open chrome please" | opens Chrome |
| **Regression:** "the movie was louder than I expected" | `Unknown` |
| **Regression:** "set the volume to 120 percent" | `Rejected` |
| **Regression:** "play next song" | `NextTrack` |
| **Regression:** "set the volume to 30 percent and turn off wifi" | `Rejected` |

---

## 7. Deferred (not done, by instruction)

Word numbers ("thirty percent"); the `"50 percent volume"` word order; `"normal
mode"`; special-casing `"find my location"`; Persian; stripping politeness from
song/destination entities without a comma; any new command, category, or
architecture change (Registry, `NeedsClarification`, `AssistantPlan`).

**Phase 7 / V1-completion / LLM work was not started.** P1 stops here.

---

## 8. Post-merge build follow-up: the `endsWithWord` resolution bug

Two builds were needed before this phase compiled and passed.

**Build 1 — `Unresolved reference 'endsWithWord'` at `TextNormalizer.kt:150`.**
`stripTrailingFillers` called a word-boundary helper that existed only inside
`RuleBasedCommandParser`'s **private companion object**. A companion member is
class-scoped, so another file cannot see it, whatever the import. Reusing it there
was simply wrong.

**Build 2 — 119 tests failed, everything returning `Unknown("")`.**
The fix applied to build 1 deleted the unresolved call instead of defining it. That
call was the *condition* of the strip:

```kotlin
if (!matchable.endsWithWholeWord(filler)) continue   // keep this
result = words.dropLast(fillerWordCount) ...
```

Without it the loop drops the last word of every phrase of two or more words, then
repeats until nothing is left: `"hello there"` → `"hello"` → `""`. Every utterance
became blank, so every test that asserts on a command failed — 119 of them, spread
across the whole suite. Deleting the call made the compiler happy and the parser
silently dead.

**The fix (this follow-up)** is to *add* the missing helper at package level, next to
`startsWithWord`, and keep the call. It is deliberately named
`endsWithWholeWord`, **not** `endsWithWord`: the parser still has its own
same-shaped companion helper for the `"… app"` suffix, and two identically-shaped
extensions on `String` would leave call-site resolution to scope rules that this
environment cannot compile-test.

`ParserCoverageTest` now pins the failure directly:

- `regression - the trailing strip never eats an ordinary last word` asserts the
  normalised view is **unchanged** for `volume up`, `pause the music`, `hello there`
  and a long sentence, and that those still resolve to commands.
- `regression - only a real trailing courtesy phrase is dropped` asserts the strip
  stays conditional in both directions — `please pause the music` and
  `pleased to meet you` are untouched, while `please` and `thanks` collapse to `""`.

Unit test count: **345 → 347**. Still not executed in the authoring sandbox.
