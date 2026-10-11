package com.rm.apogee.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

/** A window has no camera holes. */
@Composable
actual fun displayCutouts(): List<Rect> = emptyList()
