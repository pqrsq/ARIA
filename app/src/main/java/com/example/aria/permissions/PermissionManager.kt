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

/**
 * PHASE 6 — Do Not Disturb (notification policy) access.
 *
 * ── WHY IT LIVES NEXT TO THE MICROPHONE CHECK ───────────────────────────────
 * Both are "may ARIA do this?" questions, and the project keeps every permission
 * decision in one package so an audit only has to read one directory. The two are
 * deliberately *not* merged into one class: microphone access is a runtime
 * permission granted through a dialog, while policy access is a settings toggle
 * with no request API at all — conflating them would invite the very
 * auto-granting the project rules forbid.
 *
 * ── WHY A FUNCTION RETURNING A `fun interface` ──────────────────────────────
 * The port ([PolicyAccessChecker]) stays a dependency-free interface so
 * `SoundModeActionHandler` can be unit-tested with a fake, while production code
 * gets a one-liner. Nothing here ever *requests* or *changes* the grant: Android
 * offers no such API, and ARIA only reports what the user decides.
 */
object DndAccessChecker {

    /** Builds the production checker bound to [context]'s application context. */
    fun create(context: Context): PolicyAccessChecker = NotificationPolicyAccessChecker(context)

    /**
     * Whether Do Not Disturb access is currently granted.
     *
     * Shortcut for callers that need the answer once; fails safe (false) on the
     * OEM devices whose `NotificationManager` throws instead of answering.
     */
    fun isGranted(context: Context): Boolean = create(context).canChangeSoundModes()
}
