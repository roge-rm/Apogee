package com.rm.apogee.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect

/** The screen's camera holes and notches, in window pixels. Empty if unknown, as in a browser. */
@Composable
expect fun displayCutouts(): List<Rect>
