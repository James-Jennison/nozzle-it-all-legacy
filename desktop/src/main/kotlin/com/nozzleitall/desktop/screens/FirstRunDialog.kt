package com.nozzleitall.desktop.screens

import androidx.compose.runtime.Composable
import com.nozzleitall.desktop.AppState

/** Compatibility entry point for older callers; the complete onboarding flow lives in [SetupWizardDialog]. */
@Deprecated("Use SetupWizardDialog")
@Composable
fun FirstRunDialog(state: AppState) {
    SetupWizardDialog(state)
}
