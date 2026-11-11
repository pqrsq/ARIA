package com.example.aria.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aria.R
import com.example.aria.ui.components.DebugSettingsSheet
import com.example.aria.ui.components.HistoryList
import com.example.aria.ui.components.MicrophoneButton
import com.example.aria.ui.components.SectionHeader
import com.example.aria.ui.components.StatusCard
import com.example.aria.ui.theme.AriaColors
import com.example.aria.ui.theme.AriaTheme
import com.example.aria.voice.SpeechMessages

/**
 * The one screen of Version 1.
 *
 * It is a *stateless-ish* composable: all data comes from [AssistantUiState]
 * and all user intent goes out through callbacks. That makes it previewable,
 * testable and independent of Android APIs.
 *
 * LAYOUT (top → bottom):
 *   header (title + gear)  →  microphone  →  latest result  →  history
 * The whole content scrolls so that small screens and large font sizes are
 * safe; the microphone stays near the top so it is always one tap away.
 *
 * PHASE 2 CHANGES (and only these):
 *  - the Phase-1-only "Simulate a command" button is gone;
 *  - tapping the microphone asks for `RECORD_AUDIO` when it is not yet held —
 *    never at startup;
 *  - the microphone is closed when the app leaves the foreground;
 *  - the result card now receives real transcripts.
 */
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionController = rememberMicrophonePermissionController { granted, permanentlyDenied ->
        viewModel.onMicrophonePermissionResult(granted, permanentlyDenied)
    }

    // The ViewModel only decides "permission is needed"; the dialog is shown
    // here, because launching it requires an Activity. One request per flag.
    LaunchedEffect(state.isAwaitingPermission) {
        if (state.isAwaitingPermission) permissionController.request()
    }

    // Privacy rule: never record while ARIA is in the background. ON_STOP also
    // fires on Home press and on screen lock.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onAppBackgrounded()
    }

    AssistantContent(
        state = state,
        onMicrophoneTapped = viewModel::onMicrophoneTapped,
        onOpenAppSettings = { context.startActivity(appDetailsIntent(context)) },
        onDebugModeChanged = viewModel::onDebugModeChanged,
        onClearHistory = viewModel::clearHistory,
        onTtsEnabledChanged = viewModel::onTtsEnabledChanged,
        modifier = modifier
    )
}

@Composable
private fun AssistantContent(
    state: AssistantUiState,
    onMicrophoneTapped: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDebugModeChanged: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onTtsEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    val isListening = state.status == AssistantStatus.LISTENING
    val isProcessing = state.status == AssistantStatus.PROCESSING

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(onSettingsClick = { showSettings = true })

            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = AriaColors.TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = AriaColors.TextTertiary
            )

            Spacer(Modifier.height(30.dp))

            MicrophoneButton(
                isListening = isListening,
                // Disabled while a transcript is being finalised (so the button
                // cannot queue two sessions) and while the permission dialog is
                // open (nothing to listen to yet).
                enabled = !isProcessing && !state.isAwaitingPermission,
                onClick = onMicrophoneTapped
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    state.isAwaitingPermission -> stringResource(R.string.home_waiting_permission)
                    isListening -> stringResource(R.string.home_listening)
                    isProcessing -> stringResource(R.string.home_processing)
                    // PHASE 3: the visible half of the speaking state.
                    state.isSpeaking -> stringResource(R.string.speaking)
                    else -> stringResource(R.string.home_tap_to_speak)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    isListening -> AriaColors.Listening
                    state.isSpeaking -> AriaColors.Accent
                    else -> AriaColors.TextSecondary
                },
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    state.isAwaitingPermission -> stringResource(R.string.home_permission_hint)
                    isListening -> stringResource(R.string.home_tap_to_stop)
                    else -> stringResource(R.string.home_mic_hint)
                },
                style = MaterialTheme.typography.labelSmall,
                color = AriaColors.TextTertiary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(22.dp))

            AnimatedVisibility(
                visible = state.hasResult,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    StatusCard(
                        state = state,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Only a permanently blocked permission needs the detour
                    // through system settings; a plain refusal just needs
                    // another tap.
                    if (state.isPermissionBlocked) {
                        TextButton(onClick = onOpenAppSettings) {
                            Text(
                                text = stringResource(R.string.open_app_settings),
                                style = MaterialTheme.typography.labelSmall,
                                color = AriaColors.Accent
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            SectionHeader(title = stringResource(R.string.section_history))
            HistoryList(records = state.history)

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.version_footer),
                style = MaterialTheme.typography.labelSmall,
                color = AriaColors.TextTertiary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
        }

        if (showSettings) {
            DebugSettingsSheet(
                debugMode = state.debugMode,
                onDebugModeChanged = onDebugModeChanged,
                onClearHistory = onClearHistory,
                onDismiss = { showSettings = false },
                ttsEnabled = state.ttsEnabled,
                isTtsAvailable = state.isTtsAvailable,
                onTtsEnabledChanged = onTtsEnabledChanged
            )
        }
    }
}

/**
 * Opens ARIA's page in the system settings — the only place where a blocked
 * microphone permission can be granted again.
 */
private fun appDetailsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun Header(onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = AriaColors.Accent, shape = CircleShape)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "ARIA",
            style = MaterialTheme.typography.labelMedium,
            color = AriaColors.TextSecondary,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSettingsClick) {
            Icon(
                // Settings gear comes from Material's core icon set (material-icons-core),
                // which is small; the heavy "extended" set is intentionally not a dependency.
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = AriaColors.TextTertiary
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF07080D)
@Composable
private fun AssistantContentPreview() {
    val previewState = AssistantUiState(
        status = AssistantStatus.LISTENING,
        rawSpeech = "Turn the volume",
        normalizedText = null,
        message = null,
        debugMode = true,
        history = listOf(
            CommandRecord(
                id = 1,
                timestampMillis = 0L,
                rawSpeech = "Turn the volume down",
                commandType = AssistantViewModel.NO_COMMAND_YET,
                handler = AssistantViewModel.PHASE_2_HANDLER_LABEL,
                status = AssistantStatus.SUCCESS,
                message = SpeechMessages.CAPTURED_NOT_EXECUTED
            )
        )
    )
    AriaTheme {
        Box(Modifier.background(AriaColors.Background)) {
            AssistantContent(
                state = previewState,
                onMicrophoneTapped = {},
                onOpenAppSettings = {},
                onDebugModeChanged = {},
                onClearHistory = {},
                onTtsEnabledChanged = {}
            )
        }
    }
}
