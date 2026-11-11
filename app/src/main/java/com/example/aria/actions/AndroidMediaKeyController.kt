package com.example.aria.actions

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * PHASE 6 — the real [MediaKeyController].
 *
 * ── WHY `dispatchMediaKeyEvent` AND NOT A PLAYBACK API ───────────────────────
 * There is no public "pause whatever is playing" API without a `MediaSession` or
 * notification-listener access, both of which are out of scope (and the latter
 * would be a very invasive permission). Dispatching the same key a headset button
 * sends is the narrow, honest mechanism: the *currently active* player reacts, or
 * nothing does — and ARIA reports only that it asked.
 */
class AndroidMediaKeyController(
    context: Context
) : MediaKeyController {

    private val applicationContext = context.applicationContext

    private val audioManager: AudioManager
        get() = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun press(key: MediaKey): Boolean {
        val events = MediaKeyPresses.eventsFor(key)
        return try {
            events.forEach { event ->
                val action = if (event.isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                audioManager.dispatchMediaKeyEvent(KeyEvent(action, event.keyCode))
            }
            true
        } catch (error: RuntimeException) {
            // Vendors have shipped AudioManager implementations that throw from
            // dispatch when no session exists. A failed key press must never
            // crash the app; the handler turns `false` into a truthful message.
            false
        }
    }
}
