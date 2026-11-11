package com.example.aria.permissions

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings

/**
 * PHASE 6 — "may ARIA change sound modes right now?".
 *
 * ── WHY A SEPARATE PORT FROM THE RINGER ─────────────────────────────────────
 * `RingerController` performs the change; this answers whether the change is
 * *allowed*. Two different system services, two different questions, and the
 * Phase 6 rules treat the permission answer specially: it becomes
 * [com.example.aria.actions.ExecutionResult.RequiresPermission] carrying a
 * settings action, and ARIA must never act on it by itself.
 *
 * Keeping it a port also means the Do-Not-Disturb branch is testable on the JVM
 * (a fake answers `false`), which matters because that branch is the one that
 * must never be bypassed.
 */
fun interface PolicyAccessChecker {
    /** True when the user has granted Do Not Disturb (notification policy) access. */
    fun canChangeSoundModes(): Boolean
}

/**
 * PHASE 6 — notification-policy access, plus the screen that grants it.
 *
 * ── THE RULE, RESTATED ─────────────────────────────────────────────────────
 * Access can only be granted **by the user, in system settings**. There is no API
 * to request it and no intent to auto-approve it, and ARIA does not want one: it
 * reports the missing permission and names the screen. Nothing here launches
 * anything — that is a decision for the UI layer, made explicitly by the user.
 */
class NotificationPolicyAccessChecker(
    context: Context
) : PolicyAccessChecker {

    private val applicationContext = context.applicationContext

    private val notificationManager: NotificationManager
        get() = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

    override fun canChangeSoundModes(): Boolean =
        // Not all API-26 devices expose this reliably; a throw is treated as
        // "not granted", which fails safe: ARIA asks instead of assuming.
        try {
            notificationManager.isNotificationPolicyAccessGranted
        } catch (error: RuntimeException) {
            false
        }

    companion object {
        /** The settings screen where the user grants it. */
        const val SETTINGS_ACTION: String = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
    }
}
