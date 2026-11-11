package com.example.aria.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * PHASE 6 — the seam over "which apps can be launched on this device?".
 *
 * Returns plain [AppEntry] data, so [AppActionHandler] (and its matcher) can be
 * exercised on the JVM with a fake app list.
 */
fun interface InstalledAppsProvider {
    /**
     * Every app with a launcher activity, as (package, label) pairs.
     *
     * `suspend` because the real query is a cross-process `PackageManager` call
     * and a caching implementation needs to serialise concurrent fills without
     * blocking a thread — see [CachingInstalledAppsProvider].
     */
    suspend fun launcherApps(): List<AppEntry>
}

/**
 * PHASE 6 — the real provider, backed by `PackageManager`.
 *
 * ── PACKAGE VISIBILITY, HONESTLY ─────────────────────────────────────────────
 * On Android 11+ this query returns only apps the manifest's `<queries>` block
 * makes visible — here: anything with a `MAIN`/`LAUNCHER` activity, declared as
 * the `<intent>` filter in `AndroidManifest.xml`. That is a narrow, reviewable
 * declaration and is *not* `QUERY_ALL_PACKAGES`, which ARIA deliberately does not
 * request.
 *
 * The consequence, documented in docs/PHASE6.md: an app that hides itself from
 * launcher queries cannot be found by name, even if the user sees it in the
 * drawer. The failure message says the app was not found rather than pretending
 * otherwise.
 */
class AndroidInstalledAppsProvider(
    context: Context
) : InstalledAppsProvider {

    private val applicationContext = context.applicationContext

    override suspend fun launcherApps(): List<AppEntry> {
        val packageManager = applicationContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        // FLAG_MATCH_DISABLED? No: a disabled app cannot be launched, so it must
        // not be offered as a candidate.
        val resolved: List<ResolveInfo> = try {
            packageManager.queryIntentActivities(intent, 0)
        } catch (error: RuntimeException) {
            // A vendor PackageManager can throw on a malformed query. An empty
            // list degrades to "could not find an app named X", which is honest.
            emptyList()
        }

        return resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = try {
                info.loadLabel(packageManager).toString()
            } catch (error: RuntimeException) {
                // Falling back to the package id keeps the app findable by a
                // spoken name that happens to match its package.
                packageName
            }
            AppEntry(packageName = packageName, label = label.ifBlank { packageName })
        }.distinctBy { it.packageName }
    }
}
