package com.example.aria.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.example.aria.permissions.Permissions

/**
 * UI-layer owner of the microphone permission dialog.
 *
 * WHY THIS IS NOT IN THE VIEWMODEL
 * Asking for a permission needs an `Activity`, which a ViewModel must not hold.
 * The ViewModel therefore only *decides* that permission is needed (by setting
 * [AssistantUiState.isAwaitingPermission]); this controller performs the system
 * dialog and reports the answer back. One tap in the UI, one system prompt.
 *
 * The activity-result contract survives configuration changes, so rotating the
 * phone while the dialog is open does not lose the answer.
 */
@Composable
fun rememberMicrophonePermissionController(
    onResult: (granted: Boolean, permanentlyDenied: Boolean) -> Unit
): MicrophonePermissionController {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)

    // Consecutive refusals. Used to distinguish "tapped Deny" (ask again next
    // tap) from "tapped Deny twice / Don't ask again" (send the user to
    // Settings). Counting is more reliable across OEMs than
    // shouldShowRequestPermissionRationale, which is ambiguous before and just
    // after the very first prompt on Android 11+.
    var refusalCount by rememberSaveable { mutableStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refusalCount = 0
            currentOnResult(true, false)
        } else {
            refusalCount += 1
            currentOnResult(false, refusalCount >= MAX_REFUSALS)
        }
    }

    return remember(context, launcher) {
        MicrophonePermissionController(
            requestPermission = {
                // Belt and braces: some OEM builds return "granted" without
                // ever showing a dialog, in which case no callback arrives.
                if (hasMicrophonePermission(context)) {
                    refusalCount = 0
                    currentOnResult(true, false)
                } else {
                    launcher.launch(Permissions.RECORD_AUDIO)
                }
            }
        )
    }
}

/**
 * Small handle used by the screen. Nothing else in the app may launch a
 * permission request.
 */
class MicrophonePermissionController internal constructor(
    private val requestPermission: () -> Unit
) {
    /** Opens the system dialog (or reports an already-granted result). */
    fun request() = requestPermission()
}

/** Two refusals in a row ⇒ assume "don't ask again" and explain Settings. */
private const val MAX_REFUSALS = 2

private fun hasMicrophonePermission(context: Context): Boolean =
    ActivityCompat.checkSelfPermission(
        context,
        Permissions.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
