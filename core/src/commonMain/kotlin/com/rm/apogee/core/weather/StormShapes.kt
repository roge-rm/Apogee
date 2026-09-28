package com.rm.apogee.core.weather

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.terrain.Noise
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * What a storm looks like, as lobes. It's the same storm the air gets sampled from.
 *
 * Storms are spread wide. There's a broad dark base over the whole footprint (from underneath, a
 * ceiling across the sky), with towers of heaped billows rising out of it and an anvil blown out
 * ahead. When it was built as one tower on one spot, every storm looked like a chimney, far too
 * vertical and squeezed onto one small spot on the base. They also differ by kind. A supercell's
 * base is layered like a stack of plates with a wall cloud under it, and a squall line has a shelf
 * cloud running its whole length in front, with rain in a wall behind.
 *
 * [detail], 0..1, scales how many lobes there are. A storm far away, or on a device that can't draw
 * many, gets fewer and bigger ones in the same places.
 */
internal class StormShapes(
    private val storms: Storms,
    private val seed: Int,
    private val radius: Double,
    private val climate: Climate = Climate.TERRA,
) {

    private val centre = Vec3()
    private val track = Vec3()
    private val right = Vec3()

    fun build(s: Storms.Storm, time: Double, detail: Double, groundAt: (Vec3) -> Double): CloudShape {
        val envelope = storms.envelope(s, time)
        val shape = CloudShape(climate.stormCloud, envelope)
        storms.frameAt(s, time, centre, track, right)
        val c = s.cycle.toInt()
        fun h(k: Int) = Noise.hash(seed + 300 + k, s.cx, s.cy, c)
        fun at(along: Double, across: Double, height: Double) = storms.place(centre, track, right, along, across, radius + height, Vec3())

        deck(s, envelope, detail, shape, ::h, ::at)
        towers(s, time, detail, shape, ::h, ::at)
        when (s.kind) {
            StormKind.SUPERCELL -> mothership(s, detail, shape, ::h, ::at)
            StormKind.SQUALL -> shelf(s, detail, shape, ::h, ::at)
            else -> {}
        }
        anvil(s, time, envelope, detail, shape, ::h, ::at)
        if (climate.precipitation != Climate.Precipitation.NONE) rain(s, time, envelope, detail, shape, ::h, ::at, groundAt)
        return shape
    }

    /**
     * The base: billows tiled across the footprint, ragged at the edge, with darker bulges hanging
     * under the towers and tatters of scud below.
     */
    private fun deck(
        s: Storms.Storm, envelope: Double, detail: Double, shape: CloudShape,
        h: (Int) -> Double, at: (Double, Double, Double) -> Vec3,
    ) {
        val grow = 0.55 + 0.45 * envelope
        val halfA = s.halfAlong * grow
        val halfC = s.halfAcross * grow
        val wanted = 14.0 + 40.0 * detail
        val spacing = max(1_500.0, sqrt(4.0 * halfA * halfC / wanted))
        var k = 0
        var a = -halfA + spacing * 0.5
        while (a < halfA) {
            var cr = -halfC + spacing * 0.5
            while (cr < halfC) {
                k++
                val ja = a + (h(1_000 + k) - 0.5) * spacing * 0.6
                val jc = cr + (h(2_000 + k) - 0.5) * spacing * 0.6
                val q = sqrt((ja / halfA).let { it * it } + (jc / halfC).let { it * it })
                // Ragged at the edge, with some of the outermost left out.
                if (q > 1.0 || (q > 0.75 && h(3_000 + k) < 0.35)) { cr += spacing; continue }
                shape.lobes.add(
                    CloudLobe(
                        at(s.deckAlong + ja, jc, s.base + 180.0 + 220.0 * h(4_000 + k)),
                        spacing * (0.72 + 0.3 * h(5_000 + k)) * (1.0 - 0.3 * max(0.0, q - 0.7)),
                        320.0 + 260.0 * h(6_000 + k),
                        shade = 0.13 + 0.09 * h(7_000 + k),
                    ),
                )
                cr += spacing
            }
            a += spacing
        }
        // Lower and darker under each tower, where the updraught feeds it.
        for (i in 0 until s.cellCount) {
            val r = s.cellRadius[i]
            shape.lobes.add(CloudLobe(at(s.cellAlong[i], s.cellAcross[i], s.base - 120.0), r * 0.75, 380.0, shade = 0.11))
        }
        // Scud: tatters hanging below.
        val scud = (4 + 8 * detail).roundToInt()
        for (j in 0 until scud) {
            val an = h(450 + j) * 2.0 * Math.PI
            val out = sqrt(h(460 + j))
            shape.lobes.add(
                CloudLobe(
                    at(s.deckAlong + cos(an) * out * halfA * 0.9, sin(an) * out * halfC * 0.9, s.base - 150.0 - 350.0 * h(470 + j)),
                    250.0 + 450.0 * h(480 + j), 120.0 + 150.0 * h(490 + j), shade = 0.2,
                ),
            )
        }
    }

    /**
     * The towers: level after level of heaped billows, overlapping so no seam shows, bulging in and
     * out as they climb. They're nearly black low down, grey through the middle, and only lit where
     * they tower up into the sun. Lobes are shared out by size, so a line of fourteen is no heavier
     * to draw than one big tower.
     */
    private fun towers(
        s: Storms.Storm, time: Double, detail: Double, shape: CloudShape,
        h: (Int) -> Double, at: (Double, Double, Double) -> Vec3,
    ) {
        val weights = DoubleArray(s.cellCount) { i -> s.cellHeight[i] * s.cellRadius[i] * storms.cellLife(s, i, time) }
        val total = weights.sum()
        if (total <= 0.0) return
        val budget = 40.0 + 220.0 * detail
        for (i in 0 until s.cellCount) {
            val life = storms.cellLife(s, i, time)
            if (life < 0.08) continue
            val share = budget * weights[i] / total
            val levels = sqrt(share / 2.5).roundToInt().coerceIn(2, 10)
            val heap = ((share / levels).roundToInt() - 1).coerceIn(2, 7)
            val top = storms.cellTop(s, i, time)
            val width = 1.9 * s.cellRadius[i] * (0.5 + 0.5 * life)
            val step = (top - s.base) / levels
            if (step <= 0.0) continue
            val salt = 10_000 + i * 700
            // Fewer, bigger billows at lower detail, filling the same tower.
            val fill = sqrt(7.0 / heap)
            for (k in 0 until levels) {
                val rise = (k + 0.5) / levels
                val height = s.base + step * (k + 0.5)
                val w = width * (1.0 - 0.25 * rise) * (0.85 + 0.3 * h(salt + k * 32))
                val shade = 0.22 + 0.55 * rise * rise
                val drift = s.lean * s.core * rise
                val a0 = s.cellAlong[i] + drift
                val c0 = s.cellAcross[i]
                shape.lobes.add(CloudLobe(at(a0, c0, height), w * 0.5, step * 0.9, shade = shade))
                for (j in 0 until heap) {
                    val q = salt + k * 32 + j + 1
                    val an = (j + h(q)) * 2.0 * Math.PI / heap
                    val out = w * 0.5 * (0.45 + 0.55 * h(q + 100))
                    shape.lobes.add(
                        CloudLobe(
                            at(a0 + cos(an) * out, c0 + sin(an) * out, height + (h(q + 200) - 0.4) * step * 0.8),
                            w * (0.2 + 0.2 * h(q + 300)) * fill,
                            step * (0.55 + 0.35 * h(q + 400)),
                            shade = shade + 0.06 * h(q + 500),
                        ),
                    )
                }
            }
        }
        // The overshooting top: a bright dome pushed up through the anvil.
        if (s.strength > 0.55) {
            val main = s.mainCell
            val top = storms.cellTop(s, main, time)
            val a0 = s.cellAlong[main] + s.lean * s.core
            val w = 1.9 * s.cellRadius[main]
            shape.lobes.add(CloudLobe(at(a0, s.cellAcross[main], top + 200.0), w * 0.45, 700.0, shade = 0.92))
            for (j in 0 until 4) {
                val an = (j + h(600 + j)) * 0.5 * Math.PI
                shape.lobes.add(
                    CloudLobe(at(a0 + cos(an) * w * 0.3, s.cellAcross[main] + sin(an) * w * 0.3, top), w * (0.18 + 0.1 * h(610 + j)), 450.0, shade = 0.88),
                )
            }
        }
    }

    /**
     * A supercell's base: layer after layer of it stepping in as it climbs, like a stack of plates,
     * turning, and under the updraught a wall cloud hanging lower than the rest.
     */
    private fun mothership(
        s: Storms.Storm, detail: Double, shape: CloudShape,
        h: (Int) -> Double, at: (Double, Double, Double) -> Vec3,
    ) {
        val main = s.mainCell
        val a0 = s.cellAlong[main]; val c0 = s.cellAcross[main]
        val r = s.cellRadius[main]
        val base = (s.halfAlong + s.halfAcross) * 0.5
        val each = (6 + 10 * detail).roundToInt()
        for (k in 0 until 4) {
            val ring = base * (1.05 - 0.17 * k)
            for (j in 0 until each) {
                val an = (j + 0.3 * h(800 + k * 40 + j)) * 2.0 * Math.PI / each + k * 0.4
                shape.lobes.add(
                    CloudLobe(
                        at(a0 + cos(an) * ring * 0.8, c0 + sin(an) * ring * 0.8, s.base + 500.0 + 650.0 * k),
                        ring * 0.42, 220.0 + 60.0 * k, shade = 0.26 + 0.1 * k, flat = true,
                    ),
                )
            }
        }
        // The wall cloud, a little behind the middle of the updraught.
        shape.lobes.add(CloudLobe(at(a0 - 0.25 * r, c0, s.base - 380.0), r * 0.32, 450.0, shade = 0.1))
        for (j in 0 until 4) {
            val an = h(900 + j) * 2.0 * Math.PI
            shape.lobes.add(
                CloudLobe(at(a0 - 0.25 * r + cos(an) * r * 0.3, c0 + sin(an) * r * 0.3, s.base - 250.0), r * (0.12 + 0.08 * h(910 + j)), 250.0, shade = 0.12),
            )
        }
    }

    /**
     * A squall line's shelf cloud: a long low wedge along its whole front, darkest at the leading
     * edge and stepping up behind.
     */
    private fun shelf(
        s: Storms.Storm, detail: Double, shape: CloudShape,
        h: (Int) -> Double, at: (Double, Double, Double) -> Vec3,
    ) {
        val half = s.halfAcross - 3_000.0
        val step = max(1_800.0, 2.0 * half / (8.0 + 16.0 * detail))
        var across = -half
        var k = 0
        while (across <= half) {
            val bow = 0.08 * half * (1.0 - (across / half).let { it * it })
            val front = bow + 2_800.0
            shape.lobes.add(CloudLobe(at(front, across, s.base + 80.0), step * 0.75, 280.0, shade = 0.2))
            shape.lobes.add(CloudLobe(at(front - 700.0, across, s.base + 450.0), step * 0.8, 350.0, shade = 0.26))
            if (h(1_500 + k) < 0.5) {
                shape.lobes.add(CloudLobe(at(front + 300.0, across + (h(1_600 + k) - 0.5) * step, s.base - 200.0), step * 0.3, 150.0, shade = 0.2))
            }
            across += step
            k++
        }
    }

    /**
     * The anvil: a broad flat sheet at the top, blown far downwind of the tallest tower. That's
     * tens of kilometres for a supercell, and the whole length of a squall line. Under its downwind
     * half hang dark pouches, called mammatus. A weak storm tops out before it spreads.
     */
    private fun anvil(
        s: Storms.Storm, time: Double, envelope: Double, detail: Double, shape: CloudShape,
        h: (Int) -> Double, at: (Double, Double, Double) -> Vec3,
    ) {
        val spread = smooth(0.3, 0.6, s.strength) * envelope
        if (spread <= 0.05) return
        val main = s.mainCell
        val height = storms.cellTop(s, main, time) - 800.0
        val a0 = s.cellAlong[main] + 0.8 * s.core + s.lean * s.core
        val c0 = if (s.kind == StormKind.SQUALL) 0.0 else s.cellAcross[main]
        val halfA = storms.anvilHalfAlong(s) * spread
        val halfC = storms.anvilHalfAcross(s) * spread
        val count = (8 + 16 * detail).roundToInt()
        val size = sqrt(halfA * halfC / count) * 1.7
        for (j in 0 until count) {
            val an = h(200 + j) * 2.0 * Math.PI
            val out = sqrt(h(210 + j))
            shape.lobes.add(
                CloudLobe(
                    at(a0 + cos(an) * out * halfA * 0.85, c0 + sin(an) * out * halfC * 0.85, height + (h(215 + j) - 0.5) * 300.0),
                    size * (0.8 + 0.4 * h(220 + j)), 500.0, shade = 0.72 + 0.15 * h(230 + j), flat = true,
                ),
            )
        }
        if (envelope > 0.4 && s.strength > 0.6 && spread > 0.3) {
            val pouches = (4 + 8 * detail).roundToInt()
            for (j in 0 until pouches) {
                val along = a0 + (0.1 + 0.8 * h(700 + j)) * halfA
                val across = c0 + (h(710 + j) - 0.5) * 1.6 * halfC
                shape.lobes.add(CloudLobe(at(along, across, height - 450.0), 350.0 + 250.0 * h(720 + j), 300.0, shade = 0.45))
            }
        }
    }

    /**
     * Its rain: a dark curtain under each fully grown tower, where its rain shaft is, and paler
     * ones over the back of the base where lighter rain falls.
     */
    private fun rain(
        s: Storms.Storm, time: Double, envelope: Double, detail: Double, shape: CloudShape,
        h: (Int) -> Double, at: (Double, Double, Double) -> Vec3, groundAt: (Vec3) -> Double,
    ) {
        if (envelope <= 0.3) return
        fun curtain(along: Double, across: Double, width: Double, shade: Double) {
            val foot = at(along, across, 0.0).normalizeInPlace()
            val ground = max(groundAt(foot), 0.0)
            val top = s.base + 100.0
            if (top <= ground + 200.0) return
            shape.rain.add(CloudLobe(foot.mulInPlace(radius + (ground + top) * 0.5), width, (top - ground) * 0.5, shade = shade))
        }
        for (i in 0 until s.cellCount) {
            val life = storms.cellLife(s, i, time)
            if (life < 0.3) continue
            val r = s.cellRadius[i]
            curtain(s.cellAlong[i] + s.shaftAlong * r, s.cellAcross[i], 0.75 * r * life, smooth(0.3, 0.6, life))
        }
        if (s.stratiform > 0.2) {
            val count = (2 + 4 * detail).roundToInt()
            for (j in 0 until count) {
                val along = s.deckAlong - s.halfAlong * (0.1 + 0.5 * h(1_700 + j))
                val across = (h(1_710 + j) - 0.5) * 1.6 * s.halfAcross
                curtain(along, across, min(s.halfAlong, s.halfAcross) * 0.35, 0.45 * s.stratiform * envelope)
            }
        }
    }
}
