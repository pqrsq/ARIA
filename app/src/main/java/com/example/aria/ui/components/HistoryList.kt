package com.example.aria.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.aria.R
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.CommandRecord
import com.example.aria.ui.theme.AriaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight local history list.
 *
 * As of Phase 12 this is fed from the persisted history (`history/HistoryStore`,
 * JSON in `filesDir`) via `AssistantUiState.history`. The composable itself only
 * needs a List<CommandRecord>, which is what kept the storage choice free — the
 * plan mentioned here was DataStore/Room, and the decision went to a plain file.
 * R2 correction: this comment used to say Phase 12 "will" feed it.
 */
@Composable
fun HistoryList(
    records: List<CommandRecord>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (records.isEmpty()) {
            Text(
                text = stringResource(R.string.empty_history),
                style = MaterialTheme.typography.bodyMedium,
                color = AriaColors.TextTertiary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            records.forEach { record -> HistoryRow(record) }
        }
    }
}

@Composable
private fun HistoryRow(record: CommandRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AriaColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = timeFormatter.format(Date(record.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.TextTertiary
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.rawSpeech,
                style = MaterialTheme.typography.bodyMedium,
                color = AriaColors.TextPrimary
            )
            Text(
                // PHASE P1.6: the origin travels with the row, so a typed test
                // command can never be mistaken for a misrecognised one later.
                text = "${record.commandType} · ${record.source.name}",
                style = MaterialTheme.typography.labelSmall,
                color = AriaColors.TextSecondary
            )
        }

        ResultPill(record.status)
    }
}

@Composable
private fun ResultPill(status: AssistantStatus) {
    val (label, color) = when (status) {
        AssistantStatus.SUCCESS -> "OK" to AriaColors.Success
        AssistantStatus.ERROR -> "FAIL" to AriaColors.Error
        AssistantStatus.LISTENING -> "…" to AriaColors.Listening
        AssistantStatus.PROCESSING -> "…" to AriaColors.Warning
        AssistantStatus.IDLE -> "—" to AriaColors.TextTertiary
        // A captured transcript that is being read back is still a success.
        AssistantStatus.SPEAKING -> "OK" to AriaColors.Success
        // PHASE 6.5: refused on purpose — not a failure, so not FAIL.
        AssistantStatus.REJECTED -> "N/A" to AriaColors.TextSecondary
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
