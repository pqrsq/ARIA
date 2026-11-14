package com.example.aria

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aria.ui.AssistantStatus
import com.example.aria.ui.AssistantUiState
import com.example.aria.ui.components.SETTINGS_BUTTON_TAG
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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun finalTranscript_showsYouSaidAndStatus() {
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.SUCCESS,
                        rawSpeech = "Turn the volume down",
                        normalizedText = "turn the volume down",
                        // PHASE 6: a parsed command with a real result, matching
                        // what the ViewModel produces after execution.
                        commandType = "VOLUME_DOWN",
                        message = "Media volume down to 47%."
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

    @Test
    fun speakingState_showsTheSpokenReplyAndStatus() {
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.SPEAKING,
                        rawSpeech = "Turn the volume down",
                        spokenReply = "I heard you.",
                        lastSpokenText = "I heard you."
                    )
                )
            }
        }

        composeRule.onNodeWithText("SPEAKING").assertIsDisplayed()
        composeRule.onNodeWithText("\u201CI heard you.\u201D").assertIsDisplayed()
        // The transcript stays visible while ARIA talks.
        composeRule.onNodeWithText("\u201CTurn the volume down\u201D").assertIsDisplayed()
    }

    @Test
    fun rejectedState_isNeutralAndExplainsTheRefusal() {
        // PHASE 6.5 (D10/D11): a deliberate refusal is neither COMPLETED nor
        // FAILED, so the label must be its own neutral wording.
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.REJECTED,
                        rawSpeech = "set the volume to 120 percent",
                        message = "Volume must be between 0 and 100 percent."
                    )
                )
            }
        }

        composeRule.onNodeWithText("NOT DONE").assertIsDisplayed()
        composeRule.onNodeWithText("Volume must be between 0 and 100 percent.")
            .assertIsDisplayed()
    }

    @Test
    fun pendingPermission_showsOpenSettingsButton() {
        // PHASE 6.5 (D2): the only actionable thing about a missing permission is
        // the button, so its presence is the contract worth pinning.
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.ERROR,
                        message = "ARIA needs Do Not Disturb access to change the ring volume.",
                        pendingSettingsAction =
                            com.example.aria.actions.SettingsActions.NOTIFICATION_POLICY_ACCESS
                    ),
                    onOpenSettings = {}
                )
            }
        }

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Open settings").assertIsDisplayed()
    }

    @Test
    fun noPendingPermission_hidesTheSettingsButton() {
        // The negative case matters too: a permanently visible button would be a
        // dead control on every successful command.
        composeRule.setContent {
            AriaTheme {
                StatusCard(state = AssistantUiState(status = AssistantStatus.SUCCESS))
            }
        }

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun unavailableTts_showsANoticeWithoutFailingTheResult() {
        composeRule.setContent {
            AriaTheme {
                StatusCard(
                    state = AssistantUiState(
                        status = AssistantStatus.SUCCESS,
                        rawSpeech = "hello",
                        isTtsAvailable = false,
                        ttsMessage = com.example.aria.voice.TtsMessages.ENGINE_UNAVAILABLE
                    )
                )
            }
        }

        composeRule.onNodeWithText("COMPLETED").assertIsDisplayed()
        composeRule.onNodeWithText("Text-to-Speech engine not available.").assertIsDisplayed()
    }
}
