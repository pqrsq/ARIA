package com.example.aria

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aria.ui.AssistantScreen
import com.example.aria.ui.AssistantViewModel
import com.example.aria.ui.stateViewModelFactory
import com.example.aria.ui.theme.AriaTheme

/**
 * The app has exactly one Activity. Everything is Compose.
 *
 * WHY SINGLE-ACTIVITY:
 * Compose apps typically use one Activity and navigate between composables.
 * This keeps the Android plumbing minimal and means later phases (history
 * screen, settings screen) are added as composables, not Activities.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the status/navigation bars for a clean, modern look.
        enableEdgeToEdge()

        setContent {
            AriaTheme {
                val viewModel: AssistantViewModel = viewModel(
                    factory = stateViewModelFactory()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AssistantScreen(viewModel = viewModel)
                }
            }
        }
    }
}
