package com.rm.apogee.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.game.*
import com.rm.apogee.ui.screens.FlightScreen
import com.rm.apogee.ui.theme.ApogeeTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File

class MapSelectionPictureTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun cardsFitPhoneAndLandscapeHud() {
        val out = File("../build/map-selection-review").apply { mkdirs() }
        val orbit = MapPick("path", "Orbit point", MapPickKind.PATH, Vec3(), time = 1000.0, altitude = 256_000.0, speed = 2300.0)
        val world = MapPick("world:terra", "Terra", MapPickKind.WORLD, Vec3())
        val cases = listOf(
            "orbit" to MapSelection(selected = orbit, distance = 110_000.0, inSeconds = 340.0, actions = listOf(MapAction.ADD_BURN, MapAction.WARP)),
            "world" to MapSelection(selected = world, distance = 256_000.0, actions = listOf(MapAction.FOCUS)),
            "choices" to MapSelection(choices = listOf(world, orbit, MapPick("craft:1", "Wayfarer", MapPickKind.CRAFT, Vec3()))),
        )
        for ((width, height) in listOf(1080 to 2400, 2400 to 1080)) for ((name, selection) in cases) {
            val hud = HudState().apply { connecting = false; surfaceReady = true; mapMode = true; mapSelection = selection }
            val scene = ImageComposeScene(width, height, Density(2.625f)) {
                ApogeeTheme {
                    FlightScreen(hud, 1f, false, false, onThrottleChange = {}, onAttitude = { _, _ -> }, onRoll = {},
                        onStage = {}, onToggleSas = {}, onToggleBrakes = {}, onToggleMap = {}, onJoin = {}, onExit = {})
                }
            }
            try {
                scene.render()
                val image = scene.render(500_000_000L)
                File(out, "$name-${width}x$height.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally { scene.close() }
        }
    }
}
