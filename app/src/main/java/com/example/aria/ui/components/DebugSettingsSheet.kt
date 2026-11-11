package com.example.aria.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.aria.R
import com.example.aria.ui.theme.AriaColors

/**
 * Developer settings, reachable from the small gear icon.
 *
 * DECISION: debug mode lives one tap away behind a settings icon rather than
 * being hidden by a secret gesture. It shows no sensitive data (only what the
 * user just said), so the discoverability is worth more than the secrecy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugSettingsSheet(
    debugMode: Boolean,
    onDebugModeChanged: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AriaColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = "Developer",
                style = MaterialTheme.typography.titleMedium,
                color = AriaColors.TextPrimary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_mode),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AriaColors.TextPrimary
                    )
                    Text(
                        text = stringResource(R.string.debug_mode_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AriaColors.TextTertiary
                    )
                }
                Switch(
                    checked = debugMode,
                    onCheckedChange = onDebugModeChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AriaColors.Background,
                        checkedTrackColor = AriaColors.Accent
                    )
                )
            }

            TextButton(
                onClick = {
                    onClearHistory()
                    onDismiss()
                }
            ) {
                Text(
                    text = stringResource(R.string.reset_history),
                    color = AriaColors.Error
                )
            }

            Text(
                text = "ARIA 0.1.0 — Phase 1 (UI shell). Speech recognition is not "
                    + "implemented yet; the microphone reports this honestly.",
                style = MaterialTheme.typography.bodyMedium,
                color = AriaColors.TextTertiary
            )
        }
    }
}
