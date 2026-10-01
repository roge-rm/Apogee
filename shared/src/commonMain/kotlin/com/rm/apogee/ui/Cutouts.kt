package com.rm.apogee.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

/**
 * Where the screen's camera holes and notches are, in window pixels. Empty where there are none,
 * or none we can be told about, as in a browser.
 */
@Composable
expect fun displayCutouts(): List<Rect>
