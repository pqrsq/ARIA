package com.example.aria.actions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PHASE 6 — remembers the launcher list for the life of the process.
 *
 * ── WHY A CACHE IS NOT PREMATURE OPTIMISATION HERE ──────────────────────────
 * `PackageManager.queryIntentActivities` is a cross-process query that walks every
 * installed package; on a device with 200 apps it costs real time, and
 * "open <app>" is exactly the command a user repeats. It also runs inside a
 * coroutine the ViewModel may cancel, so the fill has to tolerate *cancellation*,
 * not merely slowness.
 *
 * ── WHY NOT `by lazy` ───────────────────────────────────────────────────────
 * `lazy` publishes a value computed on whichever thread touched it first and has
 * no notion of a *cancelled* initialiser, so a cancelled fill could leave a
 * half-built list behind. A [Mutex] plus a check inside the lock expresses
 * "compute once, retry if it never finished" honestly and without blocking a
 * thread.
 *
 * ── STALENESS, STATED PLAINLY ───────────────────────────────────────────────
 * The list is cleared only by [refresh], which nothing calls automatically this
 * phase. Consequence (documented in docs/PHASE6.md §8): an app installed while
 * ARIA is running is not findable by name until the process restarts, and the
 * message says "Could not find an app named X" rather than pretending otherwise.
 * A `PACKAGE_ADDED` receiver or a per-command query is a later-phase decision.
 */
class CachingInstalledAppsProvider(
    private val source: InstalledAppsProvider
) : InstalledAppsProvider {

    private val mutex = Mutex()

    @Volatile
    private var cached: List<AppEntry>? = null

    override suspend fun launcherApps(): List<AppEntry> {
        cached?.let { return it }

        return mutex.withLock {
            // Re-check inside the lock: a concurrent caller may have filled it
            // while this coroutine was waiting.
            cached ?: source.launcherApps().also { cached = it }
        }
    }

    /** Forces a re-query on the next access; for a future "app installed" hook. */
    suspend fun refresh() {
        mutex.withLock { cached = null }
    }
}
