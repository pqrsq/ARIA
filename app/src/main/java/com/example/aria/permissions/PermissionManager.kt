package com.example.aria.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * PHASE 2 — microphone permission, checked but never bundled with the install.
 *
 * POLICY (from the project rules): ARIA asks for access at the moment it is
 * used, never at startup. The manifest therefore *declares* `RECORD_AUDIO`
 * (Android requires the declaration) while nothing is requested until the user
 * taps the microphone.
 */
object Permissions {
    const val RECORD_AUDIO: String = android.Manifest.permission.RECORD_AUDIO
}

/**
 * Answers "may I open the microphone right now?".
 *
 * WHY AN INTERFACE: [com.example.aria.ui.AssistantViewModel] must stay
 * Android-free so its whole speech flow can be unit-tested on the JVM. The
 * ViewModel asks this question; the Android answer lives below.
 */
fun interface MicrophonePermissionChecker {
    fun hasMicrophonePermission(): Boolean
}

/** Real implementation, backed by the Android permission system. */
class AndroidPermissionChecker(context: Context) : MicrophonePermissionChecker {

    private val appContext = context.applicationContext

    override fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Permissions.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
}
