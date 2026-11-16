# PHASE 12 — history persistence (V1 completion, step 1 of 4)

History was in-memory only and died with the process. This phase makes it survive a
restart, and nothing else.

> ⚠️ **Not executed *in the authoring sandbox*.** No JDK and no Android SDK there, so
> the agent could not compile or run anything. §6 has the commands.
>
> **This is not the same as "unverified".** The owner ran this on real hardware:
> the unit suite and the instrumented suite passed, and the history device script in
> §7 was walked through. See `PROJECT_STATUS.md` for exactly what was verified by
> whom — that file, not this one, is the authority on status. The wording below is
> deliberately about what the *agent* could not do.
>
> **R1 later corrected this phase's two race descriptions and its atomic-write
> claim.** Both were wrong as written; §3, §4 and §9 of `docs/PHASE-R1.md` hold the
> corrected account. Corrections are marked in place below rather than deleted, so
> the record shows what the code actually did and why the claim was wrong.

---

## 1. Decision, restated

Plain JSON in app-private storage, behind a pure `suspend` interface. **No Room, no
DataStore, no new dependency, no KSP.** The history is one flat, capped, always
read-and-written-whole list of ~200 small records; Room's annotation processing
would be disproportionate. Room stays the V2 answer if history ever needs queries,
filters or migrations.

---

## 2. Step 0 — what the code said

| Checked | Finding | Consequence |
|---|---|---|
| `CommandRecord` fields | `id, timestampMillis, rawSpeech, normalizedText, commandType, handler, status, message, source` | **`timestampMillis` already exists** (absolute epoch millis) — so no field had to be added, and ordering across restarts is free. `displayName`/UI format untouched. |
| Where rows are appended | `publish()` (`history = listOf(record) + it.history`) **and** two failure paths via `withFailureRow` | All three must persist, or a restart silently loses failure rows |
| Clear action | `clearHistory()` — state only | Needs the store cleared too |
| Dedupe path | `isDuplicate` → early `return@launch` **before** `publish()` | D1 is preserved for free: nothing reaches the persist call |
| `HistoryList.kt` | renders `records` in list order, newest first | The store must return newest-first; no UI change needed |
| `ViewModelFactory` / `AriaApplication` | plain manual wiring, no DI framework | Add one lazy store on the Application, pass it through both factories |
| Both backup XMLs | only `sharedpref` excluded | `history.json` in `filesDir` would have been **backed up to the cloud** — the phase's privacy requirement was a real gap, not a formality |

No assumption in the brief was wrong. One thing the brief implied but the code
already had: the absolute timestamp.

---

## 3. What was built

### `history/HistoryStore.kt` — the pure contract

```kotlin
interface HistoryStore {
    suspend fun load(): List<CommandRecord>
    suspend fun save(records: List<CommandRecord>)
    suspend fun clear()
}
```

Plus the two pure pieces that make the cap testable without a device:

- `const val MAX_HISTORY_RECORDS = 200`
- `fun capHistory(records, max = MAX_HISTORY_RECORDS)` — keeps the newest `max`,
  order-preserving, **idempotent** (both the ViewModel and the adapter apply it),
  and `require(max > 0)` so a cap of zero can never silently erase the history.

The interface documents that `load` never throws and that `save` receives the
**complete** list — there is no append/merge API for a caller to get wrong.

### `history/FileHistoryStore.kt` — the only Android code

- JSON array via `org.json`, written to `filesDir/history.json`.
- **Atomic write**: `history.json.tmp` then renamed over `history.json`, so a
  process death mid-save leaves the old complete file or the new complete file,
  never a half-written one. A failed rename deletes the temp file so a later load
  cannot pick it up.
  > **CORRECTED BY R1.** This paragraph used to claim that a failed rename still
  > left a complete file. It did not. `File.renameTo` does not overwrite on Android,
  > so the old `if (!renameTo) { target.delete(); renameTo(...) }` fallback was the
  > **normal** path for every save after the first, and it left a window in which no
  > history file existed at all — a kill there lost everything. R1 replaced it with
  > `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` (retrying without `ATOMIC_MOVE`),
  > and the target is now never deleted before the new content is in place. The
  > guarantee above is true *of the current code*; it was not true of this phase's
  > code. See `docs/PHASE-R1.md` §5.
- **Never throws**: missing file, empty file, malformed JSON, wrong root type, I/O
  error → empty list (and a `Log.w` so the loss can be explained). One undecodable
  row is dropped without discarding the other 199.
- Unknown enum names fall back to the field defaults instead of throwing, so a file
  from a newer build cannot crash an older one.
- `load` sorts newest-first by `timestampMillis`, so the UI never depends on the
  writer having been right.
- `clear` deletes the temp file too, so an interrupted save cannot resurrect rows.

**Why `org.json` is contained here**: it is an Android stub in JVM unit tests, so
anything touching it cannot run on the JVM. That is precisely why the store is an
interface: the JVM suite tests a fake, and this adapter is tested on a device.

### ViewModel wiring

- **The store is a required constructor parameter with no default.** A default could
  only be a no-op, which would make "history is persisted" quietly false in
  production while every test still passed against a fake.
- `init` launches the load and prepends the stored rows to whatever is in memory;
  `nextId` is advanced past the restored ids so ids never collide.
- `persistHistory()` is called after `publish()` and from both failure paths, and
  saves **the state's own list** (capped) rather than `record + old list` — one
  source of truth, one place applying the cap.
- Every store call goes through `guardHistory {}`, which takes the `Mutex` and
  swallows any `Exception` (rethrowing `CancellationException`). Storage is a
  convenience: a read-only volume costs the user their history, never their command.

### Privacy

`history.json` is excluded from `<full-backup-content>` (both domains' rules file)
and from **both** `<cloud-backup>` and `<device-transfer>` in
`data_extraction_rules.xml`. Transcripts are the most private thing the app
produces; auto-backup must not upload them without the user asking.

---

## 4. The two races, and why they needed explicit handling

**(a) An append during the initial load.** The load is asynchronous, so a command
typed the instant the app opens can finish before it returns — and would then write a
one-row list over the stored history. The mitigation introduced here was one `Mutex`
around every store access.
  > **CORRECTED BY R1 — THE MUTEX ALONE DID NOT FIX THIS.** A mutex serialises
  > *accesses*; it cannot order a save against a coroutine that has not asked for the
  > lock yet. When the save won the race — the ordinary case for a command typed
  > immediately after a cold start — it wrote the state's list without the stored
  > rows, and the load's merge happened only in memory. The screen looked right while
  > the file had been truncated. The real fix is that `persistHistory()` now **joins**
  > the load job before taking the lock. See `docs/PHASE-R1.md` §2.

**(b) Clear while the load is in flight.** Joining the load job is *not* enough: the
load's own final step prepends the stored rows back into the state, so a clear that
ran during the load would be undone moments later — rows gone from the file, back on
the screen. `clearHistory()` therefore joins the load and then empties the state
**again**, making the outcome independent of the race. This was found by replaying the
state machine, not by reading the code (§5).

---

## 5. Verification performed here (no compiler)

The load/save/clear state machine was replayed against a fake store, including both
races. Every assertion below reproduced:

| Behaviour | Result |
|---|---|
| init loads stored rows, order preserved | ✅ |
| `load` called exactly once on init | ✅ |
| one command → one row in state **and** one save, persisted == state row | ✅ |
| duplicate-suppressed repeat → no new row, **no new save at all** | ✅ |
| 201st record → 200 in state and store, newest on top, oldest dropped | ✅ |
| load failure → empty history, no crash, next command still works | ✅ |
| save failure → the command result is unaffected | ✅ |
| clear → state and store both empty | ✅ |
| **clear before the load finishes → nothing resurrected** | ✅ after the fix in §4b |

That replay caught one real defect (4b) that reading the code had missed.

---

## 6. Build and tests

```bash
./gradlew :app:compileDebugKotlin :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

**Not executed in the sandbox.** The figures below were the expectation at the time;
the suite has since grown (**390** JVM and **30** instrumented after R1's T1-T6), so
use `PROJECT_STATUS.md` for current counts, not this paragraph.

### JVM unit tests added

`AssistantViewModelHistoryTest` (15) — every bullet in the brief's test list:
init load and order · one row per completed command · D1 persists nothing ·
the cap in state and store · load failure · save failure · clear · the race in
§4b · a typed command keeps `TEXT` · `Rejected` persists with its displayed
status · `Unknown` persists · a speech failure row persists.

`HistoryCapTest` (8) — the pure cap: shorter than the cap (same instance), exactly
the cap, one over, far over, empty, idempotent, custom cap, and a non-positive cap
rejected.

`FakeHistoryStore` — records `loadCount`/`clearCount`/`saves`, can fail on load or
save, and has a `loadGate` so a test can suspend the load and prove the race.

### Instrumented tests added

`HistoryStoreInstrumentedTest` (14) — the adapter, which cannot run on the JVM:
missing file · JSON round-trip including enums and the `source` marker · newest-first
ordering · corrupt file · truncated file · empty file · one undecodable row not
discarding the rest · unknown enum names falling back · cap truncation · replace
rather than append · clear idempotent · **no leftover `.tmp`** · the file name pinned
to `history.json` (so the backup exclusion cannot silently stop matching) · nested
directory creation.

### What the tests do not prove

- **Process death** in the literal sense: the instrumentation process stays alive.
  The mechanism is proven (a second `FileHistoryStore` over the same directory reads
  back what the first wrote, with nothing shared in memory), and the manual script
  below is the real check.
- **Rotation**: no state is held in the composable or the Activity, so rotation only
  exercises `ViewModel` retention — which the script checks rather than a test
  pretending to.

---

## 7. Device test script (typed input, no microphone)

1. Enable debug mode; type **`turn up the volume`**, **`find my phone`**,
   **`volume 120 please`**. Three rows appear: `VOLUME_UP · TEXT` (OK),
   `UNKNOWN · TEXT` (OK), `VOLUME_120… · TEXT` (**N/A**, the rejection).
2. **Force-stop** (App info → Force stop — swiping from recents may not kill the
   process on One UI), then reopen. All three rows are still there, in the same
   order, with the same status and the same `· TEXT` marker.
3. **Rotate** the device: history intact.
4. Clear history → **force-stop** → reopen: still empty.
5. Optional privacy check: `adb shell run-as com.example.aria ls files/` shows
   `history.json`; `adb shell dumpsys backup` (or a backup to another device)
   must not include it.
6. The 200 cap is covered by unit tests — no need to type 201 commands.

---

## 8. Scope confirmation

**New**: `history/HistoryStore.kt`, `history/FileHistoryStore.kt`.
**Touched**: `AssistantViewModel.kt`, `ViewModelFactory.kt`, `AriaApplication.kt`,
`backup_rules.xml`, `data_extraction_rules.xml`, three existing test files (each
constructs a ViewModel, so each now passes a fake store).
**Untouched**: parser, normalizer, handlers, `ExecutionResult`, TTS, UI layout,
command/history semantics, Gradle/AGP/Kotlin, permissions. No new dependency.

**Regression, polish and release work have not been started.** This is step 1 of 4;
the phase stops here.

---

## 9. Build fix: the cap fixtures were in the wrong order

Four cap tests failed. The code was right; the **fixtures were ascending** while the
app's list is **newest-first**.

`capHistory` keeps the first `max` elements, and `publish()` builds
`listOf(newRecord) + oldHistory`, so index 0 is the newest. `FileHistoryStore.load`
also sorts newest-first. My fixtures did `(1L..n).map(::record)` — ascending — and then
read `first()`/`last()` as newest/oldest. That is backwards: with ascending ids the
cap drops the *newest* and keeps the oldest, so assertions like
`capped.last().id == 201` could never hold.

Fixed by giving the tests the same shape the app produces:

```kotlin
/** index 0 is the NEWEST, like publish(): listOf(new) + old */
private fun newest(count: Int): List<CommandRecord> =
    (count downTo 1).map { record(it.toLong()) }
```

Every assertion then follows from the real convention: one over the cap keeps ids
`MAX+1 … 2` and drops `1`; 500 keeps `500 … 301`; a cap of 3 on `10…1` keeps
`10, 9, 8`. The two ViewModel cap tests were re-seeded newest-first as well, and the
`assertEquals`/`assertNotEquals` on the evicted row now names the row it means.

Test-only change — no production code touched. All 23 cap assertions were re-checked
against the real semantics, including the store's sort-then-cap load path.

---

## 10. Hotfix — instrumented-test baseline (no app behaviour change)

Three items, nothing else touched.

**1. `gradle/libs.versions.toml`** — two version bumps:

| key | before | after |
|---|---|---|
| `espresso` | 3.6.1 | **3.7.0** |
| `androidxTestJunit` | 1.2.1 | **1.3.0** |

The explicit `androidx-test-espresso-core` dependency in `app/build.gradle.kts` is
kept on purpose: Compose `ui-test-junit4` pulls an older espresso transitively, and
without the explicit declaration the resolved version would silently be the old one.
No Compose BOM, AGP, Kotlin or app-code change.

**2. `FileHistoryStore.save()`** — `file.parentFile?.mkdirs()` before writing the temp
file, replacing `directory.mkdirs()`.

They look equivalent (the file is built from the directory) but they are not the same
contract: the thing that must exist for the rename to land is the **target's parent**,
not the constructor's argument. A sandbox reproduction of both paths shows the
difference — with the old line, `save` reported success while no file appeared:

```
old: save() returns true, history.json exists -> False
new: save() returns true, history.json exists -> True, round-trips
```

**3. One test assertion corrected, `save_createsTheDirectoryWhenMissing`.** It read
back through `newStore()` — the **root** test directory — while the file under test had
been written to `directory/nested/does-not-exist`. That assertion could only ever
prove that a *different* location was empty, so it would have failed even with the
store correct. It now round-trips through `store.load()`: the store that wrote the file
is the one that must read it.

### Build results

**Not executed — the sandbox has no JDK, no Android SDK and no `adb`/device.**

```
$ bash gradlew :app:testDebugUnitTest --rerun-tasks
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

$ bash gradlew :app:connectedDebugAndroidTest
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
```

`java`, `javac`, `ANDROID_HOME`, `ANDROID_SDK_ROOT` and `adb` are all absent, so
neither command could even reach Gradle configuration. To be run on the Windows
machine:

```powershell
.\gradlew :app:testDebugUnitTest --rerun-tasks
.\gradlew :app:connectedDebugAndroidTest
```

Expected: 385/385 unit tests; 28 instrumented, including
`HistoryStoreInstrumentedTest.save_createsTheDirectoryWhenMissing`, which is the test
this hotfix exists to satisfy.
