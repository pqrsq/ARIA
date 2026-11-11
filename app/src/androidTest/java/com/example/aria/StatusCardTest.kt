package com.example.aria

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.AssistantViewModel
import com.example.aria.ui.AssistantUiState
import com.example.aria.ui.components.StatusCard
import com.example.aria.ui.theme.AriaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE 2 instrumented test: a real transcript renders in the existing card.
 *
 * Like the Phase 1 history test, it pins the small, stable contract (labels and
 * text) rather than animations, so it keeps passing as the screen evolves.
 */
@RunWith(AndroidJUnit4::class)
class StatusCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun finalTranscript_showsYouSaidAndStatus() {
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.SUCCESS,
                        rawSpeech = "Turn the volume down",
                        normalizedText = "turn the volume down",
                        commandType = AssistantViewModel.NO_COMMAND_YET,
                        message = com.example.aria.voice.SpeechMessages.CAPTURED_NOT_EXECUTED
                    )
                )
            }
        }

        composeRule.onNodeWithText("COMPLETED").assertIsDisplayed()
        composeRule.onNodeWithText("\u201CTurn the volume down\u201D").assertIsDisplayed()
    }

    @Test
    fun errorState_explainsWhatHappenedWithoutATranscript() {
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.ERROR,
                        message = com.example.aria.voice.SpeechMessages.forError(
                            com.example.aria.voice.SpeechError.NO_MATCH
                        )
                    )
                )
            }
        }

        composeRule.onNodeWithText("FAILED").assertIsDisplayed()
        composeRule.onNodeWithText("I did not catch that. Tap the microphone and try again.")
            .assertIsDisplayed()
    }
}
