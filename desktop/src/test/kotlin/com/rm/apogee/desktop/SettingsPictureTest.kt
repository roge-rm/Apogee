package com.rm.apogee.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.ui.screens.SettingsScreen
import com.rm.apogee.ui.theme.ApogeeTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Settings on a desktop window, a landscape phone and an upright phone, each tab, to
 * build/settings-review.
 */
class SettingsPictureTest {
    private class Screen(val name: String, val width: Int, val height: Int, val density: Float)

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun pictures() {
        val out = File("../build/settings-review").apply { mkdirs() }
        val settings = GameSettings(Files.createTempDirectory("settings-review").toFile())
        val screens = listOf(
            Screen("desktop", 1920, 1080, 1.25f),
            Screen("phone-landscape", 2400, 1080, 2.625f),
            Screen("phone", 1080, 2400, 2.625f),
        )
        for (screen in screens) {
            val scene = ImageComposeScene(screen.width, screen.height, Density(screen.density)) {
                ApogeeTheme { SettingsScreen(settings, detectedTier = null, canScaleRender = true, controllerName = null) }
            }
            scene.render()
            // The tabs share the header's width; find it the way the screen lays it out.
            val d = screen.density
            val w = screen.width / d
            val wide = w >= 840f
            val column = minOf(w, if (wide) 1040f else 560f)
            val left = (w - column) / 2f + 32f
            val tabsWidth = column - 64f - 16f
            val tabY = 24f + 48f + 8f + 24f
            listOf("player", "controls", "display", "audio").forEachIndexed { i, tab ->
                val at = Offset((left + tabsWidth * (i + 0.5f) / 4f) * d, tabY * d)
                scene.sendPointerEvent(PointerEventType.Press, at)
                scene.sendPointerEvent(PointerEventType.Release, at)
                scene.render(500_000_000L * (i + 1))
                val image = scene.render(500_000_000L * (i + 1) + 400_000_000L)
                val file = File(out, "settings-${screen.name}-$tab.png")
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                assertTrue(file.length() > 0)
            }
            scene.close()
        }
    }
}
