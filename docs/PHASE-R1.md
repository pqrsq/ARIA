# PHASE R1 — correctness before V1

An external audit reported three concurrency/durability defects in the history and
TTS paths. This round **verified each against the actual source first**, quoted the
offending code, and fixed only what was real. Nothing else changed: no features, no
dependencies, no parser/executor/UI edits.

> ⚠️ **Not executed.** No JDK and no Android SDK in the authoring sandbox
> (`JAVA_HOME` and `ANDROID_HOME` are both empty; `java` is not installed). **No
> unit or instrumented test was run here.** §6 has the exact commands the owner must
> run; the tests below were written to be deterministic by construction, not by
> timing, but only a real run proves them.

---

## 1. Verdicts

| Item | Claim | Verdict | Action |
|---|---|---|---|
| **C1** | `persistHistory()` reads the snapshot before the launch, so a command run during the startup load writes `[new]` over the stored rows | **CONFIRMED** | Fixed |
| **C1 (KDoc)** | The comment claims the mutex makes the merge safe "whichever one wins" | **CONFIRMED — factually wrong** | Rewritten |
| **C1b** | A `clearHistory()` during a suspended load is undone when the load merges its stale rows back | **CONFIRMED** | Fixed |
| **C2a** | `SpeakFailed` computes `isOurs` and then ignores it | **CONFIRMED** | Fixed |
| **C2b** | `isCurrentUtterance` admits any event when `awaitingUtteranceId == null` | **CONFIRMED AS CODE, NOT A DEFECT** | **Deliberately not changed** — §4 |
| **C3** | `save()` falls back to `target.delete()` + rename, so a kill between them leaves no file | **CONFIRMED** | Fixed in the implementation |

No item was NOT-CONFIRMED. One item was confirmed as written but rejected as a
defect, with the code that disproves the harm in §4.

---

## 2. C1 — the startup race

### What the code said

```kotlin
private fun persistHistory() {
    val snapshot = _uiState.value.history      // ← read OUTSIDE the mutex, at request time
    viewModelScope.launch {
        guardHistory { historyStore.save(snapshot) }
    }
}
```

and, above it:

> WHY IT DOES NOT AWAIT THE LOAD: it takes the same mutex, so if the load is still
> running the save simply queues behind it, and because the load PREPENDS the stored
> rows while this writes the resulting list, the merge is preserved whichever one
> wins.

That comment was wrong, and it is why the defect survived. The load is its **own
coroutine**, launched from `init`. A mutex cannot order work against a coroutine that
has not asked for it yet: if the save acquires the lock first — the normal case when
the user commands ARIA immediately after a cold start — it writes the state's list as
it is *at that moment*, which is `[new]` only. The load then merges the stored rows
into the **in-memory** list, so the screen looks right while the file has been
truncated. The next launch starts from one record.

### What changed

```kotlin
private fun persistHistory() {
    val generation = historyGeneration.get()
    viewModelScope.launch {
        historyLoadJob?.join()                        // 1. wait for the load
        if (historyGeneration.get() != generation) return@launch
        guardHistory {
            historyStore.save(_uiState.value.history) // 2. snapshot INSIDE the lock
        }
    }
}
```

Three separate requirements, each for a different reason:

1. **Join the load before writing.** Nothing else establishes the order.
2. **Read the snapshot inside the mutex.** A snapshot captured at request time can
   predate a later save: with two commands queued behind a gated load, the first
   save holds `[up]` — which lacks both the second command's row and the restored
   rows — and writes it **last**. Reading under the lock means every write describes
   the state at the moment it is written.
3. **Join *outside* `guardHistory`, not inside it.** `guardHistory` is the only thing
   that takes `historyMutex`, and the load needs that same mutex to apply its result.
   Joining while holding it would wait for a job that is waiting for the lock. Join
   first, lock second.

The caller is still not blocked: the join happens inside the launched coroutine, so
`publish()` returns to the UI immediately and a slow disk never delays the card or the
spoken reply. Invariants are untouched — a suppressed duplicate returns before
`publish()` and therefore never reaches `persistHistory()` (D1); `HistoryStore.save`
still applies `capHistory` (the 200 cap); the list stays newest-first because nothing
reorders it.

---

## 3. C1b — `clear()` during an in-flight load

### What the code said

```kotlin
val stored = guardHistory { historyStore.load() } ?: emptyList()
if (stored.isEmpty()) return@launch
nextId.updateAndGet { current -> maxOf(current, stored.maxOf { it.id } + 1) }
_uiState.update { state -> state.copy(history = capHistory(state.history + stored)) }
```

`stored` is read from the file, then the coroutine suspends (or is simply descheduled)
before the `_uiState.update`. A `clearHistory()` landing in that window empties the
state and the store — and then the load prepends rows that existed *before* the clear.
The user deletes their history and watches it come back. Confirmed.

The pre-existing mitigation (`clearHistory()` joins `historyLoadJob` and then empties
the state again) handles the *state* but not the *store*: the join cannot stop the
load from having already completed its merge before the clear's own `clear()` call
runs, and it does nothing about a save that is queued.

### What changed — a generation counter

```kotlin
private val historyGeneration = AtomicInteger(0)   // bumped by every clearHistory()
```

The load captures the counter **before it is launched** and drops its whole result if
the counter moved:

```kotlin
if (historyGeneration.get() != loadGeneration) return@launch
```

`persistHistory()` uses the same counter, captured at request time, so a save queued
before a clear cannot write the cleared rows back.

**Why a counter and not `job.cancel()`** — the simplest *correct* mechanism, which is
what the brief asked for. A load suspended inside `historyStore.load()` has **already
read** the rows it is about to merge; cancelling it is a race (it may already be past
the suspension point), and a cancel does nothing to a save that was queued earlier.
The counter is evaluated at the instant the write is about to happen, so "was there a
clear since this request?" has a definite answer. It also covers both halves — the
load merge *and* an in-flight save — with one mechanism, and it needs no new job
handles, no `NonCancellable`, and no restructuring of `clearHistory`'s existing join.

---

## 4. C2a / C2b — late TTS events

### C2a — CONFIRMED and fixed

```kotlin
is TtsEvent.SpeakFailed -> {
    val isOurs = isCurrentUtterance(event.utteranceId)
    if (isOurs) awaitingUtteranceId = null
    _uiState.update { state ->
        state.copy(                                   // ← unconditional
            status = ...,
            ttsMessage = TtsMessages.forError(TtsError.SPEAK_FAILED)
        )
    }
}
```

`SpeakFinished` guards with `isOurs`; `SpeakFailed` computes it and then updates
regardless. A failure carrying a **superseded utterance id** therefore repaints the
current card — "could not speak" over a reply that is speaking perfectly well. Fixed
by mirroring `SpeakFinished`: `if (!isOurs) state else state.copy(...)`. The genuine
failure (our id, or no id) still reaches the user.

### C2b — code as described, **not** a defect: left alone

```kotlin
private fun isCurrentUtterance(utteranceId: String?): Boolean =
    utteranceId == null || awaitingUtteranceId == null || utteranceId == awaitingUtteranceId
```

The permissiveness is load-bearing, and the evidence is in the adapter:

```kotlin
// TextToSpeechManager.kt:216 — stop() emits an id-less finish ON PURPOSE
if (ready) mutableEvents.tryEmit(TtsEvent.SpeakFinished())
```

Id-less events are emitted by `stop()` — i.e. by **barge-in** (`runCommand` stops the
current utterance and clears both ids before speaking the new reply) and by
`onAppBackgrounded()`. They are the mechanism that settles a deliberately stopped card.
Tightening matching to `id != null && id == awaiting` would require replacing that
behaviour with a synchronous reset at every `stop()` call site — a change to barge-in,
which this round forbids. The existing `a speak failure event does not erase the
result` test also emits an id-less `SpeakFailed` while an id is awaited.

Decision: **not adopted.** The real defect was the id-bearing stale event, and C2a
fixes it without touching this contract. **T5 pins the behaviour** so a future
"tightening" fails loudly instead of silently breaking barge-in.

---

## 5. C3 — `save()` could leave no file at all

### What the code said

```kotlin
if (!temporary.renameTo(target)) {
    target.delete()                 // ← the history file is GONE
    if (!temporary.renameTo(target)) {
        Log.w(TAG, "Could not replace $FILE_NAME; history not saved.")
        temporary.delete()
    }
}
```

The KDoc above it promised "either the old complete file or the new complete file —
never a half-written one". The code could deliver a third outcome: **no file**. And
because `File.renameTo` does not overwrite an existing target on Android, the
`delete()` branch was the *normal* path for every save after the first, not an exotic
fallback — every save opened a window in which the user's entire history did not exist
on disk. A kill there (crash, swipe-away, cleanup) is permanent, total loss.

### What changed

```kotlin
Files.move(tmp.toPath(), target.toPath(),
           StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
```

with a retry on `AtomicMoveNotSupportedException` using `REPLACE_EXISTING` only, and a
tmp cleanup when the move reports failure. **The target is never deleted before the new
content is in place.** `java.nio.file` is available on every supported version —
`minSdk = 26` — so this costs no dependency and no desugaring.

The honest guarantee is now stated in the KDoc: an interrupted atomic move leaves the
previous file intact; the non-atomic fallback overwrites in place (weaker if a crash
interleaves) but still never destroys the old rows.

---

## 6. Tests

| Test | Where | Discriminates? |
|---|---|---|
| **T1** `a command run while the initial load is still running keeps the stored rows` | `AssistantViewModelHistoryTest` | **Yes** — asserts `store.stored` (not the UI state, which recovered in memory even when the file was clobbered). Pre-fix: `[D]`. Post-fix: `[D, C, B, A]`. |
| **T2** `two commands queued behind the load both survive to the file` | `AssistantViewModelHistoryTest` | **Yes** — pre-fix the request-time snapshot `[up]` is written last, wiping the other command and the restored row. Post-fix: `[down, up, C]`. |
| **T3** `a clear during the initial load is not undone on disk` | `AssistantViewModelHistoryTest` | **Yes** — pre-fix the load resurrects the row in both state and store. |
| **T4** `a stale failure event does not repaint the current card` | `AssistantViewModelPhase6Test` | **Yes** — pre-fix `ttsMessage` becomes the failure warning. Also asserts the whole card is inert, and that our own failure still lands. |
| **T5** `barge-in still settles the previous utterance whose id was cleared` | `AssistantViewModelPhase6Test` | Regression guard for C2b — fails if anyone "tightens" `isCurrentUtterance`. |
| **T6** `aFailedReplace_doesNotLoseThePreviousHistoryFile` (+ `saving_overAnExistingFile_keepsTheFileAndItsNewContent`) | `HistoryStoreInstrumentedTest` | **Yes, on a device.** The target is replaced by a non-empty directory holding a marker file; a delete-first implementation loses the marker. |

Determinism comes from `FakeHistoryStore.loadDeferred`, a `CompletableDeferred` gate
plus a `loadStarted` flag, so "the load has started and has not returned" is a fact
rather than a sleep. T1 and T2 also assert that **no save lands while the load owns
the lock**, which is what makes them reproduce the race instead of hoping for it.

A note on what was *not* written: an earlier draft of the C1b save-half test could not
distinguish a stale save from a correct one under this fake's scheduling (the clear
always reaches the mutex before the queued save), so it was removed rather than kept
as a test that passes for the wrong reason. `persistHistory`'s generation check is
justified by the code path, and T1/T2 cover the ordering that *is* observable.

### Commands (owner — not run here)

```powershell
.\gradlew :app:testDebugUnitTest --rerun-tasks
.\gradlew :app:connectedDebugAndroidTest
```

Five JVM tests were added (T1–T5), so the arithmetic from the Phase 12 figure of 385 is
**390** — but that is arithmetic over the source, not a run. *Do not treat it as a
result.*

---

## 7. Device re-check (owner)

Debug text box ON, after installing:

1. Cold start → **immediately** type a command (`volume up`) → force-stop → reopen →
   the older history **and** the new command are both present. *(C1.)*
2. Clear history → force-stop → reopen → still empty. *(C1b.)*
3. Rotate mid-session → history intact.
4. TTS unmuted → speak a command, then immediately another (barge-in) → the card is
   not repainted by the previous utterance's late callback. *(C2a/C2b.)*

---

## 8. One finding left open, deliberately

A command that completes **while the initial load is in flight** is given an id from
`1`, and the load then continues numbering from `max(stored) + 1` — so the new record
can share an id with a restored row. This is not caused by, and is not changed by,
this round. It is only reachable by commanding ARIA before the first read returns, and
`HistoryList` renders with `forEach` (no Compose `key()`), so it is a list-identity
smell, not a crash and not a duplicate row. Fixing it would mean rewriting history rows
inside a coroutine that a `clear()` may already have invalidated, which is a worse
trade than the smell. Recorded here so the audit can decide; not silently "fixed".
