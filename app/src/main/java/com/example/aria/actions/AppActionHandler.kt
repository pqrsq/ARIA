package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — "open <app>".
 *
 * ── WHY THE MATCHING IS SO STRICT ───────────────────────────────────────────
 * Launching the wrong app is worse than launching nothing: it is confusing, it
 * can expose another person's data, and it teaches the user not to trust the
 * assistant. The ranking is therefore exact → starts-with → contains, shortest
 * label first, and a *miss is reported*, never guessed at
 * (see [AppNameMatcher] for the full rules and the rationale).
 *
 * ── THE FAILURE MESSAGE ─────────────────────────────────────────────────────
 * `Could not find an app named "X".` — the exact wording the Phase 6 spec asks
 * for, and the only truthful thing to say when a launcher cannot be matched.
 * It is a [ExecutionResult.Failure] because ARIA was asked to do something it
 * could not do, unlike an unrecognised sentence.
 *
 * ── LIMITATION (see docs/PHASE6.md) ─────────────────────────────────────────
 * Only apps visible under Android 11+ package visibility are candidates. ARIA
 * asks for a `<queries>` entry for launcher activities — never
 * `QUERY_ALL_PACKAGES` — so a few apps that hide from launcher queries cannot be
 * opened by name. Saying "could not find" is honest about that.
 */
class AppActionHandler(
    private val apps: InstalledAppsProvider,
    private val launcher: Launcher
) {

    suspend fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.OpenApp -> openApp(command.appName)
        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }

    private suspend fun openApp(appName: String): ExecutionResult {
        val match = AppNameMatcher.bestMatch(appName, apps.launcherApps())
            ?: return ExecutionResult.Failure(ActionMessages.appNotFound(appName.trim()))

        return if (launcher.launch(LaunchSpec.AppPackage(match.packageName))) {
            ExecutionResult.Success("Opening ${match.label}.")
        } else {
            // Visible in the launcher list but not launchable: an app that is
            // disabled, or whose launch intent the system refused.
            ExecutionResult.Failure(ActionMessages.appNotFound(appName.trim()))
        }
    }
}
