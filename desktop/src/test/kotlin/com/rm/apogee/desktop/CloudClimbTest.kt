package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.weather.*
import com.rm.apogee.game.CloudScene
import com.rm.apogee.render.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CloudClimbTest {
    @Test fun climbingIntoAPuffDoesNotRemoveItOnTheNextListing() {
        val body = SolarSystem.defaultSystem().body("terra")
        val config = WeatherConfig(clouds = CloudCover.HEAVY)
        val weather = Weather(body, config)
        val clouds = ArrayList<CloudShape>()
        val direction = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)
        val time = 100.0
        weather.clouds(direction, 20_000.0, time, clouds)
        val puff = clouds.flatMap { it.lobes }.minByOrNull { it.centre.distanceTo(direction.copy().mulInPlace(body.radius)) }
        assertNotNull("weather provides a cloud to climb through", puff)
        val centre = puff!!.centre
        val up = centre.normalized()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scene = CloudScene(body, config, QualityTier.HIGH, scope)
        // Run the actual worker listing synchronously to inspect each climb position deterministically.
        val list = scene.javaClass.getDeclaredMethod("list", Vec3::class.java, java.lang.Double.TYPE,
            java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE).also { it.isAccessible = true }
        val current = scene.javaClass.getDeclaredField("lobes").also { it.isAccessible = true }
        try {
            for (height in listOf(-puff.horizontal * 1.1, -puff.vertical, 0.0, puff.vertical, puff.horizontal * 1.1)) {
                val camera = centre.copy().addScaledInPlace(up, height)
                val lobes = list.invoke(scene, camera, time, 0.0, 20_000.0, false)
                current.set(scene, lobes)
                val items = ArrayList<RenderItem>()
                scene.append(Quat.identity(), time, items)
                assertTrue("same puff remains listed at climb offset $height", items.any { it.position.distanceTo(centre) < 1.0 })
            }
        } finally { scope.cancel() }
    }
}
