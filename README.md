# ARIA

A personal Android voice assistant. Speak a command, ARIA parses it, performs a real
Android action, and says what actually happened. **Native Kotlin + Jetpack Compose.**

> **Status:** V1.0. **Read [`PROJECT_STATUS.md`](PROJECT_STATUS.md)** — it is the single
> source of truth for what has been verified, by whom, and when.
> The architecture and decision record is [`docs/PROJECT_GUIDE.md`](docs/PROJECT_GUIDE.md).

```powershell
.\gradlew :app:testDebugUnitTest      # 390 unit tests, no device needed
.\gradlew :app:assembleDebug          # APK → app/build/outputs/apk/debug/
```

## What works

- **Speech in** — the phone's own `SpeechRecognizer`; no audio stored, none sent.
- **Parsed** — one ordered chain of small rule stages, precision over recall, always
  falls back to `Unknown` rather than guessing.
- **Acted** — volume, sound modes, media transport, maps/navigation, launching apps,
  opening settings.
- **Answers out loud** — with the value read *back* from the device, never the value
  requested. A spoken failure never looks green.
- **History** — survives a restart, capped at 200, and excluded from cloud backup and
  device transfer, because transcripts are the most private thing the app produces.
- **Debug text input** — a second input into the *same* pipeline, so the parser can be
  exercised without the microphone. Hidden unless debug mode is on.

## Documentation map

| File | What it is |
|---|---|
| `PROJECT_STATUS.md` | **Authoritative status.** Verified on hardware vs. agent limits, counts, dates, what is next, what is deferred |
| `docs/PROJECT_GUIDE.md` | Architecture, seams, why each decision was made |
| `docs/PHASE*.md` | Per-phase record, including corrections and what each phase could not verify |
| `docs/PHASE-R1.md` | The correctness pass: four concurrency/durability defects, quoted before and after |
| `docs/TROUBLESHOOTING.md` | Read this first if a build fails |
| `FILE_MANIFEST.txt` | Every tracked file with a checksum |

## A note on the record

Most of this project was written in a sandbox with **no JDK and no Android SDK**, so
the authoring agent compiled nothing. Every phase was then built, tested and verified
**by the owner on a physical Samsung device**. Both statements are true, and keeping
them separate is the point: a "not executed" note in a phase document describes the
agent's environment, never the project's state. `PROJECT_STATUS.md` holds both records,
side by side, by name.

## Deliberately not built

Better/offline speech recognition, Persian, a capability registry, a risk/confirmation
model, conversation memory, dedupe by interaction id. Each was a decision, not an
omission — see `PROJECT_STATUS.md` §3.

## Privacy

On-device recognition by default, no audio retained, no LLM, no network calls of its
own, and history excluded from backup and device transfer.
