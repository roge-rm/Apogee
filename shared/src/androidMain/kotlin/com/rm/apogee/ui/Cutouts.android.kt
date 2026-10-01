package com.rm.apogee.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat

@Composable
actual fun displayCutouts(): List<Rect> {
    val view = LocalView.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Reading the insets here recomposes this when they change: on rotation, or when the window
    // starts or stops reaching into the cutout.
    val insets = WindowInsets.displayCutout
    val key = listOf(
        insets.getLeft(density, direction), insets.getTop(density),
        insets.getRight(density, direction), insets.getBottom(density),
        LocalConfiguration.current.orientation,
    )
    return remember(view, key) {
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty()
            .map { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
    }
}
