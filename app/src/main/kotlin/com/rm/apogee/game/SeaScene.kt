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
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sea to draw, sampled from the same [Sea] the physics floats boats on, around the craft being
 * flown, on a worker.
 *
 * It's laid out as rings around the craft, with vertices a few tens of centimetres apart at its
 * hull, growing outward in step with how far apart they are around each ring, out to [reach]. It's
 * centred on the craft, so the water is finest exactly where a hull meets it, and moves with it
 * instead of crawling past. Waves too short to show at a ring's spacing are left out there, because
 * they'd only alias.
 */
class SeaScene(
    val body: CelestialBody,
    moon: CelestialBody?,
    val config: WeatherConfig?,
    private val tier: QualityTier,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /**
     * For building, off the frame thread: one for each of the workers that share a build, since a
     * sea keeps caches that aren't for sharing.
     */
    private val builders = Array(WORKERS) { Builder(Sea(body, moon, config?.let { Weather(body, it) }, config?.seed ?: 0)) }

    /** For the frame thread: the tide under the camera, and whether it's under the water. */
    private val frameSea = Sea(body, moon, config?.let { Weather(body, it) }, config?.seed ?: 0)
    private val frameSample = SeaSample()

    /** A sea of something lighter than water, like Aurantia's methane. */
    private val methane = (body.ocean?.density ?: 1_025.0) < 800.0

    /** How far around the camera the waves are drawn, in metres. */
    val reach: Double = when (tier) {
        QualityTier.LOW -> 6_000.0
        QualityTier.MEDIUM -> 12_000.0
        QualityTier.HIGH -> 20_000.0
    }

    /** Vertices around each ring. */
    private val segments = when (tier) {
        QualityTier.LOW -> 48
        QualityTier.MEDIUM -> 72
        QualityTier.HIGH -> 96
    }

    /**
     * Ring radii, in metres, from the innermost out past [reach], each a step bigger in proportion.
     */
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

    /**
     * Whether [latest] is a sea that's been worked out, as opposed to the flat stand-in drawn until
     * the first one is.
     */
    @Volatile var built = false
        private set
    @Volatile private var building = false
    @Volatile var lastBuildMillis = 0.0
        private set
    private var lastStartedNanos = 0L

    /**
     * What the sea is like at each vertex (depth, tide, how big each wave train is there), kept
     * from build to build. It changes over tens of metres and seconds, whereas the waves change
     * every frame. Each vertex's is worked out again when it has moved a good part of its spacing,
     * or has got old. See [Sea.Prepared].
     */
    private val prepared = arrayOfNulls<Sea.Prepared>(1 + radii.size * segments)
    private val preparedAt = DoubleArray(3 * (1 + radii.size * segments))
    private val preparedTime = DoubleArray(1 + radii.size * segments) { Double.NaN }

    /**
     * How far a vertex can move before it's prepared again, in metres. See [REPREPARE_METRES]. For
     * this build.
     */
    @Volatile private var reprepareMetres = REPREPARE_METRES

    /**
     * Asks for the sea around body-fixed [centre] at [time], if one isn't already being built. It's
     * built for a moment ahead, by as long as the last one took, so it's drawn nearly on time.
     */
    fun update(centre: Vec3, time: Double) {
        if (building) return
        // Nothing to draw yet, so flat water straight away while the first real sea is worked out,
        // instead of a second or more of bare seabed.
        if (latest == null) latest = placeholder(centre, time)
        // Ten a second is plenty. The renderer carries each one on for a moment by how fast its
        // water is rising, and building back to back kept two cores busy for nothing.
        val now = System.nanoTime()
        if (now - lastStartedNanos < MIN_BUILD_NANOS) return
        lastStartedNanos = now
        building = true
        val at = centre.copy()
        // No more than [MAX_AHEAD] ahead. A slow build (the first, in a new place) would otherwise
        // send the next one so far on that every vertex's prepared sea would be stale by then,
        // which would make it slow in turn.
        val ahead = time + kotlin.math.min(lastBuildMillis / 1_000.0, MAX_AHEAD)
        scope.launch(BUILD) {
            val started = System.nanoTime()
            try {
                latest = kotlinx.coroutines.coroutineScope { build(this, at, ahead) }
                built = true
                lastBuildMillis = (System.nanoTime() - started) / 1e6
                // The sea state around the craft for the next while, worked out here instead of by
                // the flight's own step when it gets there.
                builders[0].sea.prefetch(at, ahead)
            } finally {
                building = false
            }
        }
    }

    /** The sea at body-fixed [position] into [out], for the frame thread: spray and wakes. */
    fun sampleInto(position: Vec3, time: Double, out: SeaSample) { frameSea.sample(position, time, out) }

    /** The sea at body-fixed [position] now, for the frame thread. */
    fun sampleAt(position: Vec3, time: Double): SeaSample = frameSea.sample(position, time, frameSample)

    /** Whether body-fixed [position] is under the sea, with [here] being the sea sampled there. */
    fun isUnder(position: Vec3, here: SeaSample): Boolean = here.depth > 0.0 && position.length < body.radius + here.height


    /** One worker's share of a build: its own sea and scratch space. */
    private inner class Builder(val sea: Sea) {
        val sample = SeaSample()
        val direction = Vec3()

        /**
         * Vertex [index] into [out]. Past [deadline] (ns), one that isn't [near] isn't prepared any
         * more. It keeps what it was last prepared with, if that was close by, or it's drawn as
         * flat water at [tide].
         */
        fun vertex(
            out: FloatArray, index: Int, origin: Vec3, e: Vec3, n: Vec3, x: Double, y: Double, spacing: Double, time: Double,
            near: Boolean = true, deadline: Long = 0L, tide: Double = 0.0,
        ) {
            direction.setTo(origin).addScaledInPlace(e, x).addScaledInPlace(n, y).normalizeInPlace()
            val p = prepared[index] ?: Sea.Prepared().also { prepared[index] = it }
            val o3 = index * 3
            val dx = direction.x - preparedAt[o3]; val dy = direction.y - preparedAt[o3 + 1]; val dz = direction.z - preparedAt[o3 + 2]
            val moved = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) * body.radius
            val age = time - preparedTime[index]
            // Staggered, so the refreshes don't all fall in one build.
            val stale = REPREPARE_SECONDS * (1.0 + 0.5 * ((index * 0x9E3779B1L).toInt() ushr 24) / 255.0)
            if (!(age in 0.0..stale) || moved > kotlin.math.max(REPREPARE_SHARE * spacing, reprepareMetres)) {
                if (near || System.nanoTime() < deadline) {
                    sea.prepare(direction, time, spacing, p)
                    preparedAt[o3] = direction.x; preparedAt[o3 + 1] = direction.y; preparedAt[o3 + 2] = direction.z
                    preparedTime[index] = time
                } else if (preparedTime[index].isNaN() || moved > kotlin.math.max(KEEP_SPACINGS * spacing, KEEP_METRES)) {
                    flat(out, index, origin, tide)
                    return
                }
            }
            sea.surface(direction, time, p, sample, spacing)
            // Over dry land, sunk under it. Left at the tide's height, the coarse water far off and
            // the coarse ground crossed each other facet by facet along every low coast, which
            // looked like a speckle of sea and sand from high up. I noticed it and didn't like it.
            // Sunk, the coast is where they cross.
            val height = if (sample.depth <= 0.0) {
                minOf(sample.height, sample.tide - sample.depth - kotlin.math.max(DRY_SINK, DRY_SINK_SHARE * spacing))
            } else sample.height
            val radius = body.radius + height
            val o = index * SeaSurface.STRIDE
            out[o] = (direction.x * radius - origin.x).toFloat()
            out[o + 1] = (direction.y * radius - origin.y).toFloat()
            out[o + 2] = (direction.z * radius - origin.z).toFloat()
            out[o + 3] = direction.x.toFloat(); out[o + 4] = direction.y.toFloat(); out[o + 5] = direction.z.toFloat()
            colour(sample, direction, out, o + 6, spacing)
            out[o + 10] = sample.rise.toFloat()
        }

        /** Vertex [index] at [direction] as open water, flat at [tide], until there's time to work it out. */
        private fun flat(out: FloatArray, index: Int, origin: Vec3, tide: Double) {
            val radius = body.radius + tide
            val o = index * SeaSurface.STRIDE
            out[o] = (direction.x * radius - origin.x).toFloat()
            out[o + 1] = (direction.y * radius - origin.y).toFloat()
            out[o + 2] = (direction.z * radius - origin.z).toFloat()
            out[o + 3] = direction.x.toFloat(); out[o + 4] = direction.y.toFloat(); out[o + 5] = direction.z.toFloat()
            out[o + 6] = DEEP_R.toFloat(); out[o + 7] = DEEP_G.toFloat(); out[o + 8] = DEEP_B.toFloat(); out[o + 9] = DEEP_ALPHA
            out[o + 10] = 0f
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
        // Seen from well above, the finest rings around the middle are finer than anything you can
        // make out, and they cost the most to keep up with a fast camera. So inside a radius that
        // grows with the height, the rings collapse onto the middle and get drawn as one fan out to
        // the first ring kept.
        val height = kotlin.math.max(0.0, centre.length - body.radius)
        val collapse = height * COLLAPSE_SHARE
        var kept = 0
        while (kept < radii.size - 1 && radii[kept] < collapse) kept++
        reprepareMetres = kotlin.math.max(REPREPARE_METRES, height * REPREPARE_HEIGHT_SHARE)
        builders[0].vertex(vertices, 0, origin, e, n, 0.0, 0.0, 0.05, time)
        val tide = builders[0].sample.tide
        // Somewhere new (the first build, or flying fast over fresh sea), the sea state out there
        // takes seconds to work out while the terrain is being built too. The water near the craft
        // is always worked out. Further off, it's only worked out for so long, and the rest gets
        // filled in by the builds that follow, drawn as flat water in the meantime.
        val deadline = System.nanoTime() + OUTER_BUDGET_NANOS
        val step = 2.0 * Math.PI / segments
        // Rings are dealt out in turn, so each worker gets near and far alike.
        val jobs = builders.mapIndexed { w, builder ->
            scope.async(BUILD) {
                var ring = w
                while (ring < radii.size) {
                    if (ring < kept) {
                        for (k in 0 until segments) System.arraycopy(vertices, 0, vertices, (1 + ring * segments + k) * SeaSurface.STRIDE, SeaSurface.STRIDE)
                        ring += builders.size
                        continue
                    }
                    val r = radii[ring]
                    val spacing = r * step
                    // Every other ring is turned half a step, so the triangles come out nearly
                    // equilateral instead of as slivers.
                    val turn = if (ring % 2 == 0) 0.0 else 0.5 * step
                    var v = 1 + ring * segments
                    for (k in 0 until segments) {
                        val a = k * step + turn
                        builder.vertex(vertices, v, origin, e, n, r * cos(a), r * sin(a), spacing, time, r <= NEAR_REACH, deadline, tide)
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
     * The rings laid flat at the datum in deep water's colour, around body-fixed [centre]. No sea
     * is worked out at all, so it's quick enough for the frame thread. It's only used until the
     * first build lands.
     */
    private fun placeholder(centre: Vec3, time: Double): SeaSurface {
        val up = centre.copy().normalizeInPlace()
        val origin = up.copy().mulInPlace(body.radius)
        val e = Vec3(0.0, 1.0, 0.0).crossInPlace(up)
        if (e.lengthSq < 1e-9) e.setTo(1.0, 0.0, 0.0).crossInPlace(up)
        e.normalizeInPlace()
        val n = Vec3().setTo(up).crossInPlace(e).normalizeInPlace()
        val count = 1 + radii.size * segments
        val vertices = FloatArray(count * SeaSurface.STRIDE)
        val d = Vec3()
        val step = 2.0 * Math.PI / segments
        for (v in 0 until count) {
            if (v == 0) {
                d.setTo(up)
            } else {
                val ring = (v - 1) / segments
                val a = ((v - 1) % segments) * step + if (ring % 2 == 0) 0.0 else 0.5 * step
                d.setTo(origin).addScaledInPlace(e, radii[ring] * cos(a)).addScaledInPlace(n, radii[ring] * sin(a)).normalizeInPlace()
            }
            val o = v * SeaSurface.STRIDE
            vertices[o] = (d.x * body.radius - origin.x).toFloat()
            vertices[o + 1] = (d.y * body.radius - origin.y).toFloat()
            vertices[o + 2] = (d.z * body.radius - origin.z).toFloat()
            vertices[o + 3] = d.x.toFloat(); vertices[o + 4] = d.y.toFloat(); vertices[o + 5] = d.z.toFloat()
            vertices[o + 6] = DEEP_R.toFloat(); vertices[o + 7] = DEEP_G.toFloat(); vertices[o + 8] = DEEP_B.toFloat(); vertices[o + 9] = DEEP_ALPHA
            vertices[o + 10] = 0f
        }
        return SeaSurface(origin, vertices, count, indices, layout, time)
    }

    /**
     * The water's colour here, and how see-through it is: deep blue out at sea, turquoise over the
     * shallows (clear enough there to see the bottom), lighter on the crests, grey-green under a
     * storm, and white where it breaks, with whitecaps, storm crests, and surf along the shore.
     */
    private fun colour(s: SeaSample, at: Vec3, out: FloatArray, o: Int, spacing: Double) {
        val depth = s.depth
        val shallow = 1.0 - smooth(1.0, 14.0, depth)
        val mid = 1.0 - smooth(10.0, 60.0, depth)
        var r = DEEP_R + (MID_R - DEEP_R) * mid + (SHALLOW_R - MID_R) * shallow
        var g = DEEP_G + (MID_G - DEEP_G) * mid + (SHALLOW_G - MID_G) * shallow
        var b = DEEP_B + (MID_B - DEEP_B) * mid + (SHALLOW_B - MID_B) * shallow
        if (methane) {
            // Liquid methane: dark and brown, glassy, and amber over the shallows.
            r = 0.06 + 0.10 * shallow; g = 0.045 + 0.07 * shallow; b = 0.025 + 0.03 * shallow
        }
        // Crests are lighter and greener, where the light comes through them.
        val crest = if (s.significantHeight > 0.05) smooth(0.1, 0.6, (s.height - s.tide) / s.significantHeight) else 0.0
        if (!methane) { r += 0.03 * crest; g += 0.09 * crest; b += 0.05 * crest }
        // A storm sea: grey and hard.
        val storm = smooth(1.0, 8.0, s.stormHeight)
        r += (STORM_R - r) * storm * 0.7; g += (STORM_G - g) * storm * 0.7; b += (STORM_B - b) * storm * 0.7
        // Foam is patchy, not a wash. Each facet foams or doesn't by its own hash.
        val r0 = body.radius * FOAM_GRAIN
        val speckle = com.rm.apogee.core.terrain.Noise.hash(
            0xF0A, Math.floor(at.x * r0).toInt(), Math.floor(at.y * r0).toInt(), Math.floor(at.z * r0).toInt(),
        )
        // Surf only where the rings are fine enough to draw it. Out where they're tens of metres
        // apart it was a scatter of white facets along every shore.
        val surf = if (depth in 0.0..1.2 && s.significantHeight > 0.2 && spacing < SURF_SPACING) 1.0 - depth / 1.2 else 0.0
        val foam = kotlin.math.max(s.breaking, surf)
        val white = if (foam > 0.05 && speckle < foam * 1.2) 1.0 else 0.0
        r += (FOAM - r) * white; g += (FOAM - g) * white; b += (FOAM_B - b) * white
        out[o] = r.toFloat(); out[o + 1] = g.toFloat(); out[o + 2] = b.toFloat()
        // See-through over the shallows, and solid where it's deep or foaming.
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

        /** The furthest ahead a build is made for, in seconds. */
        const val MAX_AHEAD = 0.5

        /** Out to here, in metres, the sea is worked out in full on every build. */
        const val NEAR_REACH = 2_000.0

        /** How long a build can spend working out the sea beyond [NEAR_REACH] afresh, in ns. */
        const val OUTER_BUDGET_NANOS = 200_000_000L

        /**
         * Past the budget, a vertex keeps what it was last prepared with unless it has moved more
         * than this many of its spacings, or [KEEP_METRES], since then.
         */
        const val KEEP_SPACINGS = 2.0
        const val KEEP_METRES = 1_000.0

        /** Builds no closer together than this, in ns: ten a second. */
        const val MIN_BUILD_NANOS = 100_000_000L

        /**
         * A vertex's [Sea.Prepared] is worked out again once it has moved this share of its spacing
         * or [REPREPARE_METRES], whichever is more, or is this many seconds old (half as long
         * again, staggered).
         */
        const val REPREPARE_SHARE = 0.25
        const val REPREPARE_METRES = 10.0
        const val REPREPARE_SECONDS = 3.0

        /** Rings inside this share of the camera's height above the sea collapse onto the middle. */
        const val COLLAPSE_SHARE = 0.1

        /** High up, a vertex is only prepared again once it has moved this share of the camera's height. */
        const val REPREPARE_HEIGHT_SHARE = 0.1

        /**
         * The sea's own threads, just below normal priority, instead of the shared pool. A build is
         * long and never pauses, and together with the terrain and scatter workers it could take
         * every thread the shared pool has on a four-core phone, including the game server's, and
         * the world would stop.
         */
        val BUILD = workerPool("sea-build", WORKERS)

        /** The innermost ring, in metres from the centre. */
        const val INNERMOST = 0.35

        // Deep ocean, the middle depths, and turquoise shallows.
        const val DEEP_R = 0.03; const val DEEP_G = 0.20; const val DEEP_B = 0.42
        const val DEEP_ALPHA = 0.92f
        const val MID_R = 0.05; const val MID_G = 0.40; const val MID_B = 0.60
        const val SHALLOW_R = 0.14; const val SHALLOW_G = 0.72; const val SHALLOW_B = 0.70
        const val STORM_R = 0.20; const val STORM_G = 0.30; const val STORM_B = 0.33
        const val FOAM = 0.93; const val FOAM_B = 0.97

        /**
         * Water over dry land is drawn this far under it, in metres, or this share of the rings'
         * spacing if that's more.
         */
        const val DRY_SINK = 2.0
        const val DRY_SINK_SHARE = 0.03

        /** Surf is only drawn where the rings are closer together than this, in metres. */
        const val SURF_SPACING = 25.0

        /** Foam patches per metre, as the grain its speckle is hashed on. */
        const val FOAM_GRAIN = 0.35
    }
}
