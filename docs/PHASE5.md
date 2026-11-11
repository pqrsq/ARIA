# Phase 5 — Rule-based command parser

**Goal:** turn recognised English text into an existing `AssistantCommand`, with
no Android, no side effects, and no execution.

**Status: written and reviewed; not compiled here.** No JDK or Android SDK was
available in this environment (no `java`, no `ANDROID_HOME`, no route to Google's
Maven repo). §9 is the real gate.

**Scope protection honoured:** `AssistantViewModel`, `AssistantUiState`, every UI
file, every Gradle file and the Phase 4 model are **unchanged**. Verified in §10.

---

## 1. Files

### New (all pure Kotlin — zero imports, not even `java.*`)

```
app/src/main/java/com/example/aria/command/
├── CommandParser.kt            the interface: fun parse(rawText: String): AssistantCommand
├── RuleBasedCommandParser.kt   ordered rule stages, entity extraction, clamps
└── TextNormalizer.kt           three views of one utterance + entity slicing

app/src/test/java/com/example/aria/command/
└── RuleBasedCommandParserTest.kt   60 pure JUnit tests
```

### Unchanged

`AssistantCommand.kt` and `AudioStream.kt` are byte-identical to Phase 4. The
parser consumes `SetVolume(percentage, stream)` exactly as it was designed,
including its `require(percentage in 0..100)` guard.

---

## 2. Pipeline

```
raw text
   │
   ├─ TextNormalizer.normalize ──► original | entitySource | normalized
   │
   ├─ stage 1..10 (first non-null wins)
   │
   └─ AssistantCommand  (Unknown(rawText) at the end)
```

### The three views (the subtle part)

| View | Example (`"Please play Shape of You"`) | Used for |
|---|---|---|
| `original` | `Please play Shape of You` | `Unknown(rawText)` |
| `entitySource` | `play Shape of You` | slicing the entity |
| `normalized` | `play shape of you` | every rule match |

Filler/wake words are stripped from **both** `original` (to make
`entitySource`) and therefore `normalized`, in parallel, before any matching.
That keeps their word counts aligned — without it, a one-word prefix match on the
normalised text would slice the wrong word out of the original and `"please play
music"` would come back as `PlayMusic("play music")`. This bug was real; it was
found by dry-running the brief's examples before writing the tests.

---

## 3. Rule order (and why)

| # | Stage | Why it sits here |
|---|---|---|
| 1 | Sound mode with an explicit **phone** target | "mute the phone" shares the word *mute* with media muting |
| 2 | **Specific** settings (Wi-Fi / Bluetooth / battery) | must beat generic settings *and* app launching |
| 3 | Maps open | "open maps" must never become `OpenApp("maps")` |
| 4 | Navigation | before search, so "navigate to Starbucks" is not a search |
| 5 | Location search | after navigation, so "find the airport" stays a search |
| 6 | App launching | after maps/settings so those are never app names |
| 7 | Generic settings | after the specific ones |
| 8 | Sound modes (generic) | ring-restoring rules run **before** the bare "silent mode" pattern |
| 9 | Volume → mute → music transport → play | level and transport before the broad "play X" rule |
| 10 | `Unknown` | the fallback |

Priority alone is never used to disambiguate sloppy patterns: every rule also
demands a strict shape (anchor + required tail), and word boundaries are enforced
by regex `\b`.

Two ordering bugs this discipline caught during design:

1. A bare `silent mode` pattern sat in stage 1, which runs **before** the
   ring-restoring rules, so `"turn off silent mode"` became `SilentMode` instead
   of `NormalRingMode`.
2. `wi-fi` is hyphenated; the settings regex only allowed `wifi`, so
   `"open Wi-Fi settings"` fell through to app launching.

---

## 4. Entity extraction

`TextNormalizer.entityAfterPrefix(text, prefix)` drops as many words as the
prefix contains from `entitySource` and trims trailing command punctuation. The
user's capitalisation and internal spacing survive untouched:

| Input | Entity |
|---|---|
| `Please play Shape of You` | `Shape of You` |
| `Play the song Blinding Lights` | `Blinding Lights` |
| `open app YouTube` | `YouTube` |
| `Hey Aria, open YouTube.` | `YouTube` |
| `Navigate to Berlin Central Station` | `Berlin Central Station` |
| `Please take me to university` | `university` |

Play queries additionally lose leading *how-you-ask* noise — `the song`, `some`,
`a` — so "play some music" → `PlayMusic("music")` per the brief. `the` alone is
never stripped, so "play the Beatles" keeps its article.

App names must *look like names*: an internal capital (`YouTube`), or a
multi-word name of non-filler words, or a capitalised single word that is not an
everyday English word. That is what makes `"open something"` → `Unknown` while
`"open YouTube"` → `OpenApp("YouTube")`.

---

## 5. Volume and stream handling

- **Digits only.** `"volume thirty"` is `Unknown` — a `WORD_NUMBER` guard rejects
  the whole phrase rather than silently matching something else.
- **Clamped before construction:** `coerceIn(0, 100)`, so `150` → `100` and
  `-10` → `0`. The Phase 4 model's `require` is the safety net, never the tool.
- **Stream:** `ring`/`ringer` → `AudioStream.RING`, `alarm` → `ALARM`, else
  `MEDIA`. No new stream type, no bypass of the parameter.

| Input | Output |
|---|---|
| `volume 30` | `SetVolume(30, MEDIA)` |
| `volume 150` | `SetVolume(100, MEDIA)` |
| `volume -10` | `SetVolume(0, MEDIA)` |
| `set ring volume to 40` | `SetVolume(40, RING)` |
| `set alarm volume to 20` | `SetVolume(20, ALARM)` |
| `volume thirty` / `volume` / `volume abc` | `Unknown` |

---

## 6. Conservative matching

Precision over recall. `Unknown` for: unrelated sentences, bare verbs (`play`,
`navigate`, `search`, `open`, `volume`), missing entities (`navigate to`),
filler-only entities (`find it`), word numbers, and any phrase whose entity does
not look like a name.

`Unknown(rawText)` echoes the **original** input, case and punctuation intact.
Blank and whitespace-only input return exactly `Unknown("")`, and input that is
only politeness (`"please"`, `"Hey Aria,"`) also returns `Unknown("")` because
nothing is left to match.

---

## 7. Language scope

**English only.** Persian command phrases, Persian application names and Persian
voice commands are explicitly out of Phase 5. Normalisation keeps two Persian
punctuation marks (`؟` `،`) out of entities so a future phase starts from a clean
base, and nothing else assumes a Latin script.

---

## 8. Testing

`RuleBasedCommandParserTest` — **60 tests**, grouped as the brief asks:

| Group | Covers |
|---|---|
| A. Positive | every command family with 3+ phrasings |
| B. Negative | unrelated text, bare verbs, blank input, missing entities, filler-only entities, `"go to"` |
| C. Entity preservation | capitalisation, `the song` stripping, wake-word + entity, internal spacing |
| D. Numeric / stream | 0, 30, 100, clamping, negatives, malformed, word numbers, all three streams |
| E. Normalisation | uppercase, whitespace, punctuation, politeness, wake words, repeated fillers, un-lowercased entity |
| F. Priority / conflict | maps vs app, phone vs media mute, specific vs generic settings, navigate vs search |
| G. Purity / pipeline | determinism over 20 runs, statelessness across inputs, raw-text echo, full pipeline |

Every asserted input was dry-run against a faithful mirror of the rule chain
before the tests were written; the 156 case/expectation pairs reproduce.

---

## 9. Build and verification

Run these — **not run by the agent** (no JDK/SDK in this environment):

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest          # expect 125 tests: 65 + 60 new
./gradlew :app:assembleDebugAndroidTest
```

### Static checks actually performed here

| Check | Result |
|---|---|
| `android.*` imports in `command/` | none |
| imports of any kind in `command/` | none |
| `ASSISTANTVIEWMODEL` / `AssistantUiState` / UI files modified | none (`git status` shows only new files) |
| helpers declared once (no duplicate/renamed references) | `startsWithWord`, `toMatchable`, `normalize`, `stripLeadingFillers`, `entityAfterPrefix`, `stripLeadingPhrase` — 1 each |
| braces / parens balanced in every `.kt` | ok |
| regex escaping valid for Kotlin string literals | single-escaped throughout |
| Phase 4 model untouched | yes, no edits |

### Honest status

- **Build: NOT RUN** (no toolchain here).
- **Unit tests: NOT RUN** (same reason). 125 are written.
- **Instrumented APK: NOT RUN** (same reason).

---

## 10. Scope confirmations

- **No Android action was implemented.** No volume, music, maps, app, sound-mode
  or settings code exists; the parser only *names* intents.
- **No `ActionExecutor`.** Phase 6 territory.
- **The parser is not connected to anything.** `AssistantViewModel`,
  `AssistantUiState`, `AssistantScreen`, the factory and the Application are
  untouched; no dependency was added; no Gradle/AGP/Kotlin version changed.
- **The UI still records `NO_COMMAND_YET`.** That is correct and expected: this
  phase delivers the parser, Phase 6 delivers the wiring.
- **Phase 6 has NOT been started.**

---

## 11. Known limitations

- **English only**, and pattern-based: a phrasing outside the rule set becomes
  `Unknown` by design rather than being guessed.
- **Digits only** for volume; word numbers are a later phase.
- **`go to X` is not navigation.** The brief lists it as unsupported, so it stays
  `Unknown` even when a destination follows.
- **`"play some music"` → `PlayMusic("music")`** because the brief specifies that
  exact output; the model cannot distinguish "play anything" from "play the
  track called *music*" until Phase 8 resolves queries.
- **App-name detection leans on capitalisation.** Speech recognisers capitalise
  brand names; a device that lowercases everything would need Phase 10's
  installed-app list to disambiguate.
- **No Italian or Persian phrasing**, no fuzzy matching, no ranking.
- **Hand-reviewed, not compiled.** §9.
