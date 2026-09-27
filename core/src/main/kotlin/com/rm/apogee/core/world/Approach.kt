package com.rm.apogee.core.world

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.terrain.TerrainField
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/**
 * The approach to the Cape's runway: how far there is to go, how far off the centreline you are,
 * and how far above or below a [GLIDE_DEGREES] glide slope, for whichever end you're heading
 * toward. It's what the landing chip shows a pilot, and what the approach lights beside each end
 * of the runway are lit by.
 */
object Approach {

    /** What the cue shows. */
    class Cue {
        /** Metres along the runway's line to its threshold. Negative once past it, over the runway. */
        var toThreshold = 0.0
        /** Metres off the centreline, to the pilot's right positive. */
        var offCentre = 0.0
        /** Metres above the glide slope, or below it negative. */
        var aboveSlope = 0.0
        /** The angle, in degrees, of the craft seen from the aim point: 3 on the slope. */
        var angle = 0.0
        /** Landing toward the east (1) or the west (-1). */
        var sense = 1.0
    }

    /** The slope, in degrees, and where on the runway it aims, in metres past the threshold. */
    const val GLIDE_DEGREES = 3.0
    const val AIM = 300.0

    /** How far out it starts, in metres, and the most the heading can be off the runway's line, in degrees. */
    const val REACH = 20_000.0
    const val MOST_OFF_LINE = 60.0

    /** How near the slope, in degrees, counts as on it. */
    const val ON_SLOPE = 0.4

    /**
     * The cue for a craft at body-fixed [position] (metres from Terra's centre), moving at
     * body-fixed [velocity], [height] metres above the runway, into [out]. False if it isn't
     * approaching the runway at all: too far, heading away, or off to one side.
     */
    fun cue(position: Vec3, velocity: Vec3, height: Double, radius: Double, out: Cue): Boolean {
        val pad = SolarSystem.capeDirection(0.0, 0.0)
        val eastward = Vec3(0.0, 1.0, 0.0).crossInPlace(pad).normalizeInPlace()
        val northward = pad.copy().crossInPlace(eastward).normalizeInPlace()
        val d = position.copy().normalizeInPlace().subInPlace(pad)
        val east = (d dot eastward) * radius
        val north = (d dot northward) * radius
        val ve = velocity dot eastward
        val vn = velocity dot northward
        if (ve * ve + vn * vn < 1.0) return false
        val sense = if (ve >= 0.0) 1.0 else -1.0
        // How far off the runway's line it's heading.
        val heading = Math.toDegrees(atan2(vn, abs(ve)))
        if (abs(heading) > MOST_OFF_LINE) return false
        val threshold = if (sense > 0.0) TerrainField.RUNWAY_WEST else TerrainField.RUNWAY_EAST
        val toThreshold = (threshold - east) * sense
        val length = TerrainField.RUNWAY_EAST - TerrainField.RUNWAY_WEST
        if (toThreshold > REACH || toThreshold < -length) return false
        val offNorth = north - TerrainField.RUNWAY_NORTH
        out.sense = sense
        out.toThreshold = toThreshold
        // Landing east, right is south. Landing west, right is north.
        out.offCentre = -offNorth * sense
        val fromAim = toThreshold + AIM
        out.aboveSlope = height - fromAim.coerceAtLeast(0.0) * tan(Math.toRadians(GLIDE_DEGREES))
        out.angle = Math.toDegrees(atan2(height, fromAim.coerceAtLeast(1.0)))
        return true
    }

    /**
     * Whether a light set to show white above [setAt] degrees shows white to something seen [angle]
     * degrees up from it. Four of them, set at 2.5, 2.83, 3.17 and 3.5, show two white and two red on
     * the slope.
     */
    fun white(angle: Double, setAt: Double): Boolean = angle > setAt

    /**
     * The four approach lights' settings, in degrees, from the outermost in. They stand to the left
     * of the runway, so on the slope they read white, white, red, red from left to right.
     */
    val LIGHTS = doubleArrayOf(2.5, 2.83, 3.17, 3.5)
}
