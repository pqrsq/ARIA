package com.example.aria.probe

/**
 * PHASE 1-PROBE — the record of ONE attempt. Measurement only.
 *
 * WHY A FILE OF ITS OWN: the Activity should be about lifecycle and the probe
 * should be about numbers. Keeping the record separate also means the on-screen
 * list and the Logcat line are formatted from the SAME object, so what is read in
 * a screenshot can never disagree with what was logged.
 *
 * NOTHING HERE TOUCHES AUDIO. The only audio-derived field is [rmsMax], a single
 * scalar that is the maximum of `onRmsChanged`. No samples, no buffers, no file.
 */
data class ProbeAttempt(
    /** "A" default, "B" explicit service, "C" on-device. */
    val mode: String,
    /** Resolved ComponentName, or "on-device", or "?" before resolution. */
    val component: String,
    val readyMs: Long? = null,
    val beginMs: Long? = null,
    val endMs: Long? = null,
    /** Every PARTIAL exactly as received, in arrival order. Never merged. */
    val partials: List<String> = emptyList(),
    val final: String? = null,
    /** Max of `onRmsChanged` across the whole session; null if never called. */
    val rmsMax: Float? = null,
    val errorCode: Int? = null,
    val errorName: String? = null,
    /** What the system said was capturing when the session started. */
    val capturing: String = "unknown"
) {
    /**
     * How this attempt ended, for the comparison table.
     *
     * errorName is checked BEFORE errorCode because a failure can be produced
     * without a platform error code: mode C on a device with no on-device
     * recogniser, or a startListening() that threw. Those must read as
     * "UNAVAILABLE: ..." rather than as a bare "NO RESULT", which would hide the
     * most useful outcome this probe can produce.
     */
    val outcome: String
        get() = when {
            errorName != null -> errorName
            errorCode != null -> "ERROR $errorCode"
            final != null -> final
            else -> "NO RESULT"
        }

    /**
     * One line for Logcat and one row on screen.
     *
     * rmsMax is printed to one decimal because the service reports it in dB with
     * a practical ceiling of 10.0; more precision would imply accuracy the value
     * does not have.
     */
    fun logLine(): String {
        val rms = rmsMax?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "n/a"
        return buildString {
            append("mode=").append(mode)
            append(" | component=").append(component)
            append(" | ready_ms=").append(readyMs ?: "-")
            append(" | begin_ms=").append(beginMs ?: "-")
            append(" | end_ms=").append(endMs ?: "-")
            append(" | rms_max=").append(rms)
            append(" | capturing=").append(capturing)
            append(" | partials=").append(partials.joinToString(" | ") { "\"$it\"" }.ifEmpty { "-" })
            append(" | final=").append(final?.let { "\"$it\"" } ?: "-")
            if (errorName != null || errorCode != null) {
                append(" | error=").append(errorCode ?: "-")
                append(" (").append(errorName ?: "?").append(")")
            }
        }
    }
}
