package com.example.aria.actions

import com.example.aria.command.AssistantCommand

/**
 * PHASE 6 — open maps, search for a place, start navigation.
 *
 * ── THE THREE URIS, AND WHY EACH ONE ────────────────────────────────────────
 *  - `geo:0,0` — open a map centred on nothing in particular. Any map app
 *    handles it.
 *  - `geo:0,0?q=<encoded>` — a **place search**. The `0,0` is required: the
 *    `geo:` scheme demands a coordinate pair, and `0,0` is the conventional
 *    "no position" placeholder. Using `geo:0,0?q=` rather than
 *    `geo:<lat>,<lng>?q=` means the app decides what is nearby instead of ARIA
 *    inventing a location.
 *  - `google.navigation:q=<encoded>` — **turn-by-turn**. A Google-specific
 *    scheme, but the de-facto standard; apps that do not handle it simply fail
 *    to launch, which is reported honestly.
 *
 * ── WHY "OPENING", NEVER "NAVIGATING" ───────────────────────────────────────
 * ARIA hands a destination to another app and cannot see what happens next: the
 * user may cancel, the app may show a route preview, GPS may be off. Every
 * message therefore describes the hand-off. The one fixed wording the Phase 6
 * spec demands — "Opening navigation to X in your map app." — is exactly that.
 *
 * ── NO `resolveActivity()` PRE-CHECK ────────────────────────────────────────
 * Under Android 11+ package visibility it produces false negatives; the launch
 * is attempted and `ActivityNotFoundException` becomes "No map app is installed".
 */
class MapsActionHandler(
    private val launcher: Launcher
) {

    suspend fun handle(command: AssistantCommand): ExecutionResult = when (command) {
        is AssistantCommand.OpenMaps -> launch(Uris.OPEN_MAPS, ActionMessages.OPENING_MAPS)

        is AssistantCommand.SearchLocation ->
            launch(Uris.mapSearch(command.query), ActionMessages.mapSearch(command.query))

        is AssistantCommand.NavigateTo ->
            launch(Uris.navigation(command.destination), ActionMessages.navigation(command.destination))

        else -> ExecutionResult.NotSupported(ActionMessages.UNEXPECTED)
    }

    private suspend fun launch(uri: String, successMessage: String): ExecutionResult =
        if (launcher.launch(LaunchSpec.ViewUri(uri))) {
            ExecutionResult.Success(successMessage)
        } else {
            // The Phase 6 spec fixes this wording verbatim.
            ExecutionResult.Failure(ActionMessages.NO_MAP_APP)
        }

    /** The exact URI strings, kept next to their tests. */
    object Uris {

        /** Plain map view. */
        const val OPEN_MAPS: String = "geo:0,0"

        /** Place search around an unspecified position. */
        fun mapSearch(query: String): String = "geo:0,0?q=${UriEncoding.encode(query)}"

        /** Turn-by-turn hand-off. */
        fun navigation(destination: String): String =
            "google.navigation:q=${UriEncoding.encode(destination)}"
    }
}
