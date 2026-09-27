package com.rm.apogee.core.career

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.world.Command
import com.rm.apogee.core.world.World
import kotlin.math.atan2

/**
 * A test driver for a rover or a boat, on any world: throttle, and the wheel
 * or the tiller turned to steer onto a heading, through the commands a player
 * sends.
 */
internal class Driver(private val world: World, private val craft: Vessel, private val sense: Double) {
    private val body get() = world.attractorFor(craft)

    fun up(): Vec3 = craft.body.position.normalized()

    /** Here, in space now: east and north along the ground. */
    private fun east(): Vec3 = body.rotationAt(world.time).rotate(Vec3.unitY()).crossInPlace(up()).normalizeInPlace()
    private fun north(): Vec3 = up().crossInPlace(east()).normalizeInPlace()

    fun groundVelocity(): Vec3 = craft.body.linearVelocity.copy().subInPlace(body.surfaceVelocityAt(craft.body.position, Vec3()))

    fun speed(): Double = groundVelocity().length

    /** Degrees north of east the nose points, flattened. */
    fun heading(): Double = craft.body.orientation.rotate(craft.design.orientation.forward).let { Math.toDegrees(atan2(it dot north(), it dot east())) }

    /** Where it is, metres east and north of [from], body-fixed unit direction, along the surface. */
    fun offset(from: Vec3): Pair<Double, Double> {
        val here = body.toBodyFixed(craft.body.position, body.rotationAt(world.time)).normalizeInPlace()
        val e = Vec3.unitY().crossInPlace(from).normalizeInPlace()
        val n = from.copy().crossInPlace(e).normalizeInPlace()
        val d = here.subInPlace(from)
        return (d dot e) * body.radius to (d dot n) * body.radius
    }

    fun throttle(value: Double) = world.apply(Command.SetThrottle(craft.id.raw, value))

    /** The wheel turned toward [direction] degrees north of east; hard over when it is far off. */
    fun steer(direction: Double) {
        var turn = direction - heading()
        while (turn > 180.0) turn -= 360.0
        while (turn < -180.0) turn += 360.0
        world.apply(Command.SetAttitude(craft.id.raw, 0.0, (turn / 20.0).coerceIn(-1.0, 1.0) * sense, 0.0))
    }

    /**
     * Toward [direction] degrees north of east at [cruise] m/s, slowing to a
     * crawl for a sharp turn as a driver would: taken flat out, a hairpin
     * rolls a rover. No more than [most] throttle: in low gravity, a rover
     * given its all from a standstill goes over backwards.
     */
    fun drive(direction: Double, cruise: Double, most: Double = 1.0) {
        var turn = direction - heading()
        while (turn > 180.0) turn -= 360.0
        while (turn < -180.0) turn += 360.0
        val wanted = if (kotlin.math.abs(turn) > 25.0) TURNING else cruise
        val speed = speed()
        throttle(((wanted - speed) * 0.3).coerceIn(0.0, most))
        val braking = speed > wanted + 1.5
        if (craft.control.brakes != braking) world.apply(Command.SetBrakes(craft.id.raw, braking))
        // Full lock only at a crawl: the Trundler, half over at eleven metres a second, rolls.
        val lock = (LOCK_SPEED / speed.coerceAtLeast(0.1)).coerceAtMost(1.0)
        world.apply(Command.SetAttitude(craft.id.raw, 0.0, (turn / 20.0).coerceIn(-lock, lock) * sense, 0.0))
    }

    /** The wheel turned and held: [amount], -1 to 1, left positive. */
    fun wheel(amount: Double) = world.apply(Command.SetAttitude(craft.id.raw, 0.0, amount * sense, 0.0))

    companion object {
        /** Which way yaw turns it, left positive as the heading counts: every rover and boat the same way. */
        const val BOAT = 1.0
        const val ROVER = 1.0

        /** A speed to take a sharp turn at, m/s. */
        const val TURNING = 4.0

        /** Below this, m/s, the wheel may go hard over; above it, proportionally less. */
        const val LOCK_SPEED = 2.0
    }
}
