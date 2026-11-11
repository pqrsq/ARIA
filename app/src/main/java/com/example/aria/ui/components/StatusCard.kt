package com.example.aria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.aria.R
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.AssistantUiState
import com.example.aria.ui.theme.AriaColors

/**
 * Shows the outcome of the latest interaction:
 *
 *   You said → Command → Status (→ Handler, only in debug mode)
 *
 * Every row is optional, so the same card serves "listening", "processing",
 * success and error without the screen needing separate composables.
 */
@Composable
fun StatusCard(
    state: AssistantUiState,
    modifier: Modifier = Modifier
) {
    val accent = state.status.accentColor()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AriaColors.Surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = accent, pulsing = state.status == AssistantStatus.LISTENING)
            Text(
                text = state.status.displayLabel(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(start = 10.dp)
            )
        }

        state.rawSpeech?.let { speech ->
            LabelledValue(
                // While listening the text is still changing, so the label says
                // so; after the final result it becomes a quotation.
                label = stringResource(
                    if (state.isLiveTranscript) R.string.label_hearing
                    else R.string.label_you_said
                ),
                value = if (state.isLiveTranscript) speech else "\u201C$speech\u201D"
            )
        }

        state.commandLabel?.let { label ->
            LabelledValue(
                label = stringResource(R.string.label_command),
                value = label
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AriaColors.TextSecondary
            )
        }

        if (state.debugMode) {
            DebugBlock(state = state)
        }
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = AriaColors.TextPrimary
        )
    }
}

/** Developer-only technical detail. Hidden unless debug mode is enabled. */
@Composable
private fun DebugBlock(state: AssistantUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AriaColors.Background)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stringResource(R.string.section_debug).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.Warning
        )
        DebugLine("raw speech", state.rawSpeech ?: "—")
        DebugLine("normalized", state.normalizedText ?: "—")
        DebugLine("command", state.commandType ?: "—")
        DebugLine("handler", state.handler ?: "—")
        DebugLine("result", state.status.name)
    }
}

@Composable
private fun DebugLine(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = AriaColors.TextTertiary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = AriaColors.TextSecondary,
            modifier = Modifier.weight(2f)
        )
    }
}

/** Status → label + colour mapping kept in one place. */
private fun AssistantStatus.displayLabel(): String = when (this) {
    AssistantStatus.IDLE -> "READY"
    AssistantStatus.LISTENING -> "LISTENING"
    AssistantStatus.PROCESSING -> "PROCESSING"
    AssistantStatus.SUCCESS -> "COMPLETED"
    AssistantStatus.ERROR -> "FAILED"
}

private fun AssistantStatus.accentColor(): Color = when (this) {
    AssistantStatus.IDLE -> AriaColors.TextTertiary
    AssistantStatus.LISTENING -> AriaColors.Listening
    AssistantStatus.PROCESSING -> AriaColors.Warning
    AssistantStatus.SUCCESS -> AriaColors.Success
    AssistantStatus.ERROR -> AriaColors.Error
}
