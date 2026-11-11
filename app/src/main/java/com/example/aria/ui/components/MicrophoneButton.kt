package com.example.aria.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.aria.R
import com.example.aria.ui.theme.AriaColors
import com.example.aria.ui.theme.AriaIcons

/**
 * The single, big tap target of the app.
 *
 * ACCESSIBILITY: the button carries a contentDescription so TalkBack announces
 * it, and it keeps Material's 48dp minimum touch target.
 *
 * VISUAL FEEDBACK: while listening, a soft pulse runs. It is intentionally the
 * ONLY animation in the app — the user must always be able to tell whether the
 * microphone is open.
 */
@Composable
fun MicrophoneButton(
    isListening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.10f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mic-pulse-scale"
    )

    val ringColor by animateColorAsState(
        targetValue = if (isListening) AriaColors.Listening else AriaColors.AccentSoft,
        animationSpec = tween(durationMillis = 300),
        label = "mic-ring-color"
    )

    val label = stringResource(R.string.cd_microphone)
    val tapHint = if (isListening) stringResource(R.string.home_listening)
    else stringResource(R.string.home_tap_to_speak)

    Box(
        modifier = modifier
            .size(168.dp)
            .scale(if (isListening) pulseScale else 1f),
        contentAlignment = Alignment.Center
    ) {
        // Outer halo
        Box(
            modifier = Modifier
                .size(168.dp)
                .background(
                    brush = Brush.radialGradient(
                        listOf(ringColor.copy(alpha = 0.35f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        // Ring
        Box(
            modifier = Modifier
                .size(124.dp)
                .background(color = AriaColors.Surface, shape = CircleShape)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(ringColor.copy(alpha = 0.55f), Color.Transparent)
                    ),
                    shape = CircleShape
                )
        )

        // Icon surface + click handling
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(92.dp)
                .background(
                    brush = Brush.verticalGradient(
                        listOf(
                            if (isListening) AriaColors.Listening else AriaColors.Accent,
                            if (isListening) AriaColors.Listening.copy(alpha = 0.75f)
                            else AriaColors.Accent.copy(alpha = 0.75f)
                        )
                    ),
                    shape = CircleShape
                )
                .semantics { contentDescription = "$label. $tapHint" }
                .clickable(
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AriaIcons.Mic,
                contentDescription = null,
                tint = AriaColors.Background,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}
