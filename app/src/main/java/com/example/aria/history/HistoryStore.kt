package com.example.aria.history

import com.example.aria.ui.CommandRecord

/**
 * PHASE 12 — the storage contract for command history.
 *
 * ── WHY AN INTERFACE, AND WHY SUSPEND ───────────────────────────────────────
 * The ViewModel must not know that history lives in a JSON file, because then a
 * JVM test could not assert persistence without a device. Everything the
 * ViewModel does with history goes through these three calls, and the only
 * implementation that touches Android is [FileHistoryStore].
 *
 * The methods are `suspend` because they do disk I/O. Making them blocking would
 * force the caller to guess a thread; making them `Flow`s would over-promise
 * (there is exactly one writer, and the ViewModel already owns the in-memory
 * list, which is the source of truth for the UI).
 *
 * ── THE CONTRACT A CALLER CAN RELY ON ──────────────────────────────────────
 *  - [load] NEVER throws and never returns more than [MAX_HISTORY_RECORDS]; a
 *    missing, unreadable or corrupt file is an empty history, not an error. A
 *    crashed app because of its own debug log would be absurd.
 *  - [save] receives the **complete** list to store, newest first. Callers do a
 *    full rewrite; there is no append, no partial update and no delta, so the
 *    file can never drift out of step with one code path that forgot to merge.
 *  - [clear] leaves no history behind. It is separate from `save(emptyList())`
 *    so that "the user asked for this" is distinguishable at the call site and in
 *    the adapter's own tests.
 */
interface HistoryStore {

    /**
     * Reads the stored records, newest first.
     *
     * Returns an empty list for a missing file, an empty file, malformed JSON, a
     * wrong root type, or any I/O error. Stored rows that cannot be decoded are
     * dropped rather than failing the whole load: losing one debug row is better
     * than losing the app.
     */
    suspend fun load(): List<CommandRecord>

    /**
     * Replaces the stored history with [records] (newest first).
     *
     * The caller is responsible for the cap ([MAX_HISTORY_RECORDS]); the adapter
     * writes what it is given, truncated defensively to the same cap so a bug
     * upstream cannot produce an unbounded file.
     */
    suspend fun save(records: List<CommandRecord>)

    /** Removes the stored history. Idempotent: clearing twice is not an error. */
    suspend fun clear()
}

/**
 * PHASE 12 — how many records are kept.
 *
 * WHY A CAP AT ALL: this is a debug/history list rendered in a plain `Column`;
 * an unbounded file would grow forever and every append rewrites the whole list,
 * so cost would grow quadratically. 200 is roughly several days of realistic use
 * and stays far below anything that would make a rewrite noticeable.
 *
 * WHERE IT IS ENFORCED: in [capHistory], a pure function, so the rule is
 * JVM-testable without a device and without a store.
 */
const val MAX_HISTORY_RECORDS: Int = 200

/**
 * PHASE 12 — keeps the newest [max] records, preserving order.
 *
 * WHY A FUNCTION AND NOT A LINE IN THE ADAPTER: the cap is behaviour, not
 * storage. Asserting it through a file-backed adapter would need Android; here it
 * is one call with one list. It is also idempotent and stable, so applying it
 * twice (which the ViewModel and the adapter both do, defensively) is harmless.
 */
fun capHistory(records: List<CommandRecord>, max: Int = MAX_HISTORY_RECORDS): List<CommandRecord> {
    require(max > 0) { "max must be positive, was $max" }
    return if (records.size <= max) records else records.take(max)
}
