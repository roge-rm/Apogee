package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.sea.Sea
import com.rm.apogee.core.sea.SeaSample
import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.weather.WeatherConfig
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.SeaSurface
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sea to draw: sampled from the same [Sea] the physics floats boats on,
 * round the craft being flown, on a worker.
 *
 * Laid out as rings about the craft - vertices a few tens of centimetres
 * apart at its hull, growing outward in step with how far apart they are
 * round each ring, out to [reach]. Centred on the craft, so the water is
 * finest exactly where a hull meets it, and moves with it rather than
 * crawling past. Waves too short to show at a ring's spacing are left out
 * there - they would only alias.
 */
class SeaScene(
    val body: CelestialBody,
    moon: CelestialBody?,
    val config: WeatherConfig?,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /**
     * For building, off the frame thread: one each for the workers that
     * share a build, since a sea keeps caches that are not for sharing.
     */
    private val builders = Array(WORKERS) { Builder(Sea(body, moon, config?.let { Weather(body, it) }, config?.seed ?: 0)) }

    /** For the frame thread: the tide under the camera, and whether it is under the water. */
    private val frameSea = Sea(body, moon, config?.let { Weather(body, it) }, config?.seed ?: 0)
    private val frameSample = SeaSample()

    /** How far round the camera the waves are drawn, m. */
    val reach: Double = when (tier) {
        QualityTier.LOW -> 6_000.0
        QualityTier.MEDIUM -> 12_000.0
        QualityTier.HIGH -> 20_000.0
    }

    /** Vertices round each ring. */
    private val segments = when (tier) {
        QualityTier.LOW -> 48
        QualityTier.MEDIUM -> 72
        QualityTier.HIGH -> 96
    }

    /** Ring radii, m: from the innermost out past [reach], each a step bigger in proportion. */
    private val radii: DoubleArray = run {
        val grow = 1.0 + 2.0 * Math.PI / segments
        val list = ArrayList<Double>()
        var r = INNERMOST
        while (r < reach * 1.05) { list.add(r); r *= grow }
        list.add(r)
        list.toDoubleArray()
    }

    private val layout = segments * 10_000 + radii.size
    private val indices: IntArray = buildIndices()

    @Volatile var latest: SeaSurface? = null
        private set
    @Volatile private var building = false
    @Volatile var lastBuildMillis = 0.0
        private set

    /**
     * Asks for the sea round body-fixed [centre] at [time] - if one is not
     * already being built. Built for a moment ahead, by as long as the last
     * one took, so it is drawn nearly on time.
     */
    fun update(centre: Vec3, time: Double) {
        if (building) return
        building = true
        val at = centre.copy()
        val ahead = time + lastBuildMillis / 1_000.0
        scope.launch(BUILD) {
            val started = System.nanoTime()
            try {
                latest = kotlinx.coroutines.coroutineScope { build(this, at, ahead) }
                // The sea state round the craft for the next while, worked out
                // here rather than by the flight's own step when it gets there.
                builders[0].sea.prefetch(at, ahead)
            } finally {
                lastBuildMillis = (System.nanoTime() - started) / 1e6
                building = false
            }
        }
    }

    /** The sea at body-fixed [position] into [out], for the frame thread: spray, wakes. */
    fun sampleInto(position: Vec3, time: Double, out: SeaSample) { frameSea.sample(position, time, out) }

    /** The sea at body-fixed [position] now, for the frame thread. */
    fun sampleAt(position: Vec3, time: Double): SeaSample = frameSea.sample(position, time, frameSample)

    /** Whether body-fixed [position] is under the sea, [here] being the sea sampled there. */
    fun isUnder(position: Vec3, here: SeaSample): Boolean = here.depth > 0.0 && position.length < body.radius + here.height


    /** One worker's share of a build: its own sea and scratch. */
    private inner class Builder(val sea: Sea) {
        val sample = SeaSample()
        val direction = Vec3()

        fun vertex(out: FloatArray, index: Int, origin: Vec3, e: Vec3, n: Vec3, x: Double, y: Double, spacing: Double, time: Double) {
            direction.setTo(origin).addScaledInPlace(e, x).addScaledInPlace(n, y).normalizeInPlace()
            sea.surface(direction, time, sample, spacing)
            val radius = body.radius + sample.height
            val o = index * SeaSurface.STRIDE
            out[o] = (direction.x * radius - origin.x).toFloat()
            out[o + 1] = (direction.y * radius - origin.y).toFloat()
            out[o + 2] = (direction.z * radius - origin.z).toFloat()
            out[o + 3] = direction.x.toFloat(); out[o + 4] = direction.y.toFloat(); out[o + 5] = direction.z.toFloat()
            colour(sample, direction, out, o + 6)
            out[o + 10] = sample.rise.toFloat()
        }
    }

    private suspend fun build(scope: kotlinx.coroutines.CoroutineScope, centre: Vec3, time: Double): SeaSurface {
        val up = centre.copy().normalizeInPlace()
        val origin = up.copy().mulInPlace(body.radius)
        // East and north on the ground here, for laying the rings out.
        val e = Vec3(0.0, 1.0, 0.0).crossInPlace(up)
        if (e.lengthSq < 1e-9) e.setTo(1.0, 0.0, 0.0).crossInPlace(up)
        e.normalizeInPlace()
        val n = Vec3().setTo(up).crossInPlace(e).normalizeInPlace()
        val count = 1 + radii.size * segments
        val vertices = FloatArray(count * SeaSurface.STRIDE)
        builders[0].vertex(vertices, 0, origin, e, n, 0.0, 0.0, 0.05, time)
        val step = 2.0 * Math.PI / segments
        // Rings dealt out in turn, so each worker has near and far alike.
        val jobs = builders.mapIndexed { w, builder ->
            scope.async(BUILD) {
                var ring = w
                while (ring < radii.size) {
                    val r = radii[ring]
                    val spacing = r * step
                    // Every other ring turned half a step: the triangles come
                    // out near equilateral rather than as slivers.
                    val turn = if (ring % 2 == 0) 0.0 else 0.5 * step
                    var v = 1 + ring * segments
                    for (k in 0 until segments) {
                        val a = k * step + turn
                        builder.vertex(vertices, v, origin, e, n, r * cos(a), r * sin(a), spacing, time)
                        v++
                    }
                    ring += builders.size
                }
            }
        }
        jobs.forEach { it.await() }
        return SeaSurface(origin, vertices, count, indices, layout, time)
    }

    /**
     * The water's colour here, and how see-through: deep blue out at sea,
     * turquoise over the shallows - clear enough there to see the bed -
     * lighter on the crests, grey-green under a storm, and white where it
     * breaks: whitecaps, storm crests, and surf along the shore.
     */
    private fun colour(s: SeaSample, at: Vec3, out: FloatArray, o: Int) {
        val depth = s.depth
        val shallow = 1.0 - smooth(1.0, 14.0, depth)
        val mid = 1.0 - smooth(10.0, 60.0, depth)
        var r = DEEP_R + (MID_R - DEEP_R) * mid + (SHALLOW_R - MID_R) * shallow
        var g = DEEP_G + (MID_G - DEEP_G) * mid + (SHALLOW_G - MID_G) * shallow
        var b = DEEP_B + (MID_B - DEEP_B) * mid + (SHALLOW_B - MID_B) * shallow
        // Crests lighter and greener, where the light comes through them.
        val crest = if (s.significantHeight > 0.05) smooth(0.1, 0.6, (s.height - s.tide) / s.significantHeight) else 0.0
        r += 0.03 * crest; g += 0.09 * crest; b += 0.05 * crest
        // A storm sea: grey and hard.
        val storm = smooth(1.0, 8.0, s.stormHeight)
        r += (STORM_R - r) * storm * 0.7; g += (STORM_G - g) * storm * 0.7; b += (STORM_B - b) * storm * 0.7
        // Foam: patchy, not a wash - each facet foams or not by its own hash.
        val r0 = body.radius * FOAM_GRAIN
        val speckle = com.rm.apogee.core.terrain.Noise.hash(
            0xF0A, Math.floor(at.x * r0).toInt(), Math.floor(at.y * r0).toInt(), Math.floor(at.z * r0).toInt(),
        )
        val surf = if (depth in 0.0..1.2 && s.significantHeight > 0.2) 1.0 - depth / 1.2 else 0.0
        val foam = kotlin.math.max(s.breaking, surf)
        val white = if (foam > 0.05 && speckle < foam * 1.2) 1.0 else 0.0
        r += (FOAM - r) * white; g += (FOAM - g) * white; b += (FOAM_B - b) * white
        out[o] = r.toFloat(); out[o + 1] = g.toFloat(); out[o + 2] = b.toFloat()
        // See-through over the shallows; solid where deep, or foaming.
        val alpha = (0.35 + 0.57 * smooth(0.5, 20.0, depth)).coerceAtLeast(white)
        out[o + 3] = alpha.toFloat()
    }

    private fun buildIndices(): IntArray {
        val list = IntArray(segments * 3 + (radii.size - 1) * segments * 6)
        var n = 0
        // The centre fan.
        for (k in 0 until segments) {
            list[n++] = 0; list[n++] = 1 + k; list[n++] = 1 + (k + 1) % segments
        }
        for (ring in 0 until radii.size - 1) {
            val a = 1 + ring * segments
            val b = a + segments
            val odd = ring % 2 == 1
            for (k in 0 until segments) {
                val k1 = (k + 1) % segments
                // Odd rings are turned half a step ahead of the even ones.
                if (!odd) {
                    list[n++] = a + k; list[n++] = b + k; list[n++] = a + k1
                    list[n++] = a + k1; list[n++] = b + k; list[n++] = b + k1
                } else {
                    list[n++] = a + k; list[n++] = b + k; list[n++] = b + k1
                    list[n++] = a + k; list[n++] = b + k1; list[n++] = a + k1
                }
            }
        }
        return list
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private companion object {
        /** Workers sharing a build. */
        const val WORKERS = 2

        /**
         * The sea's own threads, a notch below normal priority, rather than
         * the shared pool: a build is long and never pauses, and with the
         * terrain and scatter workers it could take every thread the shared
         * pool has on a four-core phone - the game server's among them, and
         * the world would stop.
         */
        val BUILD = java.util.concurrent.Executors.newFixedThreadPool(WORKERS) { r ->
            Thread(r, "sea-build").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }.asCoroutineDispatcher()

        /** The innermost ring, m from the centre. */
        const val INNERMOST = 0.35

        // Deep ocean, the middle depths, and turquoise shallows.
        const val DEEP_R = 0.03; const val DEEP_G = 0.20; const val DEEP_B = 0.42
        const val MID_R = 0.05; const val MID_G = 0.40; const val MID_B = 0.60
        const val SHALLOW_R = 0.14; const val SHALLOW_G = 0.72; const val SHALLOW_B = 0.70
        const val STORM_R = 0.20; const val STORM_G = 0.30; const val STORM_B = 0.33
        const val FOAM = 0.93; const val FOAM_B = 0.97

        /** Foam patches per metre, as the grain its speckle is hashed on. */
        const val FOAM_GRAIN = 0.35
    }
}
