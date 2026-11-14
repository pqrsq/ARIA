package com.example.aria

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.CommandRecord
import com.example.aria.ui.components.HistoryList
import com.example.aria.ui.theme.AriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First instrumented UI test (runs on a device or emulator).
 *
 * It deliberately tests a small, stable piece (the history list) rather than
 * animations, so it stays green while the screen evolves in later phases.
 */
@RunWith(AndroidJUnit4::class)
class AssistantScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun historyRow_showsSpeechAndCommandType() {
        composeRule.setContent {
            AriaTheme {
                HistoryList(
                    records = listOf(
                        CommandRecord(
                            id = 1L,
                            timestampMillis = 1_700_000_000_000L,
                            rawSpeech = "Turn the volume down",
                            commandType = "VOLUME_DOWN",
                            status = AssistantStatus.SUCCESS
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("Turn the volume down").assertIsDisplayed()
        composeRule.onNodeWithText("VOLUME_DOWN").assertIsDisplayed()
        composeRule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun emptyHistory_showsHint() {
        composeRule.setContent {
            AriaTheme { HistoryList(records = emptyList()) }
        }

        composeRule
            .onNodeWithText("No commands yet. Tap the microphone and speak.")
            .assertIsDisplayed()
    }
}
