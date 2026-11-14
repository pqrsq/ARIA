package com.example.aria.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.aria.R
import com.example.aria.ui.theme.AriaColors

/** Test tags, so UI tests match on identity rather than on wording. */
const val DEBUG_TEXT_FIELD_TAG: String = "debug_text_field"
const val DEBUG_TEXT_SUBMIT_TAG: String = "debug_text_submit"

/**
 * PHASE P1.6 — debug-only text input.
 *
 * ── WHY THIS EXISTS ─────────────────────────────────────────────────────────
 * Speech recognition on the target device is unreliable (ambient noise, a poor
 * microphone), and that makes debugging cruel: a wrong result can be the parser,
 * or it can be a misrecognition. Typing a command removes the recogniser from
 * the picture entirely, so a defect is reproducible in one attempt.
 *
 * ── WHAT IT IS NOT ──────────────────────────────────────────────────────────
 * Not a second command path. [onSubmit] is wired by the screen to
 * `AssistantViewModel.onSubmitTextInput`, which calls the very same pipeline a
 * final speech result calls. V1 stays voice-first: this composable is only ever
 * placed on screen when debug mode is on.
 *
 * ── NO MICROPHONE ───────────────────────────────────────────────────────────
 * Nothing here touches RECORD_AUDIO, the permission check, or the recogniser, so
 * it works on a device where voice does not.
 */
@Composable
fun DebugTextInput(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // Local, unhoisted text state: the field is scaffolding for a test, not part
    // of the app's model, so it must never enter AssistantUiState (which is
    // snapshotted for duplicate suppression and would then need clearing on every
    // voice interaction).
    var text by remember { mutableStateOf("") }

    // Submit if — and only if — there is something to submit. Clearing after a
    // successful submit is the affordance: the next command can be typed
    // immediately, and the field never looks like it ignored the last one.
    val submit: () -> Unit = {
        if (text.isNotBlank()) {
            onSubmit(text)
            text = ""
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .testTag(DEBUG_TEXT_FIELD_TAG),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(R.string.debug_text_hint)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AriaColors.TextPrimary,
                unfocusedTextColor = AriaColors.TextPrimary,
                focusedBorderColor = AriaColors.Accent,
                unfocusedBorderColor = AriaColors.TextTertiary,
                focusedLabelColor = AriaColors.Accent,
                unfocusedLabelColor = AriaColors.TextTertiary,
                cursorColor = AriaColors.Accent
            ),
            // Done/Go on the keyboard submits: on a phone the keyboard covers the
            // bottom of the screen, so reaching for a button is the awkward route.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )

        TextButton(
            onClick = submit,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.testTag(DEBUG_TEXT_SUBMIT_TAG)
        ) {
            Text(
                text = stringResource(R.string.debug_text_submit),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled && text.isNotBlank()) AriaColors.Accent
                else AriaColors.TextTertiary
            )
        }
    }
}

/**
 * PHASE P1.6 — the debug text field, rendered only while debug mode is on.
 *
 * WHY THE VISIBILITY RULE LIVES HERE AND NOT IN THE SCREEN: "hidden in normal
 * use" is requirement 1 of the phase, and a rule that only exists as an `if` in
 * the screen cannot be tested in isolation. Keeping the one rule in a composable
 * means both the screen and the instrumented test exercise the same code, rather
 * than the test re-implementing the condition and agreeing with itself.
 */
@Composable
fun DebugTextInputIfDebug(
    debugMode: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    AnimatedVisibility(
        visible = debugMode,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(modifier = modifier) {
            Spacer(Modifier.height(18.dp))
            DebugTextInput(onSubmit = onSubmit, enabled = enabled)
        }
    }
}
