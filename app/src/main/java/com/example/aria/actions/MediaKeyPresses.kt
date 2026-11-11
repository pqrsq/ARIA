package com.example.aria.actions

/**
 * PHASE 6 — the media buttons ARIA can press.
 *
 * Mirrors the `KeyEvent.KEYCODE_MEDIA_*` codes ARIA needs, as a domain enum so
 * handlers and tests never handle raw integers.
 */
enum class MediaKey {
    PAUSE,
    PLAY,
    NEXT,
    PREVIOUS
}

/**
 * One half of a key press.
 *
 * @param keyCode the `KeyEvent.KEYCODE_MEDIA_*` value.
 * @param isDown true for `ACTION_DOWN`, false for `ACTION_UP`.
 */
data class MediaKeyEvent(val keyCode: Int, val isDown: Boolean)

/**
 * PHASE 6 — builds the down/up pair for each media key.
 *
 * ── WHY THIS IS PURE AND TESTED ──────────────────────────────────────────────
 * `AudioManager.dispatchMediaKeyEvent` needs **both** `ACTION_DOWN` and
 * `ACTION_UP` for the same keycode; sending only one is a classic bug that
 * *sometimes* works (some players react to DOWN) and thus survives manual
 * testing while breaking others. That pairing is decided here, as data, so a JVM
 * test can assert it exactly instead of trusting a device.
 *
 * The keycodes are `KeyEvent` *constants* (compile-time `Int`s), so referencing
 * them adds no Android runtime dependency to unit tests.
 */
object MediaKeyPresses {

    /** The codes this module can dispatch. */
    fun keyCodeFor(key: MediaKey): Int = when (key) {
        MediaKey.PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        MediaKey.PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        MediaKey.NEXT -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        MediaKey.PREVIOUS -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
    }

    /**
     * The two events that make one press, DOWN first then UP.
     *
     * DOWN must come first: players treat DOWN as the press and UP as the
     * release, and an UP without a DOWN is ignored.
     */
    fun eventsFor(key: MediaKey): List<MediaKeyEvent> {
        val code = keyCodeFor(key)
        return listOf(MediaKeyEvent(code, isDown = true), MediaKeyEvent(code, isDown = false))
    }
}

/**
 * PHASE 6 — the seam over `AudioManager.dispatchMediaKeyEvent`.
 *
 * A press is a *dispatch*, not a guarantee: ARIA cannot observe whether a player
 * was listening, which is why the handler's message says "Sent a pause request",
 * never "Music paused".
 */
fun interface MediaKeyController {
    /** Sends both halves of one press; false when the system rejected it. */
    fun press(key: MediaKey): Boolean
}
