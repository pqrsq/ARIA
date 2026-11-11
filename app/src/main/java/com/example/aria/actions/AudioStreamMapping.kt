package com.example.aria.actions

import android.media.AudioManager
import com.example.aria.command.AudioStream

/**
 * PHASE 6 — the single place `AudioStream` becomes an Android stream id.
 *
 * [AudioStream]'s own KDoc promises exactly this ("the ActionExecutor does the
 * single mapping to Android constants in one place"), so this object keeps that
 * promise rather than scattering `STREAM_MUSIC` across four handlers.
 *
 * The constants are compile-time `Int`s in the Android SDK, so referencing them
 * from JVM tests is safe (`testOptions.unitTests.isReturnDefaultValues` stubs
 * *methods*, not constant fields).
 */
object AudioStreamMapping {

    /** `AudioStream.MEDIA → STREAM_MUSIC`, `RING → STREAM_RING`, `ALARM → STREAM_ALARM`. */
    fun toAndroidStream(stream: AudioStream): Int = when (stream) {
        AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
    }

    /** Human label used inside result messages ("Media volume …"). */
    fun label(stream: AudioStream): String = stream.label
}
