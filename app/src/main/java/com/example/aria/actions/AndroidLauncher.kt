package com.example.aria.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * PHASE 6 — the single place a [LaunchSpec] becomes a real `Intent`.
 *
 * ── WHY LAUNCH-AND-CATCH INSTEAD OF `resolveActivity()` ─────────────────────
 * On Android 11+ `resolveActivity()` returns null for activities the caller
 * cannot *see* under package visibility, even when the launch would succeed — so
 * pre-checking produces false "no app installed" answers. Trying the launch and
 * catching [ActivityNotFoundException] asks the only question that matters and
 * gets the correct answer. It also removes a check-then-act race.
 *
 * ── `FLAG_ACTIVITY_NEW_TASK` ────────────────────────────────────────────────
 * ARIA launches from an Application context (the executor is Application-scoped),
 * and a non-Activity context cannot start an activity without this flag. It is
 * harmless for settings, maps and app launches alike.
 *
 * ── WHAT `true` MEANS ───────────────────────────────────────────────────────
 * Only that some activity accepted the intent. Never that the action inside it
 * happened — see [LaunchSpec].
 */
class AndroidLauncher(
    context: Context
) : Launcher {

    private val applicationContext = context.applicationContext

    override suspend fun launch(spec: LaunchSpec): Boolean {
        val intent = buildIntent(spec) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            applicationContext.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            false
        } catch (error: SecurityException) {
            // Some OEM builds throw SecurityException instead of
            // ActivityNotFoundException for blocked system screens. The caller's
            // wording ("no app … is installed" / "not supported") stays truthful.
            false
        }
    }

    /** Translates a spec, or null when the platform cannot build one. */
    private fun buildIntent(spec: LaunchSpec): Intent? = when (spec) {
        is LaunchSpec.ViewUri -> Intent(Intent.ACTION_VIEW, Uri.parse(spec.uri))

        is LaunchSpec.MusicApp ->
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)

        is LaunchSpec.SettingsScreen -> Intent(spec.action)

        is LaunchSpec.AppPackage ->
            applicationContext.packageManager.getLaunchIntentForPackage(spec.packageName)
    }
}
