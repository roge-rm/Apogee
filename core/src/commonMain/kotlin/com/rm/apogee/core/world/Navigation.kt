package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import com.rm.apogee.core.math.Math

/** What the navball's markers are measured against. */
@Serializable
enum class NavFrame(val label: String) {
    /** Surface when low down, orbit when higher up. */
    @SerialName("auto") AUTO("AUTO"),
    @SerialName("surface") SURFACE("SRF"),
    @SerialName("orbit") ORBIT("ORB"),
    /** Relative to the target craft, when there is one. */
    @SerialName("target") TARGET("TGT"),
}

/** What stability assist holds the nose on. */
@Serializable
enum class SasMode(val label: String) {
    /** Wherever it was pointing when you let go of the stick. */
    @SerialName("hold") HOLD("Hold"),
    @SerialName("prograde") PROGRADE("Prograde"),
    @SerialName("retrograde") RETROGRADE("Retrograde"),
    @SerialName("normal") NORMAL("Normal"),
    @SerialName("antiNormal") ANTI_NORMAL("Anti-normal"),
    @SerialName("radialOut") RADIAL_OUT("Radial out"),
    @SerialName("radialIn") RADIAL_IN("Radial in"),
    @SerialName("target") TARGET("Target"),
    @SerialName("antiTarget") ANTI_TARGET("Anti-target"),
    /** Along whatever is left of the next planned burn. */
    @SerialName("burn") BURN("Burn"),
}

/**
 * The navball's directions, in the attractor's inertial frame. They're the same for the HUD that
 * draws them and the stability assist that holds the nose on them, so a craft told to hold prograde
 * points exactly at the prograde marker.
 */
class NavDirections {
    /**
     * The frame actually in use: [NavFrame.AUTO] worked out, and TARGET only when there's a target.
     */
    var frame = NavFrame.SURFACE
    /** Velocity in that frame, in m/s. */
    val velocity = Vec3()
    val prograde = Vec3()
    val normal = Vec3()
    val radialOut = Vec3()
    /** Whether [prograde], [normal] and [radialOut] mean anything, meaning it's moving at all. */
    var moving = false
    /** Toward the target, when there is one. */
    val toTarget = Vec3()
    var hasTarget = false
    /** Metres to the target. */
    var targetDistance = 0.0
    /** Along whatever is left of the next planned burn, when there is one. */
    val burn = Vec3()
    var hasBurn = false

    /** A unit direction for [mode], into [out]. False if there's none right now. */
    fun forMode(mode: SasMode, out: Vec3): Boolean {
        when (mode) {
            SasMode.HOLD -> return false
            SasMode.PROGRADE -> if (moving) out.setTo(prograde) else return false
            SasMode.RETROGRADE -> if (moving) out.setTo(prograde).negateInPlace() else return false
            SasMode.NORMAL -> if (moving) out.setTo(normal) else return false
            SasMode.ANTI_NORMAL -> if (moving) out.setTo(normal).negateInPlace() else return false
            SasMode.RADIAL_OUT -> if (moving) out.setTo(radialOut) else return false
            SasMode.RADIAL_IN -> if (moving) out.setTo(radialOut).negateInPlace() else return false
            SasMode.TARGET -> if (hasTarget) out.setTo(toTarget) else return false
            SasMode.ANTI_TARGET -> if (hasTarget) out.setTo(toTarget).negateInPlace() else return false
            SasMode.BURN -> if (hasBurn) out.setTo(burn) else return false
        }
        return true
    }
}

object Navigation {

    /** Above this share of the atmosphere, AUTO reads the orbit. */
    const val ORBITAL_FRAME_FRACTION = 0.5

    /** Over an airless body, the height AUTO switches at, in metres. */
    const val AIRLESS_ORBITAL_ALTITUDE = 25_000.0

    /** Below this, in any frame, there's no direction of travel worth mentioning. */
    const val STILL = 0.5

    /**
     * The navball's directions for a craft at [position] moving at [velocity] (inertial, relative
     * to [body]), in [frame], plus the target's position and velocity if there is one.
     */
    fun compute(
        position: Vec3,
        velocity: Vec3,
        body: CelestialBody,
        frame: NavFrame,
        targetPosition: Vec3?,
        targetVelocity: Vec3?,
        out: NavDirections,
    ): NavDirections {
        val altitude = body.altitudeOf(position)
        val auto = if (body.atmosphere != null) {
            altitude > body.atmosphereHeight * ORBITAL_FRAME_FRACTION
        } else {
            altitude > AIRLESS_ORBITAL_ALTITUDE
        }
        out.hasTarget = targetPosition != null
        out.frame = when (frame) {
            NavFrame.AUTO -> if (auto) NavFrame.ORBIT else NavFrame.SURFACE
            NavFrame.TARGET -> if (targetPosition != null && targetVelocity != null) NavFrame.TARGET
                else if (auto) NavFrame.ORBIT else NavFrame.SURFACE
            else -> frame
        }
        when (out.frame) {
            NavFrame.ORBIT -> out.velocity.setTo(velocity)
            NavFrame.TARGET -> out.velocity.setTo(velocity).subInPlace(targetVelocity!!)
            else -> {
                body.surfaceVelocityAt(position, out.velocity)
                out.velocity.mulInPlace(-1.0).addInPlace(velocity)
            }
        }
        val speed = out.velocity.length
        out.moving = speed > STILL
        if (out.moving) {
            out.prograde.setTo(out.velocity).mulInPlace(1.0 / speed)
            out.normal.setTo(position).crossInPlace(out.prograde)
            if (out.normal.lengthSq < 1e-12) {
                // Straight up or down, any horizontal direction will do for "normal".
                out.normal.setTo(position.z, 0.0, -position.x)
                if (out.normal.lengthSq < 1e-12) out.normal.setTo(1.0, 0.0, 0.0)
            }
            out.normal.normalizeInPlace()
            out.radialOut.setTo(out.prograde).crossInPlace(out.normal).normalizeInPlace()
        }
        if (targetPosition != null) {
            out.toTarget.setTo(targetPosition).subInPlace(position)
            out.targetDistance = out.toTarget.length
            if (out.targetDistance > 1e-6) out.toTarget.mulInPlace(1.0 / out.targetDistance) else out.hasTarget = false
        }
        return out
    }

    /**
     * The compass heading of [direction] at [position], in degrees clockwise from north, 0..360.
     * North is toward the body's +Y pole, which it turns around.
     */
    fun heading(position: Vec3, direction: Vec3): Double {
        val up = position.normalized()
        val east = Vec3(up.z, 0.0, -up.x)
        if (east.lengthSq < 1e-12) return 0.0
        east.normalizeInPlace()
        val north = up.cross(east)
        val degrees = Math.toDegrees(atan2(direction dot east, direction dot north))
        return (degrees + 360.0) % 360.0
    }

    /** Local east and north at [position], into [east] and [north]. */
    fun horizon(position: Vec3, east: Vec3, north: Vec3) {
        val up = position.normalized()
        east.setTo(up.z, 0.0, -up.x)
        if (east.lengthSq < 1e-12) east.setTo(1.0, 0.0, 0.0) else east.normalizeInPlace()
        north.setTo(up).crossInPlace(east)
    }
}
