package com.example.aria.actions

import com.example.aria.command.AssistantCommand
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 6 — the three map URIs, and the wording that must not overclaim.
 *
 * WHY URIs ARE ASSERTED AS STRINGS: they are handed to another app verbatim. A
 * wrong scheme does not throw — it opens the wrong screen or nothing at all — so
 * this is exactly the kind of bug that manual testing "mostly" misses.
 */
class MapsActionHandlerTest {

    private val launcher = FakeLauncher()
    private val handler = MapsActionHandler(launcher)

    @Test
    fun `open maps uses the plain geo URI`() = runTest {
        val result = handler.handle(AssistantCommand.OpenMaps)

        assertEquals(listOf(LaunchSpec.ViewUri("geo:0,0")), launcher.launched)
        assertTrue("$result", result.isSuccess)
    }

    @Test
    fun `searching a location uses an encoded geo query`() = runTest {
        val result = handler.handle(AssistantCommand.SearchLocation("Eiffel Tower"))

        assertEquals(
            listOf(LaunchSpec.ViewUri("geo:0,0?q=Eiffel%20Tower")),
            launcher.launched
        )
        assertEquals(
            ExecutionResult.Success("Opening a map search for \"Eiffel Tower\"."),
            result
        )
    }

    @Test
    fun `navigation uses the turn-by-turn scheme and the exact required wording`() = runTest {
        val result = handler.handle(AssistantCommand.NavigateTo("Milan"))

        assertEquals(
            listOf(LaunchSpec.ViewUri("google.navigation:q=Milan")),
            launcher.launched
        )
        // Fixed by the Phase 6 specification, character for character.
        assertEquals("Opening navigation to Milan in your map app.", result.message)
    }

    @Test
    fun `a destination with spaces and punctuation is percent-encoded`() = runTest {
        handler.handle(AssistantCommand.NavigateTo("Piazza del Duomo, Milano"))

        assertEquals(
            listOf(LaunchSpec.ViewUri("google.navigation:q=Piazza%20del%20Duomo%2C%20Milano")),
            launcher.launched
        )
    }

    @Test
    fun `no map app is a failure with the fixed message`() = runTest {
        val blocked = FakeLauncher(accepts = false)

        val result = MapsActionHandler(blocked).handle(AssistantCommand.NavigateTo("Milan"))

        assertEquals(
            ExecutionResult.Failure(ActionMessages.NO_MAP_APP, FailureReason.NOT_SUPPORTED),
            result
        )
    }

    @Test
    fun `no map app also covers plain map opening and search`() = runTest {
        val blocked = FakeLauncher(accepts = false)
        val handler = MapsActionHandler(blocked)

        assertTrue(handler.handle(AssistantCommand.OpenMaps) is ExecutionResult.Failure)
        assertTrue(handler.handle(AssistantCommand.SearchLocation("cafe")) is ExecutionResult.Failure)
    }

    @Test
    fun `nothing claims navigation started`() = runTest {
        // The distinction the spec insists on: opening the hand-off is not the
        // same as navigating.
        val results = listOf(
            handler.handle(AssistantCommand.NavigateTo("Milan")),
            handler.handle(AssistantCommand.OpenMaps),
            handler.handle(AssistantCommand.SearchLocation("cafe"))
        )

        results.forEach { result ->
            assertFalse("'${result.message}' claims the action happened", result.message.contains("Navigating"))
            assertTrue("'${result.message}' should be a hand-off", result.message.startsWith("Opening"))
        }
    }

    // --- Encoding (pure) ------------------------------------------------------

    @Test
    fun `encoding leaves unreserved characters alone`() {
        assertEquals(
            "ABCxyz019-_.~",
            UriEncoding.encode("ABCxyz019-_.~")
        )
    }

    @Test
    fun `encoding turns a space into percent twenty, never a plus`() {
        // A '+' would be read literally by a geo: URI ("Eiffel+Tower").
        assertEquals("Eiffel%20Tower", UriEncoding.encode("Eiffel Tower"))
        assertFalse(UriEncoding.encode("a b").contains('+'))
    }

    @Test
    fun `encoding escapes reserved characters and encodes unicode`() {
        assertEquals("caf%C3%A9", UriEncoding.encode("café"))
        assertEquals("a%26b", UriEncoding.encode("a&b"))
        assertEquals("%3F%23%2F", UriEncoding.encode("?#/"))
    }

    @Test
    fun `encoding an empty string stays empty`() {
        assertEquals("", UriEncoding.encode(""))
    }
}
