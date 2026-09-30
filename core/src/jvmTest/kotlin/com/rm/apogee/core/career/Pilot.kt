package com.rm.apogee.core.career

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A test pilot for an aircraft at the Cape, flying the way a player does: SAS on, the nose put
 * where it should be and held there. Where a player would nudge the stick and let go, this sets the
 * attitude SAS holds (a nose angle, a heading and a bank) every tick, so the craft is flown by its
 * own wings and the game's SAS. On the ground it uses the stick, because SAS does nothing there.
 */
internal class Pilot(private val world: World, private val craft: Vessel) {
    private val terra = world.system.body(SolarSystem.HOMEWORLD_ID)
    private val pad = SolarSystem.capeDirection(0.0, 0.0)
    private val eastAxis = SolarSystem.capeDirection(1_000.0, 0.0).subInPlace(pad).normalizeInPlace()
    private val northAxis = SolarSystem.capeDirection(0.0, 1_000.0).subInPlace(pad).normalizeInPlace()

    init {
        world.apply(Command.SetSas(craft.id.raw, true))
    }

    fun up(): Vec3 = craft.body.position.normalized()

    /** Here, in space now: east and north along the ground. */
    private fun east(): Vec3 = terra.rotationAt(world.time).rotate(Vec3.unitY()).crossInPlace(up()).normalizeInPlace()
    private fun north(): Vec3 = up().crossInPlace(east()).normalizeInPlace()

    fun groundVelocity(): Vec3 = craft.body.linearVelocity.copy().subInPlace(terra.surfaceVelocityAt(craft.body.position, Vec3()))

    fun speed(): Double = groundVelocity().length

    fun climb(): Double = groundVelocity() dot up()

    /** Above the ground, or the sea where that's higher. Over the bay, the terrain is its floor. */
    fun height(): Double {
        val fixed = terra.toBodyFixed(craft.body.position, terra.rotationAt(world.time))
        return minOf(terra.heightAboveTerrain(craft.body.position, fixed), terra.altitudeOf(craft.body.position))
    }

    /** Degrees the nose is above the horizon. */
    fun nose(): Double = Math.toDegrees(asin((craft.forward() dot up()).coerceIn(-1.0, 1.0)))

    /** Degrees of bank, positive into a turn to the left. */
    fun bank(): Double = Math.toDegrees(-asin((craft.body.orientation.rotate(Vec3.unitX()) dot up()).coerceIn(-1.0, 1.0)))

    /** Degrees the track over the ground climbs. */
    fun path(): Double = Math.toDegrees(asin((climb() / speed().coerceAtLeast(1.0)).coerceIn(-1.0, 1.0)))

    /** Degrees north of east the track over the ground runs. */
    fun track(): Double = groundVelocity().let { Math.toDegrees(atan2(it dot north(), it dot east())) }

    /** Where it is from the pad, in metres: east along the runway, and north across it. */
    fun cape(): Pair<Double, Double> {
        val d = terra.toBodyFixed(craft.body.position, terra.rotationAt(world.time)).normalizeInPlace().subInPlace(pad)
        return (d dot eastAxis) * terra.radius to (d dot northAxis) * terra.radius
    }

    fun throttle(value: Double) = world.apply(Command.SetThrottle(craft.id.raw, value))

    fun brakes(on: Boolean) = world.apply(Command.SetBrakes(craft.id.raw, on))

    /** On the ground: the stick back to lift the nose to [nose] degrees, or let go. */
    fun rotate(nose: Double?) {
        val pitch = nose?.let { ((it - nose()) / 6.0).coerceIn(-1.0, 1.0) } ?: 0.0
        world.apply(Command.SetAttitude(craft.id.raw, pitch, 0.0, 0.0))
    }

    /** SAS to hold the nose [nose] degrees up, on [heading] degrees north of east, banked [bank] degrees. */
    fun attitude(nose: Double, heading: Double, bank: Double) {
        if (craft.control.hasAttitudeInput) world.apply(Command.SetAttitude(craft.id.raw, 0.0, 0.0, 0.0))
        val u = up()
        val t = Math.toRadians(nose)
        val h = Math.toRadians(heading)
        val f = east().mulInPlace(cos(h) * cos(t)).addScaledInPlace(north(), sin(h) * cos(t)).addScaledInPlace(u, sin(t))
        // Wings level: the craft's up in the vertical plane through the nose, then banked about the
        // nose.
        val level = u.copy().addScaledInPlace(f, -(u dot f)).normalizeInPlace()
        val wing = f.cross(level)
        val b = Math.toRadians(bank)
        val deck = level.copy().mulInPlace(cos(b)).addScaledInPlace(wing, sin(b))
        val q = quatFromTo(Vec3.unitY(), f)
        val q2 = quatFromTo(q.rotate(Vec3.unitZ()), deck)
        craft.assistHeld.setTo(q2.mulInPlace(q))
        craft.assistHolding = true
    }

    /**
     * The most the nose goes over the flight path, in degrees: [MAX_ATTACK], or less when it's fast
     * enough that that would pull more than [MOST_G]. A pilot flies to a load, not an angle, and at
     * the bottom of a dive from the edge of space eight degrees tore the Sparrow's wings off.
     */
    fun mostAttack(ceiling: Double = MAX_ATTACK): Double {
        var lift = 0.0
        for (def in craft.defs) lift += def.module<com.rm.apogee.core.part.AeroSurface>()?.area ?: 0.0
        val density = terra.atmosphere?.densityAt(terra.altitudeOf(craft.body.position)) ?: return ceiling
        val pressure = 0.5 * density * speed() * speed()
        if (lift <= 0.0 || pressure <= 0.0) return ceiling
        val weight = craft.body.mass * terra.gravityAt(craft.body.position, Vec3()).length
        val altitude = terra.altitudeOf(craft.body.position)
        val sound = kotlin.math.sqrt(1.4 * 287.0 * (terra.atmosphere?.temperatureAt(altitude) ?: 288.0))
        val radians = MOST_G * weight / (pressure * lift * com.rm.apogee.core.world.slopeAt(speed() / sound))
        return minOf(ceiling, Math.toDegrees(radians))
    }

    /**
     * Flying [direction] degrees north of east at [targetHeight] above the ground. It banks into
     * the turn onto it, with the nose held a few degrees above the way it's going to climb or sink
     * toward that height.
     */
    fun fly(direction: Double, targetHeight: Double, maxBank: Double = 25.0) {
        var turn = direction - track()
        while (turn > 180.0) turn -= 360.0
        while (turn < -180.0) turn += 360.0
        val bank = (turn * 1.5).coerceIn(-maxBank, maxBank)
        // High above where it's going, so down at a good rate. At ten metres a second a glider from
        // space flies right over the Cape.
        val climbWanted = ((targetHeight - height()) / 10.0).coerceIn(if (height() > 2_000.0) -30.0 else -10.0, 12.0)
        val attack = (2.0 + (climbWanted - climb()) * 0.6).coerceIn(-3.0, mostAttack())
        attitude(path() + attack, track() + (turn * 0.3).coerceIn(-5.0, 5.0), bank)
    }

    /** Degrees north of east to fly to come onto the runway's line, heading east (+1) or west (-1). */
    fun toRunway(sense: Double): Double {
        val (_, north) = cape()
        val offset = north - CENTRELINE
        val line = if (sense > 0.0) 0.0 else 180.0
        // Closing on the line, turned across it more the further off it is.
        return line - sense * (offset / 20.0).coerceIn(-40.0, 40.0)
    }

    private var downAt = -1.0
    private var flare: Double? = null
    private var groundTicks = 0

    /**
     * Onto the runway heading east (+1) or west (-1), aiming to touch down [aim] metres east of the
     * pad. It goes down a glide slope on the line, with the throttle holding the approach speed and
     * a flare over the tarmac. Then, rolling, the throttle is shut and the brakes go on once the
     * nose is down. Call it every tick. True once it's on the ground.
     */
    fun land(sense: Double, aim: Double, approach: Double = APPROACH): Boolean {
        // Down to stay: on its wheels for a moment, not a touch and a bounce.
        if (craft.touchingGround) groundTicks++ else groundTicks = 0
        if (groundTicks > 30 && downAt < 0.0) downAt = world.time
        if (downAt >= 0.0) {
            throttle(0.0)
            // The nose wheel held down, because the wing flying it off again is how it bounces.
            world.apply(Command.SetAttitude(craft.id.raw, -0.3, 0.0, 0.0))
            if (world.time - downAt > 1.0) brakes(true)
            return true
        }
        val (east, _) = cape()
        val toGo = (aim - east) * sense
        val h = height()
        if (h < FLARE || flare != null) {
            // Over the tarmac: power off, and the nose eased up from where the approach had it
            // until the sink is just a touch.
            throttle(0.0)
            val wanted = -((h - 2.0) * 0.25).coerceAtLeast(0.6)
            val attack = ((flare ?: (nose() - path())) + (wanted - climb()) * 1.2 / 60.0).coerceIn(0.0, mostAttack())
            flare = attack
            val turn = ((toRunway(sense) - track() + 540.0) % 360.0) - 180.0
            attitude(path() + attack, track() + turn * 0.2, 0.0)
            return false
        }
        // Slowed to the approach speed only once it's lined up with the runway. Turning onto it,
        // it's at cruise.
        val heading = toRunway(sense)
        val lined = kotlin.math.abs(((heading - track() + 540.0) % 360.0) - 180.0) < 15.0 && kotlin.math.abs(cape().second - CENTRELINE) < 150.0
        val slope = (toGo.coerceAtLeast(0.0) * kotlin.math.tan(Math.toRadians(GLIDE))).coerceAtMost(CRUISE)
        fly(heading, if (lined) slope else CRUISE, maxBank = 20.0)
        throttle((0.4 + ((if (lined) approach else CRUISE_SPEED) - speed()) * 0.05).coerceIn(0.0, 1.0))
        return false
    }

    companion object {
        /**
         * The runway's centreline, in metres north of the pad. It runs east from [RUNWAY_WEST] to
         * [RUNWAY_EAST].
         */
        const val CENTRELINE = -400.0
        const val RUNWAY_WEST = 230.0
        const val RUNWAY_EAST = 2_770.0

        const val MAX_ATTACK = 8.0

        /** The most load a pull puts on it, in g. */
        const val MOST_G = 4.0

        /**
         * Approach speed in m/s, the glide slope in degrees, the height the flare starts in metres,
         * and circuit height in metres.
         */
        const val APPROACH = 60.0
        const val CRUISE_SPEED = 140.0
        const val GLIDE = 3.0
        const val FLARE = 25.0
        const val CRUISE = 400.0
    }
}
