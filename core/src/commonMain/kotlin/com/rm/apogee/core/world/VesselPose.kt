package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.LandingLeg
import com.rm.apogee.core.part.PartDef
import com.rm.apogee.core.part.Wheel
import kotlin.math.roundToInt
import com.rm.apogee.core.math.Math

/**
 * A craft's moving parts packed for sending, one byte per value, in part order: surface
 * deflection, engine or rotor output, gimbal pitch and yaw, wheel steer and suspension, deploy,
 * thruster push (three, craft axes), flap, and sail angle and fill. Other players' craft are drawn
 * from these.
 *
 * They're the values, not the stick command. Working them out again on each client would need its
 * own centre of mass, and a surface near it could move backwards.
 */
object VesselPose {

    /** Anything that swings by a deflection: a control surface, or a walker's legs and arms. */
    private fun controlSurface(def: PartDef): Boolean =
        def.module<AeroSurface>()?.controllable == true ||
            def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true ||
            def.module<com.rm.apogee.core.part.Walker>() != null

    private fun gimballed(def: PartDef): Boolean = (def.module<com.rm.apogee.core.part.Engine>()?.gimbalRange ?: 0.0) > 0.0

    /** An engine, or a rotor, whose output is how fast it's turning. */
    private fun engine(def: PartDef): Boolean =
        def.module<com.rm.apogee.core.part.Engine>() != null || def.module<com.rm.apogee.core.part.Rotor>() != null

    /** A leg swinging down, or a chute filling (below 0 means cut away). */
    private fun deploys(def: PartDef): Boolean =
        def.module<LandingLeg>() != null || def.module<com.rm.apogee.core.part.Parachute>() != null || foldsOut(def)

    /** A sun wing, dish or drill: folds out by itself, its progress kept where a leg's is. */
    fun foldsOut(def: PartDef): Boolean =
        def.module<com.rm.apogee.core.part.SolarPanel>()?.deployable == true ||
            def.module<com.rm.apogee.core.part.Antenna>()?.deployable == true ||
            def.module<com.rm.apogee.core.part.Drill>() != null

    private fun thruster(def: PartDef): Boolean = def.module<com.rm.apogee.core.part.Rcs>() != null

    private fun flapped(def: PartDef): Boolean = (def.module<AeroSurface>()?.flapLift ?: 0.0) > 0.0

    private fun sail(def: PartDef): Boolean = def.module<com.rm.apogee.core.part.Sail>() != null

    /** Values per part, for [defs] in order. */
    private fun slots(def: PartDef): Int {
        var n = 0
        if (controlSurface(def)) n++
        if (engine(def)) n++
        if (gimballed(def)) n += 2
        if (def.module<Wheel>() != null) n += 2
        if (deploys(def)) n++
        if (thruster(def)) n += 3
        if (flapped(def)) n++
        if (sail(def)) n += 2
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
            if (flapped(def)) out[k++] = signed(vessel.flapPosition[i])
            if (sail(def)) {
                out[k++] = signed(vessel.sailAngle[i] / Math.PI)
                out[k++] = signed(vessel.sailFill[i])
            }
        }
        return out
    }

    /** Unpacks [bytes] for a craft of [defs] into [into]. False if they don't fit this structure. */
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
            if (flapped(def)) into.flap[i] = unsigned(bytes[k++])
            if (sail(def)) {
                into.sailAngle[i] = unsigned(bytes[k++]) * Math.PI
                into.sailFill[i] = unsigned(bytes[k++])
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
        /** Engines: output, 0..1 of full thrust. Rotors: how fast they're turning, 0..1. */
        var output = DoubleArray(0); private set
        /** Thruster blocks: push, three per part in the craft's axes, length 0..1 of thrust. */
        var rcs = DoubleArray(0); private set
        /** Wings with flaps: how far down, 0..1. */
        var flap = DoubleArray(0); private set
        /** Sails: the angle off the centreline, in radians, and how full, 0..1. */
        var sailAngle = DoubleArray(0); private set
        var sailFill = DoubleArray(0); private set

        fun fit(n: Int) {
            if (deflection.size == n) return
            deflection = DoubleArray(n); steer = DoubleArray(n)
            compression = DoubleArray(n); deploy = DoubleArray(n)
            gimbalPitch = DoubleArray(n); gimbalYaw = DoubleArray(n)
            output = DoubleArray(n)
            rcs = DoubleArray(n * 3)
            flap = DoubleArray(n)
            sailAngle = DoubleArray(n)
            sailFill = DoubleArray(n)
        }
    }

    private fun signed(value: Double): Byte = (value.coerceIn(-1.0, 1.0) * 127.0).roundToInt().toByte()

    private fun unsigned(value: Byte): Double = value / 127.0

    /** The biggest error the rounding to bytes adds, as a fraction of full travel. */
    const val RESOLUTION = 0.5 / 127.0
}
