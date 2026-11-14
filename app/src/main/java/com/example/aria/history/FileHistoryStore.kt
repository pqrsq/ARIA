package com.example.aria.history

import android.util.Log
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.CommandRecord
import com.example.aria.ui.InputSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * PHASE 12 — history in a plain JSON file in app-private storage.
 *
 * ── WHY A FILE AND NOT ROOM/DATASTORE ───────────────────────────────────────
 * A decided point, not an oversight: the data is one flat, capped (200) list of
 * small records that is always read and written whole. Room would bring KSP and
 * an annotation processor to a problem that needs `File.writeText`. No new
 * dependency is added for this phase, and Room stays a V2 option if history grows
 * queries, filters or migrations.
 *
 * ── WHY org.json IS CONTAINED HERE ──────────────────────────────────────────
 * `org.json` is an Android stub in JVM unit tests, so touching it from anything
 * that runs on the JVM would make those tests fail at runtime. This class is the
 * single Android adapter; the pure layer never sees a `JSONObject`. The JVM suite
 * tests a fake store instead, and this adapter's own behaviour (survives process
 * death, corrupt file recovers) is covered on a device.
 *
 * ── ATOMIC WRITE ────────────────────────────────────────────────────────────
 * The list is written to `history.json.tmp` and then renamed over `history.json`.
 * `File.renameTo` within one directory is atomic on the same filesystem, so a
 * process death mid-write leaves either the old complete file or the new complete
 * file — never a half-written one. Losing history because the phone was killed
 * while saving would be a poor trade for the two lines it costs.
 *
 * ── NEVER THROWS ────────────────────────────────────────────────────────────
 * History is a convenience. Every failure path is caught and logged: a corrupt
 * file yields an empty history, a write error is swallowed, and the app keeps
 * working. The UI's in-memory list stays authoritative for the session either way.
 */
class FileHistoryStore(
    private val directory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : HistoryStore {

    /** The real file. Named so tests and the privacy rules can refer to one place. */
    val file: File get() = File(directory, FILE_NAME)

    override suspend fun load(): List<CommandRecord> = withContext(ioDispatcher) {
        val source = file
        if (!source.isFile) return@withContext emptyList()

        val raw = try {
            source.readText()
        } catch (error: IOException) {
            Log.w(TAG, "Could not read $FILE_NAME; starting with an empty history.", error)
            return@withContext emptyList()
        }

        if (raw.isBlank()) return@withContext emptyList()

        val decoded = try {
            JSONArray(raw)
        } catch (error: JSONException) {
            // A truncated or hand-edited file. Discard it rather than crash, and
            // say so in logcat so a lost history can be explained.
            Log.w(TAG, "Ignoring corrupt $FILE_NAME.", error)
            return@withContext emptyList()
        }

        val records = ArrayList<CommandRecord>(decoded.length())
        for (index in 0 until decoded.length()) {
            val objectAt = decoded.optJSONObject(index) ?: continue
            val record = objectAt.toRecord() ?: continue
            records += record
        }
        // Newest first, whatever order the file claims: the UI renders list order
        // directly, so ordering must not depend on the writer having been right.
        capHistory(records.sortedByDescending { it.timestampMillis })
    }

    override suspend fun save(records: List<CommandRecord>) = withContext(ioDispatcher) {
        val capped = capHistory(records)
        val array = JSONArray()
        capped.forEach { array.put(it.toJson()) }

        val target = file
        val temporary = File(directory, "$FILE_NAME.tmp")
        try {
            // HOTFIX: create the TARGET's parent, not just the constructor's
            // directory. Those are normally the same object, but "honour the
            // directory that was given" is the contract, and `file.parentFile`
            // is what actually has to exist for the rename to land. Using
            // `directory.mkdirs()` made save() silently depend on the caller
            // passing an already-usable File.
            target.parentFile?.mkdirs()
            temporary.writeText(array.toString())
            // Rename over the real file. On failure, remove the temp so a later
            // read cannot pick up a half-written file.
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) {
                    Log.w(TAG, "Could not replace $FILE_NAME; history not saved.")
                    temporary.delete()
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not write $FILE_NAME; history not saved.", error)
            temporary.delete()
        }
        Unit
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        // Delete both, so a leftover temp from an interrupted save cannot be
        // resurrected by the next load.
        File(directory, "$FILE_NAME.tmp").delete()
        file.delete()
        Unit
    }

    private fun CommandRecord.toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_TIMESTAMP, timestampMillis)
        put(KEY_RAW_SPEECH, rawSpeech)
        put(KEY_NORMALIZED, normalizedText)
        put(KEY_COMMAND_TYPE, commandType)
        put(KEY_HANDLER, handler)
        put(KEY_STATUS, status.name)
        put(KEY_MESSAGE, message)
        // PHASE P1.6: the source marker is part of the row, so a typed test
        // command is still attributable after a restart.
        put(KEY_SOURCE, source.name)
    }

    /**
     * Decodes one row, or null when it is unusable.
     *
     * WHY null INSTEAD OF EXCEPTIONS: one malformed row must not discard the other
     * 199. A row without an id or a timestamp has no meaning in a newest-first
     * list, so those are required; every other field falls back to the same
     * defaults [CommandRecord] itself uses, so an older file (or a field added
     * later) still loads.
     */
    private fun JSONObject.toRecord(): CommandRecord? {
        val id = optLong(KEY_ID, -1L)
        if (id < 0L) return null
        val timestamp = optLong(KEY_TIMESTAMP, -1L)
        if (timestamp < 0L) return null

        return CommandRecord(
            id = id,
            timestampMillis = timestamp,
            rawSpeech = optString(KEY_RAW_SPEECH, ""),
            normalizedText = optString(KEY_NORMALIZED, ""),
            commandType = optString(KEY_COMMAND_TYPE, DEFAULT_COMMAND_TYPE),
            handler = optString(KEY_HANDLER, DEFAULT_HANDLER),
            // Unknown names fall back to the record's own default rather than
            // throwing: the enum may gain values later, or the file may be from a
            // newer build.
            status = enumByName(AssistantStatus.values(), optString(KEY_STATUS))
                ?: AssistantStatus.SUCCESS,
            message = optString(KEY_MESSAGE, ""),
            source = enumByName(InputSource.values(), optString(KEY_SOURCE))
                ?: InputSource.VOICE
        )
    }

    /**
     * Case-sensitive name lookup over [values].
     *
     * WHY NOT `valueOf`: `valueOf` throws on an unknown name, and a file written
     * by a newer build (or edited by hand) must not take the whole load down. A
     * null here lets the caller fall back to the field's default.
     */
    private fun <T : Enum<T>> enumByName(values: Array<T>, name: String): T? =
        values.firstOrNull { it.name == name }

    companion object {
        private const val TAG = "FileHistoryStore"

        /** The file name. Referenced by the backup-exclusion rules. */
        const val FILE_NAME: String = "history.json"

        // Defaults mirror CommandRecord's own, so a partial row decodes exactly as
        // a partially-constructed record would behave in memory.
        private const val DEFAULT_COMMAND_TYPE = "UNKNOWN_COMMAND"
        private const val DEFAULT_HANDLER = "—"

        private const val KEY_ID = "id"
        private const val KEY_TIMESTAMP = "timestampMillis"
        private const val KEY_RAW_SPEECH = "rawSpeech"
        private const val KEY_NORMALIZED = "normalizedText"
        private const val KEY_COMMAND_TYPE = "commandType"
        private const val KEY_HANDLER = "handler"
        private const val KEY_STATUS = "status"
        private const val KEY_MESSAGE = "message"
        private const val KEY_SOURCE = "source"
    }
}
