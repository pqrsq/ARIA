# SPEECH-LIFECYCLE.md — Phase 1A diagnostics (`ARIA_STT`)

Observation only. **This document describes how to read the trace; it does not describe
a fix.** No recognition behaviour was changed in Phase 1A.

---

## The problem being investigated

On a real **Samsung SM-A566B**, recognition is intermittently incomplete:

| Spoken | Sometimes received |
|---|---|
| "play face off" | "Face off" |
| "volume up" | "Up", or "Volume" |
| "volume down" | "Noun" |

Google's own voice input recognises the same speech correctly, and waiting a moment
after tapping the microphone makes ARIA noticeably more reliable. That points at the
interval between `startListening()` and the platform actually opening the microphone —
**but it is a hypothesis, not a finding.** These logs exist to settle it.

---

## How to capture a trace

```powershell
adb logcat -c                                  # clear first
adb logcat -s ARIA_STT                         # macOS/Linux: ./adb
```

Then in ARIA: tap the microphone and say **"play face off"** immediately, in one go.
Repeat ~10 times, including a few where you wait a second before speaking. Compare the
`+Nms` between `START_LISTENING` and `BEGINNING_OF_SPEECH` in each.

Save a trace to a file:

```powershell
adb logcat -s ARIA_STT > aria-stt.txt
```

---

## What each line means

```
ARIA_STT | session=12 | +0ms    | START_LISTENING
ARIA_STT | session=12 | +384ms  | READY_FOR_SPEECH
ARIA_STT | session=12 | +702ms  | BEGINNING_OF_SPEECH
ARIA_STT | session=12 | +840ms  | PARTIAL | "play"
ARIA_STT | session=12 | +1012ms | PARTIAL | "play face"
ARIA_STT | session=12 | +1198ms | PARTIAL | "play face off"
ARIA_STT | session=12 | +1680ms | END_OF_SPEECH
ARIA_STT | session=12 | +1815ms | FINAL | "face off"
```

| Event | Meaning |
|---|---|
| `START_LISTENING` | `startListening()` was called on the platform. `+0ms` is this moment. |
| `READY_FOR_SPEECH` | The service says the microphone is open. |
| `BEGINNING_OF_SPEECH` | The service says it heard voice begin. |
| `PARTIAL` | One partial transcript, **exactly as received** — never merged, never the longest-wins. |
| `END_OF_SPEECH` | The service says speech ended. |
| `FINAL` | The transcript handed to the parser. `"" (blank)` means the service returned nothing. |
| `ERROR` | `code=<Android int> \| mapped=<SpeechError>`. Mapped by the same function the app uses, so the log cannot disagree with the user-facing message. |
| `STOP_REQUESTED` | ARIA asked for the final transcript (microphone button pressed again). |
| `CANCEL_REQUESTED` | ARIA abandoned the session (app backgrounded). |
| `RELEASED` | The manager released the platform recogniser. |
| `START_FAILED` | `startListening()` threw `IllegalStateException` ("recognition is busy"). |

`session=N` increments once per real `startListening()`. Comparing sessions is the point:
if the failing sessions show a short gap before `BEGINNING_OF_SPEECH` and no `PARTIAL`
containing "play", the opening word never reached the service.

### Reading a `CANCEL_REQUESTED` pair

`SpeechRecognizer.cancel()` **always** delivers `ERROR_CLIENT` (code 5) afterwards. That
is why you will see:

```
ARIA_STT | session=15 | +99ms  | CANCEL_REQUESTED
ARIA_STT | session=15 | +103ms | ERROR | code=5 | mapped=SERVICE_UNAVAILABLE | swallowed (ARIA cancelled this session)
```

`swallowed` means ARIA treated it as expected and showed the user nothing. It is not a
failure to investigate.

---

## Phase 1B — two extra lines (experiments (a) and (b))

The Phase 1A trace proved the loss happens **before** ARIA sees it (the FINAL was never
worse than the best PARTIAL, and ARIA never cancelled a session). It could not say
*where* upstream. Two lines settle that.

### `ALTERNATIVES` — experiment (a): did the service hear the whole phrase?

```
ARIA_STT | session=7 | ALTERNATIVES | n=1 | #0 "play face off" conf=n/a
ARIA_STT | session=7 | ALTERNATIVES | n=3 | #0 "play" conf=0.81 ; #1 "play face" conf=0.62 ; #2 "play face off" conf=0.44
```

ARIA asks for `EXTRA_MAX_RESULTS = 1` and reads index 0 only. This line prints **every**
hypothesis the service returned, plus its confidence.

| What you see | What it means |
|---|---|
| `n=1` and `#0` is the full phrase | nothing was lost — you spoke a different thing, or this attempt was fine |
| `n>1`, a later hypothesis is the FULL phrase | the words **were** decoded; only ranking lost them → `EXTRA_MAX_RESULTS` is a real lever |
| `n=1`…`n>1` but **every** hypothesis is truncated | the words were never decoded → acoustic / endpointing, not scoring |
| `n=0` | the service produced nothing at all (the NO_MATCH case) |
| `conf=n/a` | this service supplies no scores. A *different* finding from a low score |

Confidences are read from `SpeechRecognizer.CONFIDENCE_SCORES` (`"confidence_scores"`) —
**not** `RecognizerIntent.EXTRA_CONFIDENCE_SCORES` (`"android.speech.extra.CONFIDENCE_SCORES"`).
Those are two different strings: the `EXTRA_` one is an *input* key a recogniser reads, so
looking it up in the results bundle returns null forever and every session would print
`conf=n/a` whether or not scores existed. Getting this backwards would not crash — it
would quietly fabricate the "no scores" finding.

### `ENDPOINT_CONFIG` — experiment (b): what did the endpointer think it was doing?

```
ARIA_STT | session=7 | ENDPOINT_CONFIG | at=RESULTS | complete_silence=800ms | possibly_complete_silence=1500ms | minimum_length=2000ms | segmented_session=false | prefer_offline=true
```

Printed **twice** per session — `at=END_OF_SPEECH` and `at=RESULTS` — because services
disagree about where they attach these extras. `n/a` means the service did not supply
that key at all, which is itself information.

The two sessions to compare are the ones that reported only **170 ms** and **245 ms**
between `BEGINNING_OF_SPEECH` and `END_OF_SPEECH`:

- Short `complete_silence` / `minimum_length` → the endpointer is closing utterances
  around a fragment. Remedy is a configuration value.
- Normal values, or all `n/a` → endpointing is not the explanation, and the acoustics
  are the place to look.

## Phase 1C — scalar audio levels (experiment (c))

Added after a physical test split the failure by microphone: a Bluetooth headset
recognised **5/5**, the phone's own microphone **1/5**, with TTS off.

Phase 1B could not distinguish two very different causes, because a truncated
utterance and an utterance that was never captured look identical in transcript
callbacks. These numbers do distinguish them.

### `RMS` — one line per session

```
ARIA_STT | session=7 | RMS | at=FINAL | n=50 | min=-2.2 | max=12.0 | mean=4.7 |
  begin#=1 end#=1 | pre(n=20 mean=-1.9 max=-1.5) |
  atBegin(before=-1.8 recentMax=-1.5 after=8.0) |
  during(n=27 mean=9.9 max=12.0) | RISE=11.8 |
  atEnd(before=9.0 recentMax=12.0) | afterEnd(n=2 mean=-1.5 max=-1.0)
```

All values are `rmsdB` scalars. **No sample, buffer or audio is stored** — a level
cannot be turned back into speech.

| Field | Meaning |
|---|---|
| `n` | number of `onRmsChanged` callbacks this session |
| `pre(n= mean= max=)` | the level **before** `BEGINNING_OF_SPEECH` — the noise floor |
| `atBegin(before= recentMax= after=)` | last value before BEGIN, max of the ~8 recent values, first value after BEGIN |
| `during(n= mean= max=)` | level **between** BEGIN and END |
| `RISE` | `during.mean − pre.mean`. **Known-unreliable — see below.** |
| `atEnd(before= recentMax=)` | level just before `END_OF_SPEECH` |
| `afterEnd(...)` | tail after END, usually the room going quiet |

Emitted once, at `at=FINAL` or `at=ERROR` — never per callback, because a log line
per callback would flood Logcat and change the timing of the session being measured.

### `INPUT_ROUTE` — read twice per session (Phase 1D)

```
ARIA_STT | session=7 | INPUT_ROUTE | at=PRE_START | capturing=[src=123 dev=BUILTIN_MIC/Phone Microphone silenced=false] |
  mode=0 | sco_on=false | speakerphone_on=false | music_active=false | comm_device=BUILTIN_EARPIECE | available=[...]
```

Printed at **`PRE_START`** (immediately before `startListening()`) and again at
**`READY_FOR_SPEECH`**, because a capture may not be registered at the instant
`startListening()` returns.

| Field | Meaning |
|---|---|
| **`capturing=`** | **the answer.** Each active capture in the system, its real input device, and whether the system is feeding it silence |
| `mode` | `0` = `MODE_NORMAL` — the state in which Android prefers the built-in mic |
| `sco_on` / `speakerphone_on` | legacy **output**-side flags, kept as context only |
| `music_active` | whether some other client is playing audio |
| `comm_device` | **output** device for communication. Not relevant to which mic is used |
| `available` | input devices that *exist*. A paired headset appears here even when it is not capturing, so this is never proof |

#### Correction: the Phase 1C version of this line was wrong

Phase 1C reported `getCommunicationDevice()` as "the closest observable answer to
which microphone is being used". **That was wrong.** Its own javadoc says it
replaces `isBluetoothScoOn()`/`isSpeakerphoneOn()` — both **output**-side — so it can
never name an input. In the 1C trace it read `TYPE_1`
(`TYPE_BUILTIN_EARPIECE`, an output type) in **all 15 sessions, including the
Bluetooth ones**, which is why the Bluetooth microphone could not be confirmed.

`getActiveRecordingConfigurations()` is the correct source: it lists every active
capture and each one's `getAudioDevice()` is the input actually in use.

#### `RISE` did not work, and why

`RISE` was designed as the headline number. **It is not usable**, and the reason is
worth recording so nobody rebuilds it:

`pre` was assumed to be a clean noise floor. It is not. The recogniser fires
`BEGINNING_OF_SPEECH` *after* the user has already started talking, so `pre` is
polluted with the beginning of the utterance itself. In the 1C trace `pre` contains
values of `10.0`. The result is that `RISE` is ≈ 0 or negative **even for sessions
that succeeded**, so it cannot separate success from failure.

Use instead: `n` (callbacks), `during.mean`, `during.max`, and the gap between the
last PARTIAL and `END_OF_SPEECH`. Those did separate the groups in the 1C trace.

`rmsdB` itself is an **unbounded dB value** — the platform documents only "the new
RMS dB value". The `10.0` ceiling seen everywhere is the service's own practical
clamp, not an API limit, so treat "10.0" as "at the top of this service's range"
rather than an absolute level.

### Decision table

| `RISE` | `atBegin(after=)` | Reading | Lever |
|---|---|---|---|
| **large** (≳ 6 dB) | clearly above floor | audio **arrived**; loss is a boundary/decoding decision | endpoint extras (needs approval — it is an intent change) |
| **≈ 0** | ≈ floor | the recogniser **never received usable audio** | route/gain/mic — no endpoint setting can fix it |
| large, then collapse | high then floor | speech started, signal died mid-utterance | route instability / gain |
| `n=0` | `n/a` | the service reports **no levels at all** | (c) is inconclusive; compare only across sessions |

Compare the same table across the three groups: phone-mic successes, phone-mic
failures, headset successes.

## Phase 1F — per-session `SpeechRecognizer`: TESTED AND REVERTED

This phase was the only one in this branch that changed behaviour rather than
observation, and it has been undone. It is kept in the record because a negative
result is what closes a hypothesis.

**Changed:** every `startListening()` created a fresh `SpeechRecognizer`, destroyed
after each terminal callback, instead of creating once and reusing forever.
Nothing else: same intent, same extras, same audio configuration.

**Result:**

| group | exact phrase | any result |
|---|---|---|
| A — Bluetooth off | **2/5** | 3/5 |
| C — Bluetooth on, device paired elsewhere | **5/5** | 5/5 |

Every session logged `recognizer=CREATED | per_session=true`, so the change was
definitely in effect, and the gap between A and C survived it unchanged.

**Conclusion: reuse is not the mechanism.** A stale recogniser bound to an older
audio state does not explain the failure. Reverted.

Two honest caveats about the numbers:

- **A went 1/5 → 2/5.** With n=5 that is one attempt, inside noise; A failed the
  same way in both builds (mostly a truncated decode, twice NO_MATCH). Not
  evidence that per-session creation helped.
- **`capturing=NONE` is not evidence of no capture.** In 4 of the 5 group-C
  sessions the capture list was empty and all succeeded. Without
  `MODIFY_AUDIO_ROUTING` that API reports nothing reliably at those instants, so
  `capturing=[]` only ever means "not visible to this app right now".

## What the whole investigation has now ruled out

| Candidate | Verdict | Evidence |
|---|---|---|
| TTS / ARIA's own audio | **excluded** | TTS off did not help; ARIA plays no tones, takes no audio focus |
| ARIA's lifecycle (stop/cancel) | **excluded** | zero STOP/CANCEL/RELEASED in every trace |
| Result selection / ranking | **excluded** | `ALTERNATIVES n=1` every time; FINAL never worse than the best PARTIAL |
| Start readiness / adding a delay | **excluded** | START→READY 47–216 ms; no correlation with success |
| Endpointing as the primary cause | **unlikely** | BEGIN→END is *longer* when failing, not shorter |
| Reuse of `SpeechRecognizer` | **excluded** (Phase 1F) | per-session creation changed nothing |
| Microphone hardware or dirt | **excluded** | the same mic succeeds 5/5 in group C |
| Bluetooth *microphone* as the input | **excluded** | capture stays `BUILTIN_MIC`; headset was in another room |
| **A paired Bluetooth device being present** | **only surviving correlate** | A 1–2/5, B 1/5, C 5/5 |

A paired device is present in C and absent in A and B, and every observable audio
field (`mode`, `sco_on`, `speakerphone_on`, `comm_device`) is identical across all
groups. The effect is real but invisible to every public API checked here.

## Why the trace only appears in a debuggable build

Transcripts are the most private data this app produces, and ARIA's privacy contract says
it persists nothing it hears. Logging a user's words unconditionally on a shipped build
would quietly break that.

So the instrumentation is compiled in but **emits only when the running APK is
`debuggable`** — `installDebug` and every on-device test build show the full trace, a
release build logs nothing at all. This is a logging gate; it cannot change what is
recognised. It is also why `currentSession` stays `0` in a release build: no bookkeeping
happens there at all.

No audio is recorded, saved or transmitted. No analytics, no telemetry. The only sink is
Logcat.

---

## What Phase 1A deliberately did not do

- No parser, command-pattern, `MediaActionHandler`, language, intent-extra or
  offline-preference change.
- No artificial delays — a delay is a fix candidate, and it must not be applied before
  the evidence is in.
- No `onBeginningOfSpeech` added to the public `SpeechEvent` vocabulary: it is diagnostic
  information the UI has no use for, and adding it would be a contract change for a
  logging phase.
- No merging of partials, no longest-partial substitution, no altering of the final text.

---

## What to send back (Phase 1B)

Same as before, plus for each session:

1. the `ALTERNATIVES` count `n=` and whether any hypothesis is the full phrase;
2. the `ENDPOINT_CONFIG` values — even if they are all `n/a`, that is the answer;
3. which sessions were failures, so the three can be lined up.

## What to send back

A `aria-stt.txt` containing the 10–20 sessions, plus which ones produced a wrong
transcript. The two numbers that matter for each:

1. `READY_FOR_SPEECH` and `BEGINNING_OF_SPEECH` elapsed times.
2. Whether any `PARTIAL` before `FINAL` contained the missing word.
