package com.example.aria.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Tiny factory so the ViewModel can receive constructor dependencies without
 * pulling in a DI framework.
 *
 * WHY NO HILT/KOIN (yet): Version 1 has a handful of collaborators. A factory
 * keeps the build fast and the project readable for someone new to Android.
 * When the dependency graph grows (Phase 6+), replacing this with a DI
 * container is a local change because screens only ever ask for a ViewModel.
 */
class AssistantViewModelFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AssistantViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AssistantViewModel() as T
    }
}

/** Convenience used by MainActivity. */
fun stateViewModelFactory(): ViewModelProvider.Factory = AssistantViewModelFactory()
