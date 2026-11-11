# Phase 4 — The `AssistantCommand` model

**Goal:** give ARIA a strongly-typed vocabulary for everything it will ever do.
No parser, no executor, no Android actions — just the model Phase 5 will produce
and Phase 6 will consume.

**Status: written and reviewed; not compiled here.** No JDK or Android SDK was
available in the environment that wrote this phase. Run §5 before trusting it.
Phase 4 is **purely additive**: not one existing file was modified, so the verified
Phase 1-3 behaviour cannot regress.

---

## 1. What was added

```
app/src/main/java/com/example/aria/command/
├── AssistantCommand.kt        the sealed model: 21 commands in 7 categories
└── AudioStream.kt             MEDIA / RING / ALARM — the Android-free stand-in
                               for AudioManager's integer stream constants

app/src/test/java/com/example/aria/command/
└── AssistantCommandTest.kt    20 pure JUnit tests
```

**Zero imports** in `command/` — not even `java.*`. It is plain Kotlin and nothing
else, which is what makes it testable on the JVM and impossible to couple to
Android by accident.

No dependency, Gradle, AGP, Kotlin or manifest change. No existing file touched.

---

## 2. The model at a glance

| Category | Commands | Phase that executes it |
|---|---|---|
| **Media** | `PlayMusic(query)`, `PauseMusic`, `ResumeMusic`, `NextTrack`, `PreviousTrack` | 8 |
| **Volume** | `VolumeUp`, `VolumeDown`, `SetVolume(percentage, stream)`, `MuteMedia` | 7 |
| **Sound modes** | `SilentMode`, `VibrateMode`, `NormalRingMode` | 11 |
| **Maps** | `OpenMaps`, `SearchLocation(query)`, `NavigateTo(destination)` | 9 |
| **Apps** | `OpenApp(appName)` | 10 |
| **Settings** | `OpenSettings`, `OpenWifiSettings`, `OpenBluetoothSettings`, `OpenBatterySettings` | 10 |
| **Fallback** | `Unknown(rawText)` | current pipeline |

Every command carries a `displayName`:

| Command | `displayName` |
|---|---|
| `VolumeUp` | `Volume Up` |
| `SetVolume(30)` | `Set Volume to 30%` |
| `SetVolume(70, RING)` | `Set Ring Volume to 70%` |
| `PlayMusic("jazz")` | `Play "jazz"` |
| `PlayMusic("")` | `Play Music` |
| `NavigateTo("Duomo")` | `Navigate to "Duomo"` |
| `OpenApp("Spotify")` | `Open "Spotify"` |
| `Unknown("…")` | `Unknown Command` |

---

## 3. Design decisions (and why)

### `sealed interface`, not `sealed class`

The members share **no implementation** — no state, no behaviour, no constructor
chain. A sealed *class* would imply a common base that carries something and would
invite subclassing games that buy nothing. An interface states the truth: *these
are the shapes a command can take, they all live in this one file, and the
compiler knows them exhaustively.* Each member is then free to be a `data class`
(parameters) or a `data object` (none).

### `data object` for parameterless commands

`VolumeUp`, `OpenMaps`, etc. are singletons — `data object` guarantees exactly one
instance, so the executor and `when` branches never allocate and identity is
stable. `data class` is used wherever there are parameters, which gives free
`equals`/`hashCode`/`copy`/`toString`.

### `AudioStream` enum instead of `AudioManager` constants

`SetVolume(percentage, stream)` speaks in `AudioStream.MEDIA / RING / ALARM`.
Phase 6 maps each to `AudioManager.STREAM_MUSIC / STREAM_RING / STREAM_ALARM` in
exactly one place. Two reasons: the model stays free of `android.*` (so it is
JVM-testable), and the Android mapping is a decision that belongs beside the code
that performs the action, not in the data.

Only three streams: Version 1 controls what people actually ask for out loud.
Call/notification/system streams stay out until someone needs them.

### `SetVolume` validates at construction

`require(percentage in 0..100)` runs in `init`, so a parser slip fails loudly in a
test instead of quietly setting the wrong volume. The message names the value.

> **Trade-off, stated plainly:** this makes `SetVolume(150)` throw rather than
> clamp. The parser (Phase 5) must clamp or reject before constructing, and there
> is a test pinning the rejection so nobody "fixes" it by accident. If user speech
> can realistically produce an out-of-range value, Phase 5 should clamp — the
> model refuses to guess.

### `MuteMedia` is its own command, not `SetVolume(0)`

They will compile to the same Android call in Phase 6. But "mute the music" and
"set the volume to zero" are different intentions, and the history should record
what the user meant, not a value they never said.

### `Unknown` is a command, not an exception

"I did not understand that" is a normal outcome of talking to a machine, not an
error. Keeping it inside the model means the executor and the history have exactly
one path for it, and `rawText` preserves what was said so the debug view can
explain the miss.

### `displayName` lives on the model

It keeps the wording next to the data it describes (a new command cannot ship
without one) and keeps a second exhaustive `when` out of the UI. The tests assert
all 21 names, including that each is non-blank and starts uppercase.

---

## 4. Why this is not wired into the UI yet

`AssistantUiState` still records `NO_COMMAND_YET` after a transcript. That is
**correct for Phase 4**: this phase defines the vocabulary, it does not use it.
Nothing can produce an `AssistantCommand` until the parser exists (Phase 5), so
wiring one into the state machine now would mean inventing a fake producer — the
same mistake Phase 1's "Simulate a command" button was, and that was deliberately
deleted in Phase 2.

**Phase 5 will** replace `NO_COMMAND_YET` with `command.displayName` (or
`commandType`) and the `CommandRecord.handler` label with the real one. Both are
single-field changes in the ViewModel because the model already exists.

---

## 5. Build & test

```bash
cd ARIA
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Expected: **BUILD SUCCESSFUL** and **65 unit tests, 0 failures**
(45 from Phases 1-3 + 20 new).

The 20 new tests cover:

| Area | Tests |
|---|---|
| `displayName` for every category | 6 |
| Every command non-blank + uppercase, and exactly 21 exist | 1 |
| Data-class equality / inequality / hashCodes / identity | 4 |
| `data object` singletons | 1 |
| `SetVolume` formatting, stream variants, bounds 0 & 100, rejection outside range | 4 |
| `AudioStream` contents and labels | 1 |
| Exhaustive `when` + category coverage | 1 |
| `Unknown` holds raw text; blank `PlayMusic` wording | 2 |

No device needed: none of these touch Android.

---

## 6. Known limitations

- **The model does not validate string parameters.** `OpenApp("")`,
  `NavigateTo("  ")` and `PlayMusic("")` are all constructible. Judging whether
  speech was good enough to make a command is the parser's job (Phase 5), not the
  data's; only `SetVolume` validates because a numeric range is objectively wrong
  at any layer.
- **No `SearchOnYouTube`, `CallContact`, `TakeScreenshot`, timers, etc.** The
  brief's required list is implemented exactly; anything additional is a Phase 5+
  conversation with the spec.
- **`PlayMusic(query)` names no provider.** Which app plays is Phase 8's decision.
- **No serialisation yet.** Phase 12 persists history; the model is deliberately
  not annotated for any format until that phase chooses one.
- **`displayName` is English-only and code-level.** Same convention as
  `SpeechMessages`/`TtsMessages`; the Phase 15 polish pass moves user-facing
  wording into `strings.xml` and would need a `when` at that point.
- **Not compiled here.** §5 is the real gate.

---

## 7. Next phase

**Phase 5 — rule-based parser.** Planned scope: a `CommandParser` interface plus
`RuleBasedCommandParser` that maps normalised text (Italian and English phrasing)
to these commands, with `Unknown` as the safe default; unit tests per command
category; then wire `displayName` into `CommandRecord` so the card and history show
the real command. No execution.

Awaiting confirmation that Phase 4 builds and passes §5.
