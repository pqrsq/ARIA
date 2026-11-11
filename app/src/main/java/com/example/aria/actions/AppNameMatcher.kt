package com.example.aria.actions

/**
 * PHASE 6 — matching a spoken app name against the installed launchers.
 *
 * ── WHY THIS IS PURE AND SEPARATE ────────────────────────────────────────────
 * Asking `PackageManager` for launcher activities is an Android call; deciding
 * *which* of them the user meant is plain string work and is where the bugs are
 * ("YouTube" vs "YouTube Music", "Chrome" vs "Chrome Beta"). Keeping the matcher
 * pure means the ranking rules can be tested exhaustively on the JVM with a fake
 * app list, and the Android class stays a thin query.
 *
 * ── THE RANKING (deliberately simple and predictable) ────────────────────────
 *  1. **Exact** label match, ignoring case and surrounding spaces.
 *  2. **Starts with** the spoken name.
 *  3. **Contains** the spoken name.
 * Within a tier, the *shortest* label wins (so "Maps" beats "Maps Beta"), and
 * package name breaks the remaining ties so the result is deterministic rather
 * than "whatever order the system returned".
 *
 * WHY NOT FUZZY MATCHING: a wrong guess launches a stranger's app. Precision over
 * recall — no match is better than the wrong app, and the caller reports the miss.
 *
 * ── LIMITATION (documented in docs/PHASE6.md) ────────────────────────────────
 * ARIA only sees apps that expose a launcher activity and are visible under
 * Android 11+ package-visibility rules (see the `<queries>` block in the
 * manifest). It never asks for `QUERY_ALL_PACKAGES`.
 */
data class AppEntry(
    /** The package id, e.g. `com.google.android.apps.maps`. */
    val packageName: String,
    /** The launcher label the user actually sees, e.g. "Maps". */
    val label: String
)

/** One ranking outcome, kept as a type so a caller cannot grab the wrong field. */
object AppNameMatcher {

    /**
     * Returns the best candidate for [spokenName], or null when nothing matches.
     *
     * @param spokenName raw speech; compared case-insensitively after trimming.
     */
    fun bestMatch(spokenName: String, candidates: List<AppEntry>): AppEntry? {
        val wanted = spokenName.trim().lowercase()
        if (wanted.isEmpty()) return null

        val labelled = candidates
            .filter { it.packageName.isNotBlank() }
            .map { it to it.label.trim().lowercase() }
            .filter { (_, lower) -> lower.isNotEmpty() }

        return ranked(labelled, wanted)?.first
    }

    private fun ranked(
        labelled: List<Pair<AppEntry, String>>,
        wanted: String
    ): Pair<AppEntry, String>? {
        val tiers = listOf(
            labelled.filter { (_, lower) -> lower == wanted },
            labelled.filter { (_, lower) -> lower.startsWith(wanted) },
            labelled.filter { (_, lower) -> lower.contains(wanted) }
        )

        for (tier in tiers) {
            if (tier.isNotEmpty()) {
                // Shortest label, then package name: deterministic across devices
                // whose PackageManager returns activities in a different order.
                return tier.sortedWith(
                    compareBy<Pair<AppEntry, String>> { (_, lower) -> lower.length }
                        .thenBy { (entry, _) -> entry.packageName }
                ).first()
            }
        }
        return null
    }
}
