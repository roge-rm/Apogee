package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.HeatShield
import com.rm.apogee.core.part.ResourceType
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How hot every part of a craft is.
 *
 * Air rushing past a craft is brought to a stop against it, and stopping it
 * heats it: to the recovery temperature, the air's own plus v²/2cp - about
 * 750 K at a kilometre a second, over 2,600 K coming back from orbit. A
 * part is driven toward that at a rate that grows with √ρ·v, the shape of
 * the real heat flux, over the area it shows the flow. So a climb, however
 * fast, never makes anything hotter than it can stand, while a return from
 * orbit will burn up what is not shielded; and at low speed the same term
 * is the breeze that cools things down.
 *
 * The part that meets the air takes it: anything with another part squarely
 * ahead of it in the flow is shaded, and gets [SHADED] of it. A heat shield
 * turns most of what reaches it into charred ablator instead of heat. Every
 * part glows its heat away as σεT⁴ over its whole skin, conducts it across
 * its joints to its neighbours, and a lit engine heats itself by its thrust.
 * Past its [com.rm.apogee.core.part.PartDef.heatLimit] a part loses health,
 * the faster the hotter, and burns away.
 *
 * Only a thin skin heats quickly, not a part's whole mass - [SKIN] of it -
 * which is what lets a re-entry glow and pass in a couple of minutes.
 */
class Heat {

    /** Parts that burned away this update, [burntCount] of them. */
    var burnt = IntArray(8)
        private set
    var burntCount = 0
        private set

    /** The hottest part this update, as a share of its limit, and which. */
    var hottest = 0.0
        private set
    var hottestPart = -1
        private set

    private var exposure = DoubleArray(0)
    private val air = Vec3()
    private val flow = Vec3()
    private val local = Vec3()
    private val scratch = Vec3()

    fun update(vessel: Vessel, attractor: CelestialBody, dt: Double) {
        burntCount = 0
        hottest = 0.0
        hottestPart = -1
        val n = vessel.design.parts.size
        if (n == 0) return
        if (exposure.size < n) exposure = DoubleArray(n)
        val temperature = vessel.temperature
        val body = vessel.body

        val altitude = attractor.altitudeOf(body.position)
        val density = attractor.atmosphere?.densityAt(altitude) ?: 0.0
        val airTemperature = if (density > 0.0) (SEA_LEVEL_AIR - LAPSE * altitude).coerceAtLeast(STRATOSPHERE) else SPACE
        attractor.surfaceVelocityAt(body.position, air)
        air.mulInPlace(-1.0).addInPlace(body.linearVelocity)
        val speed = air.length
        // Recovery temperature, and how hard it is driven at: W/m²K.
        val recovery = airTemperature + speed * speed / (2.0 * CP_AIR)
        val coefficient = if (density > 0.0) FILM * sqrt(density) * (speed + STILL_AIR) else 0.0

        // Which way the air comes from, in the craft's own axes.
        if (speed > 1e-3) body.orientation.inverseRotate(air, flow).mulInPlace(1.0 / speed) else flow.setTo(0.0, 1.0, 0.0)
        shade(vessel, n, speed)

        val parts = vessel.design.parts
        // Inside a closed fairing: none of the air reaches it.
        val enclosed = vessel.enclosed()
        for (i in 0 until n) {
            val def = vessel.defs[i]
            val half = def.boundsHalfExtents
            val t = temperature[i]
            var power = 0.0

            // The air: toward the recovery temperature, over the area shown to it.
            if (coefficient > 0.0 && !enclosed.getOrElse(i) { false }) {
                parts[i].rotation.inverseRotate(flow, local)
                val front = 4.0 * (abs(local.x) * half.y * half.z + abs(local.y) * half.x * half.z + abs(local.z) * half.x * half.y)
                val side = skin(half) * SIDE_SHARE
                power += coefficient * (recovery - t) * (front * exposure[i] + side)
            }

            // Glowing it away, to the sky or the air.
            val t2 = t * t
            val sink = airTemperature * airTemperature
            power -= STEFAN_BOLTZMANN * EMISSIVITY * skin(half) * (t2 * t2 - sink * sink)

            // A lit engine, by the thrust it is making.
            if (def.module<Engine>() != null && vessel.activated[i]) {
                val f = vessel.partForce
                val thrust = sqrt(f[i * 3] * f[i * 3] + f[i * 3 + 1] * f[i * 3 + 1] + f[i * 3 + 2] * f[i * 3 + 2])
                power += thrust * ENGINE_HEAT
            }

            // Through the joint to its parent, both ways.
            val q = parts[i].parentIndex
            if (q >= 0) {
                val radius = minOf(def.jointRadius, vessel.defs[q].jointRadius)
                val flowOut = CONDUCTANCE * radius * radius * (t - temperature[q]) * dt
                val cq = capacity(vessel, q)
                temperature[q] += flowOut / cq
                power -= flowOut / dt
            }

            // A shield at its char temperature goes no hotter while it has
            // ablator: whatever would heat it further chars some away.
            val shield = def.module<HeatShield>()
            if (shield != null && power > 0.0 && t >= shield.charTemperature) {
                val wanted = power * dt / shield.energyPerUnit
                val charred = vessel.takeFromPart(i, ResourceType.ABLATOR, wanted)
                power -= power * (charred / wanted)
            }

            temperature[i] = (t + power * dt / capacity(vessel, i)).coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)

            val share = temperature[i] / def.heatLimit
            if (share > hottest) { hottest = share; hottestPart = i }
            if (share > 1.0 && vessel.health[i] > 0.0) {
                val rate = OVERHEAT_RATE * (share - 1.0) / 0.1
                if (vessel.damage(i, rate * dt)) {
                    if (burntCount == burnt.size) burnt = burnt.copyOf(burnt.size * 2)
                    burnt[burntCount++] = i
                }
            }
        }
    }

    /**
     * Who meets the air: a part is shaded if another sits ahead of it in the
     * flow, close enough across it to be in its way. Only worth working out
     * when the air is fast enough to matter.
     */
    private fun shade(vessel: Vessel, n: Int, speed: Double) {
        if (speed < SHADE_SPEED || n == 1) {
            exposure.fill(1.0, 0, n)
            return
        }
        val parts = vessel.design.parts
        for (i in 0 until n) {
            val pi = parts[i].position
            val aheadI = pi.x * flow.x + pi.y * flow.y + pi.z * flow.z
            var shaded = false
            for (j in 0 until n) {
                if (j == i) continue
                val pj = parts[j].position
                val aheadJ = pj.x * flow.x + pj.y * flow.y + pj.z * flow.z
                if (aheadJ <= aheadI + 0.05) continue
                // Across the flow, how far apart.
                scratch.setTo(pj.x - pi.x, pj.y - pi.y, pj.z - pi.z)
                val along = aheadJ - aheadI
                scratch.addScaledInPlace(flow, -along)
                if (scratch.length < vessel.defs[j].jointRadius) { shaded = true; break }
            }
            exposure[i] = if (shaded) SHADED else 1.0
        }
    }

    private fun capacity(vessel: Vessel, i: Int): Double =
        vessel.defs[i].dryMass.coerceAtLeast(MIN_MASS) * SPECIFIC_HEAT * SKIN

    /** A part's whole outside, m², from its bounding box. */
    private fun skin(half: Vec3): Double = 8.0 * (half.x * half.y + half.y * half.z + half.x * half.z)

    companion object {
        /** Air's heat capacity, J/kg·K: sets the recovery temperature. */
        const val CP_AIR = 1_005.0

        /**
         * W/m²K per √(kg/m³)·(m/s): how hard the air drives a part toward
         * the recovery temperature. Set so a bare pod coming back from low
         * orbit passes its limit and a shielded one does not.
         */
        const val FILM = 10.0

        /** m/s of breeze even in still air, so a parked craft still sheds heat to it. */
        const val STILL_AIR = 3.0

        /** Of a part's skin, what the air along its sides reaches as well as its face. */
        const val SIDE_SHARE = 0.01

        /** What a shaded part gets of the air's heat. */
        const val SHADED = 0.05

        /** Below this there is no heating to share out, m/s. */
        const val SHADE_SPEED = 400.0

        const val STEFAN_BOLTZMANN = 5.670e-8
        const val EMISSIVITY = 0.8

        /** Aluminium-ish, J/kg·K. */
        const val SPECIFIC_HEAT = 900.0

        /** The share of a part's mass that heats with its skin. */
        const val SKIN = 0.1
        const val MIN_MASS = 10.0

        /** W per N of thrust a lit engine heats itself by. */
        const val ENGINE_HEAT = 4.0

        /** W/K across a joint per m² of its radius. */
        const val CONDUCTANCE = 150.0

        /** Health a second lost 10% over the limit; more, the hotter. */
        const val OVERHEAT_RATE = 0.5

        const val SEA_LEVEL_AIR = 288.0
        const val LAPSE = 0.0065
        const val STRATOSPHERE = 217.0
        /** Where a part in sunlight and shade by turns settles, K. */
        const val SPACE = 250.0

        const val MIN_TEMPERATURE = 3.0
        const val MAX_TEMPERATURE = 6_000.0
    }
}
