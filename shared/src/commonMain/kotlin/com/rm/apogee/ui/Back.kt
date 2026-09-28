package com.rm.apogee.ui

import androidx.compose.runtime.Composable

/** Runs [onBack] for the system's back gesture or button while [enabled]: Android's back. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
