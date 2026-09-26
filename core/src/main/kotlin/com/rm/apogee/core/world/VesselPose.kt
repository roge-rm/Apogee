package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Wheel
import kotlin.math.roundToInt

/**
 * A craft's moving parts, packed for the wire: one byte per value, in part
 * order - a control surface's deflection; an engine's output, and if it
 * gimbals its pitch and yaw; a wheel's steering and then its suspension; a
 * leg's, chute's, sun wing's or dish's deploy; a thruster block's push,
 * three values in the craft's axes.
 *
 * Every client draws another player's craft from these, so the elevon, the
 * steered wheel and the half-deployed leg they see are the ones the pilot
 * sees. Close-range play - two craft docking, a rover parking beside a
 * lander - is where a difference would show, and where it matters most.
 *
 * Values, not the stick command they came from: deriving them again on each
 * client would need each client's idea of the craft's centre of mass, and a
 * surface near it could be judged on the wrong side and deflect backwards.
 */
object VesselPose {

    private fun controlSurface(def: PartDef): Boolean =
        def.module<AeroSurface>()?.controllable == true ||
            def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true

    private fun gimballed(def: PartDef): Boolean = (def.module<com.rm.apogee.core.part.Engine>()?.gimbalRange ?: 0.0) > 0.0

    private fun engine(def: PartDef): Boolean = def.module<com.rm.apogee.core.part.Engine>() != null

    /** A leg swinging down, or a chute filling (below 0: cut away). */
    private fun deploys(def: PartDef): Boolean =
        def.module<LandingLeg>() != null || def.module<com.rm.apogee.core.part.Parachute>() != null || foldsOut(def)

    /** A sun wing, dish or drill: out and back on its own, its progress kept where a leg's is. */
    fun foldsOut(def: PartDef): Boolean =
        def.module<com.rm.apogee.core.part.SolarPanel>()?.deployable == true ||
            def.module<com.rm.apogee.core.part.Antenna>()?.deployable == true ||
            def.module<com.rm.apogee.core.part.Drill>() != null

    private fun thruster(def: PartDef): Boolean = def.module<com.rm.apogee.core.part.Rcs>() != null

    /** Values per part, for [defs] in order. */
    private fun slots(def: PartDef): Int {
        var n = 0
        if (controlSurface(def)) n++
        if (engine(def)) n++
        if (gimballed(def)) n += 2
        if (def.module<Wheel>() != null) n += 2
        if (deploys(def)) n++
        if (thruster(def)) n += 3
        return n
    }

    fun encode(vessel: Vessel): ByteArray {
        vessel.fitPose()
        val defs = vessel.defs
        val out = ByteArray(defs.sumOf { slots(it) })
        if (out.isEmpty()) return out
        var k = 0
        for (i in defs.indices) {
            val def = defs[i]
            if (controlSurface(def)) {
                out[k++] = signed(vessel.surfaceDeflection[i])
            }
            if (engine(def)) out[k++] = signed(vessel.engineOutput[i])
            if (gimballed(def)) {
                out[k++] = signed(vessel.gimbalPitch[i])
                out[k++] = signed(vessel.gimbalYaw[i])
            }
            val wheel = def.module<Wheel>()
            if (wheel != null) {
                val range = Math.toRadians(wheel.steeringRange).coerceAtLeast(1e-6)
                out[k++] = signed(vessel.wheelSteer[i] / range)
                out[k++] = signed(vessel.wheelCompression[i] / wheel.suspensionTravel.coerceAtLeast(1e-6))
            }
            if (deploys(def)) {
                out[k++] = signed(vessel.legDeploy[i])
            }
            if (thruster(def)) {
                for (a in 0 until 3) out[k++] = signed(vessel.rcsFiring[i * 3 + a])
            }
        }
        return out
    }

    /** Unpacks [bytes] for a craft of [defs] into [into]; false if they do not fit this structure. */
    fun decode(defs: List<PartDef>, bytes: ByteArray, into: Values): Boolean {
        into.fit(defs.size)
        if (bytes.size != defs.sumOf { slots(it) }) return false
        var k = 0
        for (i in defs.indices) {
            val def = defs[i]
            if (controlSurface(def)) {
                into.deflection[i] = unsigned(bytes[k++])
            }
            if (engine(def)) into.output[i] = unsigned(bytes[k++])
            if (gimballed(def)) {
                into.gimbalPitch[i] = unsigned(bytes[k++])
                into.gimbalYaw[i] = unsigned(bytes[k++])
            }
            val wheel = def.module<Wheel>()
            if (wheel != null) {
                into.steer[i] = unsigned(bytes[k++]) * Math.toRadians(wheel.steeringRange)
                into.compression[i] = unsigned(bytes[k++]) * wheel.suspensionTravel
            }
            if (deploys(def)) {
                into.deploy[i] = unsigned(bytes[k++])
            }
            if (thruster(def)) {
                for (a in 0 until 3) into.rcs[i * 3 + a] = unsigned(bytes[k++])
            }
        }
        return true
    }

    /** Decoded values, per part. */
    class Values {
        var deflection = DoubleArray(0); private set
        var steer = DoubleArray(0); private set
        var compression = DoubleArray(0); private set
        var deploy = DoubleArray(0); private set
        var gimbalPitch = DoubleArray(0); private set
        var gimbalYaw = DoubleArray(0); private set
        /** Engines: output, 0..1 of full thrust. */
        var output = DoubleArray(0); private set
        /** Thruster blocks: push, three per part in the craft's axes, length 0..1 of thrust. */
        var rcs = DoubleArray(0); private set

        fun fit(n: Int) {
            if (deflection.size == n) return
            deflection = DoubleArray(n); steer = DoubleArray(n)
            compression = DoubleArray(n); deploy = DoubleArray(n)
            gimbalPitch = DoubleArray(n); gimbalYaw = DoubleArray(n)
            output = DoubleArray(n)
            rcs = DoubleArray(n * 3)
        }
    }

    private fun signed(value: Double): Byte = (value.coerceIn(-1.0, 1.0) * 127.0).roundToInt().toByte()

    private fun unsigned(value: Byte): Double = value / 127.0

    /** The largest error quantisation introduces, as a fraction of full travel. */
    const val RESOLUTION = 0.5 / 127.0
}
