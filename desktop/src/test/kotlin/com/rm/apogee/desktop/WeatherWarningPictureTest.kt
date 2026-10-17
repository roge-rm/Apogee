package com.rm.apogee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.game.HudState
import com.rm.apogee.game.WeatherWarnings
import com.rm.apogee.ui.components.StatusActions
import com.rm.apogee.ui.components.StatusRow
import com.rm.apogee.ui.theme.ApogeeTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The weather warning chip as the status row shows it, one picture per kind and level, to
 * build/weather-warning-review.
 */
class WeatherWarningPictureTest {
    private fun warning(kind: WeatherWarnings.Kind, distance: Double, seconds: Double, danger: Boolean) =
        WeatherWarnings.Warning(kind, 1L, distance, seconds, danger, Vec3.unitY(), 10_000.0)

    @Test
    fun pictures() {
        val out = File("../build/weather-warning-review").apply { mkdirs() }
        val shots = listOf(
            "tornado" to warning(WeatherWarnings.Kind.TORNADO, 3_200.0, 0.0, false),
            "tornado-danger" to warning(WeatherWarnings.Kind.TORNADO, 600.0, 0.0, true),
            "waterspout" to warning(WeatherWarnings.Kind.WATERSPOUT, 5_400.0, 0.0, false),
            "hurricane" to warning(WeatherWarnings.Kind.HURRICANE, 120_000.0, 0.0, false),
            "hurricane-danger" to warning(WeatherWarnings.Kind.HURRICANE, 23_000.0, 0.0, true),
            "tsunami" to warning(WeatherWarnings.Kind.TSUNAMI, 900_000.0, 240.0 + 600.0, false),
            "tsunami-danger" to warning(WeatherWarnings.Kind.TSUNAMI, 900_000.0, 0.0, true),
            "quake" to warning(WeatherWarnings.Kind.QUAKE, 24_000.0, 7.0, false),
            "quake-danger" to warning(WeatherWarnings.Kind.QUAKE, 24_000.0, 0.0, true),
            "aftershocks" to warning(WeatherWarnings.Kind.AFTERSHOCKS, 24_000.0, 0.0, false),
            "dust" to warning(WeatherWarnings.Kind.DUST, 12_000.0, 0.0, false),
            "dust-danger" to warning(WeatherWarnings.Kind.DUST, 0.0, 0.0, true),
            "dust-storm" to warning(WeatherWarnings.Kind.DUST_STORM, 0.0, 0.0, false),
            "blizzard" to warning(WeatherWarnings.Kind.BLIZZARD, 18_000.0, 0.0, false),
            "blizzard-danger" to warning(WeatherWarnings.Kind.BLIZZARD, 0.0, 0.0, true),
            "hail" to warning(WeatherWarnings.Kind.HAIL, 4_200.0, 0.0, false),
            "hail-danger" to warning(WeatherWarnings.Kind.HAIL, 0.0, 0.0, true),
            // Struck by lightning in heavy hail, crewed: the outage beside the warning.
            "struck" to warning(WeatherWarnings.Kind.HAIL, 0.0, 0.0, true),
        )
        for ((name, w) in shots) {
            val hud = HudState().also { it.weatherWarning = w }
            if (name == "struck") hud.power = HudState.PowerReadout(
                charge = 30f, capacity = 200f, net = 0f, powered = true, needsSignal = false,
                signal = com.rm.apogee.core.world.Signal.NONE, relays = 0, controllable = true, deployed = false, stunned = 5f,
            )
            val scene = ImageComposeScene(520, 120, Density(2f)) {
                ApogeeTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF5A6E82))) {
                        StatusRow(hud, null, StatusActions(), fadedAlpha = 1f, detailHeight = 200.dp, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
                    }
                }
            }
            val image = scene.render()
            val file = File(out, "weather-warning-$name.png")
            file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
            assertTrue(file.length() > 0)
            println("$name: ${w.text} -> ${file.canonicalPath}")
        }
    }
}
