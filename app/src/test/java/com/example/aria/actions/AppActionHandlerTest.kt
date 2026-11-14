package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — "open <app>": matching rules, launch, and the honest miss.
 *
 * WHY SO MUCH ATTENTION ON MATCHING: launching the wrong app is the worst failure
 * mode in this phase — it is confusing and it erodes trust. The ranking is
 * therefore pinned case by case, including the awkward ones (YouTube vs YouTube
 * Music, a name that is only a substring of two apps).
 */
class AppActionHandlerTest {

    private val apps = FakeInstalledAppsProvider(
        listOf(
            AppEntry("com.android.chrome", "Chrome"),
            AppEntry("com.google.android.apps.maps", "Maps"),
            AppEntry("com.google.android.youtube", "YouTube"),
            AppEntry("com.google.android.apps.youtube.music", "YouTube Music"),
            AppEntry("com.spotify.music", "Spotify"),
            AppEntry("com.example.notes", "My Notes")
        )
    )
    private val launcher = FakeLauncher()
    private val handler = AppActionHandler(apps, launcher)

    // --- Matching -------------------------------------------------------------

    @Test
    fun `an exact label match wins over a longer app that starts with it`() = runTest {
        handler.handle(AssistantCommand.OpenApp("YouTube"))

        assertEquals(listOf(LaunchSpec.AppPackage("com.google.android.youtube")), launcher.launched)
    }

    @Test
    fun `matching ignores case and surrounding spaces`() = runTest {
        handler.handle(AssistantCommand.OpenApp("  spotify "))

        assertEquals(listOf(LaunchSpec.AppPackage("com.spotify.music")), launcher.launched)
    }

    @Test
    fun `a partial name falls back to contains`() = runTest {
        val result = handler.handle(AssistantCommand.OpenApp("notes"))

        assertEquals(listOf(LaunchSpec.AppPackage("com.example.notes")), launcher.launched)
        assertEquals(ExecutionResult.Success("Opening My Notes."), result)
    }

    @Test
    fun `an unknown name fails with the exact required wording`() = runTest {
        val result = handler.handle(AssistantCommand.OpenApp("Photoshop"))

        assertEquals(
            ExecutionResult.Failure(
                "Could not find an app named \"Photoshop\".",
                FailureReason.APP_NOT_FOUND
            ),
            result
        )
        assertTrue("Nothing may be launched", launcher.launched.isEmpty())
    }

    @Test
    fun `a blank name matches nothing rather than the first app`() = runTest {
        val result = handler.handle(AssistantCommand.OpenApp("   "))

        assertTrue("$result", result is ExecutionResult.Failure)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `an empty installed-app list is a clean miss`() = runTest {
        val empty = AppActionHandler(FakeInstalledAppsProvider(emptyList()), launcher)

        val result = empty.handle(AssistantCommand.OpenApp("Chrome"))

        assertTrue("$result", result is ExecutionResult.Failure)
    }

    // --- Launching ------------------------------------------------------------

    @Test
    fun `a matched app is launched by package, not by name`() = runTest {
        handler.handle(AssistantCommand.OpenApp("Chrome"))

        assertEquals(listOf(LaunchSpec.AppPackage("com.android.chrome")), launcher.launched)
    }

    @Test
    fun `a package that refuses to launch is reported as not found`() = runTest {
        // Visible in the launcher list but disabled: the system declines.
        val blocked = FakeLauncher(accepts = false)

        val result = AppActionHandler(apps, blocked).handle(AssistantCommand.OpenApp("Chrome"))

        // PHASE 6.5 (A1): the sentence is the same, but a launch that was refused
        // is now tagged LAUNCH_FAILED rather than sharing APP_NOT_FOUND's reason.
        assertEquals(
            ExecutionResult.Failure(
                "Could not find an app named \"Chrome\".",
                FailureReason.LAUNCH_FAILED
            ),
            result
        )
    }

    // --- Matcher, in isolation ------------------------------------------------

    @Test
    fun `the shortest label wins within a tier so results are deterministic`() {
        val candidates = listOf(
            AppEntry("com.b", "Maps Beta"),
            AppEntry("com.a", "Maps Pro"),
            AppEntry("com.c", "Maps")
        )

        val match = AppNameMatcher.bestMatch("Maps", candidates)

        assertEquals(AppEntry("com.c", "Maps"), match)
    }

    @Test
    fun `package name breaks a tie between identical labels`() {
        val candidates = listOf(
            AppEntry("com.z.same", "Same"),
            AppEntry("com.a.same", "Same")
        )

        assertEquals(AppEntry("com.a.same", "Same"), AppNameMatcher.bestMatch("Same", candidates))
    }

    @Test
    fun `entries without a package or a label are ignored`() {
        val candidates = listOf(
            AppEntry("", "Ghost"),
            AppEntry("com.blank", "   "),
            AppEntry("com.real", "Real")
        )

        assertEquals(AppEntry("com.real", "Real"), AppNameMatcher.bestMatch("e", candidates))
        assertNull("A blank query must never match", AppNameMatcher.bestMatch(" ", candidates))
    }

    @Test
    fun `no candidate at all yields null instead of throwing`() {
        assertNull(AppNameMatcher.bestMatch("Maps", emptyList()))
    }

    // --- Caching --------------------------------------------------------------

    @Test
    fun `the installed-app list is queried once and reused`() = runTest {
        // "open <app>" is repeated often; the PackageManager query is not free.
        val source = FakeInstalledAppsProvider(listOf(AppEntry("com.maps", "Maps")))
        val cached = CachingInstalledAppsProvider(source)

        cached.launcherApps()
        cached.launcherApps()
        cached.launcherApps()

        assertEquals(1, source.queryCount)
        assertEquals(listOf(AppEntry("com.maps", "Maps")), cached.launcherApps())
    }

    @Test
    fun `refreshing re-queries on the next access`() = runTest {
        val source = FakeInstalledAppsProvider(listOf(AppEntry("com.maps", "Maps")))
        val cached = CachingInstalledAppsProvider(source)
        cached.launcherApps()

        cached.refresh()
        cached.launcherApps()

        assertEquals(2, source.queryCount)
    }

    @Test
    fun `a cached provider still lets a handler open an app`() = runTest {
        // End to end through the cache, so the wrapper cannot silently break the
        // launch path (for example by returning an empty list).
        val source = FakeInstalledAppsProvider(listOf(AppEntry("com.maps", "Maps")))
        val handler = AppActionHandler(CachingInstalledAppsProvider(source), launcher)

        val result = handler.handle(AssistantCommand.OpenApp("Maps"))

        assertEquals(ExecutionResult.Success("Opening Maps."), result)
        assertEquals(listOf(LaunchSpec.AppPackage("com.maps")), launcher.launched)
    }
}
