package com.example.aria.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aria.R
import com.example.aria.ui.components.DebugSettingsSheet
import com.example.aria.ui.components.HistoryList
import com.example.aria.ui.components.MicrophoneButton
import com.example.aria.ui.components.SectionHeader
import com.example.aria.ui.components.StatusCard
import com.example.aria.ui.theme.AriaColors
import com.example.aria.ui.theme.AriaTheme

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
 */
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AssistantContent(
        state = state,
        onMicrophoneTapped = viewModel::onMicrophoneTapped,
        onSimulateCommand = viewModel::simulateVoiceCommand,
        onDebugModeChanged = viewModel::onDebugModeChanged,
        onClearHistory = viewModel::clearHistory,
        modifier = modifier
    )
}

@Composable
private fun AssistantContent(
    state: AssistantUiState,
    onMicrophoneTapped: () -> Unit,
    onSimulateCommand: () -> Unit,
    onDebugModeChanged: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSettings by remember { mutableStateOf(false) }
    val isListening = state.status == AssistantStatus.LISTENING

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
                enabled = !isListening,
                onClick = onMicrophoneTapped
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = if (isListening) stringResource(R.string.home_listening)
                else stringResource(R.string.home_tap_to_speak),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isListening) AriaColors.Listening else AriaColors.TextSecondary,
                textAlign = TextAlign.Center
            )

            // --- Phase 1 scaffold control -----------------------------------
            // This button exists ONLY because speech input is not built yet.
            // It is deleted in Phase 2. It drives the exact same state machine
            // (LISTENING → PROCESSING → SUCCESS) that the real pipeline uses,
            // so the UI can be verified on a device today.
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onSimulateCommand,
                enabled = !isListening
            ) {
                Text(
                    text = "Simulate a command (Phase 1 only)",
                    style = MaterialTheme.typography.labelSmall,
                    color = AriaColors.TextTertiary
                )
            }
            // ---------------------------------------------------------------

            Spacer(Modifier.height(22.dp))

            AnimatedVisibility(
                visible = state.hasResult,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                StatusCard(
                    state = state,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(28.dp))

            SectionHeader(title = stringResource(R.string.section_history))
            HistoryList(records = state.history)

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Version 1 · Phase 1 of 16 — UI shell only",
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
                onDismiss = { showSettings = false }
            )
        }
    }
}

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
        status = AssistantStatus.SUCCESS,
        rawSpeech = "Turn the volume down",
        normalizedText = "turn the volume down",
        commandType = "VOLUME_DOWN",
        commandLabel = "Volume Down",
        handler = "VolumeActionHandler",
        message = "Simulated — no Android action was performed.",
        debugMode = true,
        history = listOf(
            CommandRecord(
                id = 1,
                timestampMillis = 0L,
                rawSpeech = "Turn the volume down",
                commandType = "VOLUME_DOWN",
                status = AssistantStatus.SUCCESS
            )
        )
    )
    AriaTheme {
        Box(Modifier.background(AriaColors.Background)) {
            AssistantContent(
                state = previewState,
                onMicrophoneTapped = {},
                onSimulateCommand = {},
                onDebugModeChanged = {},
                onClearHistory = {}
            )
        }
    }
}
