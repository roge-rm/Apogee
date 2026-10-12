package com.rm.apogee.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Whether sending a craft or world saves a file (desktop, browser) rather than sharing it (phone). */
val LocalSavesToFiles = staticCompositionLocalOf { false }
