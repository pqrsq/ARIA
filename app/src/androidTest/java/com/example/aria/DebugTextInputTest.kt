package com.example.aria

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.aria.ui.components.DEBUG_TEXT_FIELD_TAG
import com.example.aria.ui.components.DEBUG_TEXT_SUBMIT_TAG
import com.example.aria.ui.components.DebugTextInput
import com.example.aria.ui.components.DebugTextInputIfDebug
import com.example.aria.ui.theme.AriaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHASE P1.6 — the debug text field, from the UI's side.
 *
 * WHAT IS PINNED HERE: not the styling, but the three contracts that make the
 * affordance safe:
 *  1. it is INVISIBLE unless debug mode is on (V1 stays voice-first),
 *  2. submitting really invokes the single callback it is given — the screen
 *     wires that to the same ViewModel entry point speech uses,
 *  3. blank input never submits and the field clears after a real submit.
 */
@RunWith(AndroidJUnit4::class)
class DebugTextInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun debugOff_hidesTheFieldEntirely() {
        composeRule.setContent {
            AriaTheme {
                // The SAME composable the screen uses, so this asserts the real
                // visibility rule instead of a copy of it. "Off" must mean
                // absent, not merely disabled or transparent.
                DebugTextInputIfDebug(debugMode = false, onSubmit = {})
            }
        }

        composeRule.onNodeWithTag(DEBUG_TEXT_FIELD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).assertDoesNotExist()
    }

    @Test
    fun debugOn_showsTheField() {
        composeRule.setContent {
            AriaTheme {
                DebugTextInputIfDebug(debugMode = true, onSubmit = {})
            }
        }

        composeRule.onNodeWithTag(DEBUG_TEXT_FIELD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).assertIsDisplayed()
    }

    @Test
    fun submit_invokesTheCallbackWithTheTypedText_andClearsTheField() {
        var submitted: String? = null
        composeRule.setContent {
            AriaTheme {
                DebugTextInput(onSubmit = { submitted = it })
            }
        }

        composeRule.onNodeWithTag(DEBUG_TEXT_FIELD_TAG).performTextInput("turn the volume down")
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).performClick()

        assertEquals("turn the volume down", submitted)
        // Cleared, so the same command can be retyped at once. Asserted on the
        // button being disabled again, which is the observable consequence and
        // avoids depending on how a TextField exposes its editable text.
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun blankInput_neverSubmits() {
        var calls = 0
        composeRule.setContent {
            AriaTheme {
                DebugTextInput(onSubmit = { calls++ })
            }
        }

        // Whitespace only: the button is disabled and a click must not fire.
        composeRule.onNodeWithTag(DEBUG_TEXT_FIELD_TAG).performTextInput("   ")
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).performClick()

        assertEquals(0, calls)
    }

    @Test
    fun theFieldIsDisabledWhileAnExecutionIsInFlight() {
        // The pipeline is single-flight: a submit while a command is executing
        // would cancel that command's result. The screen passes enabled = false
        // in that window, exactly like the microphone button.
        var calls = 0
        composeRule.setContent {
            AriaTheme {
                DebugTextInput(onSubmit = { calls++ }, enabled = false)
            }
        }

        composeRule.onNodeWithTag(DEBUG_TEXT_FIELD_TAG).performTextInput("turn the volume up")
        composeRule.onNodeWithTag(DEBUG_TEXT_SUBMIT_TAG).performClick()

        assertEquals(0, calls)
    }
}
