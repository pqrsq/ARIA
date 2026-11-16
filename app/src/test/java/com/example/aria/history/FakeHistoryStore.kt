package com.example.aria.history

import com.example.aria.ui.CommandRecord
import kotlinx.coroutines.CompletableDeferred

/**
 * PHASE 12 — in-memory [HistoryStore] for JVM tests.
 *
 * WHY A FAKE AND NOT A TEMP-DIRECTORY [FileHistoryStore]: the real adapter needs
 * `org.json` and `android.util.Log`, both of which are stubs in JVM unit tests, so
 * a file-backed store simply cannot run there. The fake lets the *ViewModel's*
 * persistence behaviour (load on init, one row per command, cap, clear) be proven
 * without a device, which is the point of keeping the store behind an interface.
 *
 * The adapter's own behaviour — surviving process death, recovering from a corrupt
 * file — is genuinely device-only and is covered in the instrumented tests and the
 * manual script, not pretended at here.
 */
class FakeHistoryStore(
    /** Records returned by [load], newest first. Tests seed this directly. */
    var stored: List<CommandRecord> = emptyList()
) : HistoryStore {

    /** Every list passed to [save], in order, so tests can assert how often. */
    val saves: MutableList<List<CommandRecord>> = mutableListOf()

    var loadCount: Int = 0
        private set
    var clearCount: Int = 0
        private set

    /** Set to make [load] throw, proving the ViewModel survives a broken store. */
    var loadFailure: RuntimeException? = null

    /** Set to make [save] throw. */
    var saveFailure: RuntimeException? = null

    /** Slow the load down, so a test can prove an append does not race it. */
    var loadGate: (suspend () -> Unit)? = null

    /**
     * R1 — DETERMINISTIC suspension for [load].
     *
     * `loadGate` above takes a lambda, which makes "the load is now suspended"
     * something a test has to infer with sleeps. This is a hard gate: while it is
     * set and not completed, [load] suspends inside the call, so a test can be
     * certain the load has STARTED and has not RETURNED. That is exactly the
     * window the cold-start race lived in.
     *
     * The records the load will eventually return are captured when it reaches the
     * gate, i.e. they are the pre-clear rows even if `stored` changes while the
     * gate is shut — which is what a real `load()` does too, having already read
     * the file.
     */
    var loadDeferred: CompletableDeferred<Unit>? = null

    /** True once [load] has reached the gate — the load has started. */
    var loadStarted: Boolean = false
        private set

    override suspend fun load(): List<CommandRecord> {
        loadCount++
        loadStarted = true
        loadDeferred?.await()
        loadGate?.invoke()
        loadFailure?.let { throw it }
        // Snapshot at return time, like a real read would.
        return stored
    }

    override suspend fun save(records: List<CommandRecord>) {
        saves += records
        saveFailure?.let { throw it }
        stored = records
    }

    override suspend fun clear() {
        clearCount++
        stored = emptyList()
    }
}
