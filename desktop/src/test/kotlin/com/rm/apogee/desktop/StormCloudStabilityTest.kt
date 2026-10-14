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

class StormCloudStabilityTest {
    @Test fun nearAndFarListingsAgreeOnStormIdentityGeometryAndOpacityDuringAClimb() {
        val body = SolarSystem.defaultSystem().body("terra")
        val config = WeatherConfig(intensity = WeatherIntensity.WILD, clouds = CloudCover.HEAVY)
        val weather = Weather(body, config)
        val shapes = ArrayList<CloudShape>()
        val home = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)
        val time = 100.0
        weather.clouds(home, 80_000.0, time, shapes, 140_000.0, .75)
        val storm = shapes.firstOrNull { it.type == CloudType.CUMULONIMBUS }
        assertNotNull("test sky contains a storm", storm)
        val centre = storm!!.lobes.first().centre
        val east = Vec3.unitY().cross(centre).normalizeInPlace()
        val camera = centre.copy().addScaledInPlace(east, 17_000.0 + storm.lobes.first().horizontal)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scene = CloudScene(body, config, QualityTier.MEDIUM, scope)
        val list = scene.javaClass.getDeclaredMethod("list", Vec3::class.java, java.lang.Double.TYPE,
            java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE).also { it.isAccessible = true }
        val field = scene.javaClass.getDeclaredField("lobes").also { it.isAccessible = true }
        fun value(lobe: Any, name: String) = lobe.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(lobe)
        try {
            val near = list.invoke(scene, camera, time, 0.0, 20_000.0, false) as List<*>
            val far = list.invoke(scene, camera, time, 14_000.0, 45_000.0, false) as List<*>
            val nearByKey = near.filterNotNull().associateBy { value(it, "key") as Long }
            val farByKey = far.filterNotNull().associateBy { value(it, "key") as Long }
            val shared = nearByKey.keys.intersect(farByKey.keys)
            assertTrue("overlapping near/far storm lobes checked", shared.size > 5)
            for (key in shared) {
                val a = nearByKey.getValue(key); val b = farByKey.getValue(key)
                val alpha = (value(a, "colour") as FloatArray)[3]
                assertEquals("opacity at near/far handoff", alpha, (value(b, "colour") as FloatArray)[3], 1e-6f)
                assertTrue("same lobe geometry", (value(a, "centre") as Vec3).distanceTo(value(b, "centre") as Vec3) < .001)
            }
            field.set(scene, near)
            val items = ArrayList<RenderItem>()
            scene.append(Quat.identity(), time, items, 0)
            assertEquals("unique visual identities", items.size, items.map { it.key }.toSet().size)
            assertTrue(items.all { it.key != 0L })
            assertTrue("storms retain strict transparent order", items.any { it.orderedTransparency && it.shape is CloudPuff })
        } finally { scope.cancel() }
    }
    @Test fun stormClimbKeepsMeshesAndOpacityContinuousAcrossWorkerAndBudgetUpdates() {
        val body = SolarSystem.defaultSystem().body("terra")
        val config = WeatherConfig(intensity = WeatherIntensity.WILD, clouds = CloudCover.HEAVY)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scene = CloudScene(body, config, QualityTier.LOW, scope)
        val direction = SolarSystem.surfaceDirection(SolarSystem.PAD_LATITUDE, SolarSystem.PAD_LONGITUDE)
        val list = scene.javaClass.getDeclaredMethod("list", Vec3::class.java, java.lang.Double.TYPE,
            java.lang.Double.TYPE, java.lang.Double.TYPE, java.lang.Boolean.TYPE).also { it.isAccessible = true }
        val merge = scene.javaClass.getDeclaredMethod("merged", List::class.java, List::class.java, Vec3::class.java).also { it.isAccessible = true }
        val field = scene.javaClass.getDeclaredField("lobes").also { it.isAccessible = true }
        var far: List<*> = emptyList<Any>()
        var previous = emptyMap<Long, RenderItem>()
        var stormFrames = 0
        var publications = 0
        try {
            for (step in 0..180) {
                val seconds = step / 10.0
                val time = 100.0 + seconds
                val camera = direction.copy().mulInPlace(body.radius + 8000.0 + seconds * 400.0)
                if (step % 10 == 0) {
                    if (step % 50 == 0) far = list.invoke(scene, camera, time, 14_000.0, 25_000.0, false) as List<*>
                    val near = list.invoke(scene, camera, time, 0.0, 20_000.0, false) as List<*>
                    field.set(scene, merge.invoke(scene, near, far, camera))
                    publications++
                }
                val frame = ArrayList<RenderItem>()
                scene.append(Quat.identity(), time, frame, step * 100_000_000L)
                assertEquals("unique identities at $seconds", frame.size, frame.map { it.key }.toSet().size)
                assertTrue("bounded fade-out overlap", frame.count { it.shape is CloudPuff } <= 640)
                if (frame.any { it.orderedTransparency && it.shape is CloudPuff && it.color[0] < .55f }) stormFrames++
                for (item in frame) previous[item.key]?.let { old ->
                    assertEquals("same mesh at $seconds", old.shape, item.shape)
                    assertTrue("no opacity pop at $seconds", kotlin.math.abs(old.color[3] - item.color[3]) < .35f)
                    if (step % 10 == 0) assertEquals("worker publication is continuous", old.color[3], item.color[3], .03f)
                }
                previous = frame.associateBy { it.key }
            }
            assertEquals(19, publications)
            assertTrue("storm visible throughout the test climb", stormFrames > 150)
        } finally { scope.cancel() }
    }

}
