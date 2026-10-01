package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Math
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.math.quatFromTo
import com.rm.apogee.core.orbit.CelestialBody
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Auto land for planes, rotorcraft and airships, somewhere clear. Rockets use [World]'s own.
 *
 * - A plane glides in wings level, sinking a few metres a second, less the lower it is, and flares
 *   just over the ground. The nose sets the sink, like [Cruise]; the throttle holds approach
 *   speed. Gear and flaps go down on the way. Down, it shuts the throttle and brakes to a stop.
 * - A rotorcraft moves over somewhere clear (World picks it, into its keep point) and comes
 *   straight down, slower and slower, on the collective, then shuts down.
 * - An airship makes for the same kind of spot, comes down on its ballonets, and stays down with
 *   them full.
 *
 * Each says whether it's down, and gives up, saying why, if it can't.
 */
internal class Landing(private val keeper: StationKeeping) {
    private val up = Vec3()
    private val east = Vec3()
    private val north = Vec3()
    private val velocity = Vec3()
    private val flat = Vec3()
    private val nose = Vec3()
    private val level = Vec3()
    private val wing = Vec3()
    private val spot = Vec3()
    private val turnNose = Quat()
    private val turnDeck = Quat()
    private val toSpot = Vec3()
    private val wantDrift = Vec3()
    private val drift = Vec3()

    /** What it's doing now, for the HUD. */
    enum class Outcome { FLYING, LANDED }

    /**
     * One tick of bringing plane [vessel] down, [height] metres over the ground or water, for [dt]
     * seconds. [gearDown] lowers anything it has to stand on.
     */
    fun plane(vessel: Vessel, attractor: CelestialBody, rotation: Quat, height: Double, dt: Double, gearDown: () -> Unit): Outcome {
        frame(vessel, attractor)
        val control = vessel.control
        control.sasEnabled = true
        control.sasMode = SasMode.HOLD
        val speed = flat.length
        if (vessel.touchingGround || vessel.buoyed) {
            // Down: throttle shut, brakes on, rolling to a stop with the nose lowered so the wing
            // stops flying and doesn't bounce it back up.
            control.throttle = 0.0
            control.brakes = true
            var track = Math.toDegrees(atan2(flat dot north, flat dot east))
            // Down its strip, steered back to the centre, so a plane still crabbed into a
            // crosswind doesn't run off the side.
            if (vessel.landStripSet) {
                rotation.rotate(vessel.landStripAt, touchdown)
                rotation.rotate(vessel.landStripAlong, along)
                right.setTo(along).crossInPlace(up).normalizeInPlace()
                val across = offset.setTo(vessel.body.position).subInPlace(touchdown) dot right
                val lineHeading = Math.toDegrees(atan2(along dot north, along dot east))
                track = lineHeading + ROLL_INTERCEPT * (2.0 / Math.PI) * kotlin.math.atan((across + (flat dot right) * ROLL_LOOK_AHEAD) / ROLL_CLOSING)
            }
            hold(vessel, ROLLOUT_NOSE, track)
            return if (settled(vessel, dt)) Outcome.LANDED else Outcome.FLYING
        }
        vessel.landDownFor = 0.0
        if (height < GEAR_OUT) {
            control.deployed = true
            gearDown()
        }
        if (height < FLAPS_OUT && vessel.defs.any { (it.module<com.rm.apogee.core.part.AeroSurface>()?.flapLift ?: 0.0) > 0.0 }) control.flaps = true
        val climb = velocity dot up
        // Its airspeed, which is what keeps it flying. With a tailwind it's slower through the air
        // than over the ground.
        rotation.rotate(vessel.air.wind, airFlat).negateInPlace().addInPlace(flat)
        airFlat.addScaledInPlace(up, -(airFlat dot up))
        val airSpeed = airFlat.length
        var track = Math.toDegrees(atan2(flat dot north, flat dot east))
        val path = Math.toDegrees(atan2(climb, speed))
        // In at a pilot's approach speed, a margin over the stall, unless it was already slower, so
        // it doesn't float down the runway.
        if (vessel.landSpeed <= 0.0) vessel.landSpeed = minOf(airSpeed, APPROACH_MARGIN * stallSpeed(vessel, attractor))
        // With its flaps out it flies slower, by as much as they add to the wings' lift.
        val landSpeed = vessel.landSpeed * flapSlowing(vessel)
        // The nose sets the sink: a few metres a second on the way in, easing to almost nothing in
        // the flare.
        val approaching = height > FLARE_START
        var climbWanted = -minOf(height * SINK_PER_METRE, APPROACH_SINK).coerceAtLeast(FLARE_SINK)
        // Lined up on a runway or clear strip (see World's chooseStrip): out to where its line
        // starts, then down a glide slope until the flare takes over.
        if (vessel.landStripSet) {
            val guided = strip(vessel, attractor, rotation, height, speed, dt)
            track = vessel.landHeading
            // Going round, it climbs away however low it is, so it doesn't turn off the line low
            // and come down in the trees.
            if (!guided.isNaN() && (height > STRIP_FLARE_FROM || vessel.landOutbound)) climbWanted = guided
        }
        // Too fast to land, it holds its height to bleed off speed first, or it floats and bounces.
        if (approaching && airSpeed > landSpeed * TOO_FAST) climbWanted = 0.0
        control.cruiseTrim = (control.cruiseTrim + (climbWanted - climb) * SINK_TRIM_RATE * dt).coerceIn(-MOST_ATTACK, MOST_ATTACK)
        val attack = (control.cruiseTrim + (climbWanted - climb) * SINK_GAIN).coerceIn(-MOST_ATTACK, MOST_ATTACK)
        // The throttle holds most of the approach speed on the way in and comes off for the flare,
        // which runs on the speed it carries down. Holding only the wing's angle, a plane short of
        // power slows until it can't flare.
        if (approaching) {
            val short = landSpeed * KEEP_SPEED - airSpeed
            control.keepTrim = (control.keepTrim + short * POWER_TRIM_RATE * dt).coerceIn(0.0, 1.0)
            control.throttle = (control.keepTrim + short * POWER_GAIN).coerceIn(0.0, 1.0)
        } else {
            // Eased off through the flare, all gone at the ground. Cut at the top, a plane at a
            // real approach speed stalls and sinks onto the runway.
            val short = landSpeed * KEEP_SPEED - airSpeed
            control.throttle = ((control.keepTrim + short * POWER_GAIN) * height / FLARE_START).coerceIn(0.0, 1.0)
        }
        hold(vessel, path + attack, track)
        return Outcome.FLYING
    }

    /**
     * Steers plane [vessel] for its strip: the heading into [Vessel.landHeading], and the climb it
     * wants in m/s returned. Out of line, it makes for where the line starts, [Vessel.landFinal]
     * metres out, no lower than the glide slope there. On the line it keeps to the centre and comes
     * down a [Approach.GLIDE_DEGREES] slope to touchdown. NaN leaves the climb to the normal way
     * down.
     */
    private fun strip(vessel: Vessel, attractor: CelestialBody, rotation: Quat, height: Double, speed: Double, dt: Double): Double {
        rotation.rotate(vessel.landStripAt, touchdown)
        rotation.rotate(vessel.landStripAlong, along)
        val body = vessel.body
        offset.setTo(body.position).subInPlace(touchdown)
        val alongLine = offset dot along
        right.setTo(along).crossInPlace(up).normalizeInPlace()
        val across = offset dot right
        val lineHeading = Math.toDegrees(atan2(along dot north, along dot east))
        val trackHeading = Math.toDegrees(atan2(flat dot north, flat dot east))
        val final = vessel.landFinal
        val slope = kotlin.math.tan(Math.toRadians(Approach.GLIDE_DEGREES))
        // Its height over the touchdown point, not the ground under it.
        val overTouchdown = body.position.length - touchdown.length
        val lined = abs(across) < ESTABLISHED_ACROSS && abs(wrap(trackHeading - lineHeading)) < ESTABLISHED_TURN
        // Going round: past touchdown and still high, or close in and not lined up. Out it goes
        // alongside the line until it's far enough out to turn in again.
        val longAndHigh = alongLine >= 0.0 && overTouchdown > GO_AROUND_HEIGHT
        val offGate = alongLine > -GATE && alongLine < 0.0 && abs(across) > GATE_ACROSS && overTouchdown > FLARE_START
        val tooClose = alongLine > -TOO_CLOSE && alongLine < 0.0 && !lined
        // Lined up but too far above the slope close in to get down in time, so go round now
        // rather than dive and go round over the end anyway.
        val tooHigh = lined && alongLine > -TOO_CLOSE && alongLine < 0.0 && overTouchdown > -alongLine * slope * 2.0 + GO_AROUND_HEIGHT
        if (!vessel.landOutbound && (longAndHigh || offGate || tooClose || tooHigh)) {
            vessel.landOutbound = true
            vessel.landOutSide = if (across >= 0.0) 1.0 else -1.0
        }
        if (vessel.landOutbound && alongLine < -final) vessel.landOutbound = false
        val wanted: Double
        val climbWanted: Double
        // Where it'll be across the line a few seconds on, so it eases off before it gets there.
        val acrossRate = flat dot right
        if (!vessel.landOutbound) {
            // Onto the line and along it: square to it far off, less the nearer it gets, so it
            // closes on the centre without overshooting.
            wanted = lineHeading + INTERCEPT * (2.0 / Math.PI) * kotlin.math.atan((across + acrossRate * LOOK_AHEAD) / CLOSING)
            climbWanted = if (lined) {
                val slopeHeight = (-alongLine).coerceAtLeast(0.0) * slope
                (-speed * slope + (slopeHeight - overTouchdown) * SLOPE_GAIN).coerceIn(-STRIP_MOST_SINK, STRIP_MOST_CLIMB)
            } else {
                // Not lined up yet: no lower than the slope's height where it'll join it.
                val joinHeight = (-alongLine).coerceAtLeast(TOO_CLOSE) * slope
                ((joinHeight - overTouchdown) * CRUISE_HEIGHT_GAIN).coerceIn(-APPROACH_SINK, STRIP_MOST_CLIMB)
            }
        } else {
            // Out alongside the line the other way, far enough to one side to turn back in.
            val outAcross = -(across + acrossRate * LOOK_AHEAD - vessel.landOutSide * OUT_OFFSET)
            wanted = wrap(lineHeading + 180.0) + INTERCEPT * (2.0 / Math.PI) * kotlin.math.atan(outAcross / CLOSING)
            val joinHeight = final * slope
            climbWanted = ((joinHeight - overTouchdown) * CRUISE_HEIGHT_GAIN).coerceIn(-APPROACH_SINK, STRIP_MOST_CLIMB)
        }
        var climb = climbWanted
        // Never low over what's in between until it's lined up to land.
        if (!(lined && !vessel.landOutbound) && height < ENROUTE_LEAST) climb = maxOf(climb, STRIP_MOST_CLIMB)
        // [wanted] is the track over the ground. The nose points that way plus the wind's crab
        // angle, learned slowly from how nose and track differ, as a pilot crabs into it.
        vessel.body.orientation.rotate(vessel.design.orientation.forward, nose)
        val noseHeading = Math.toDegrees(atan2(nose dot north, nose dot east))
        vessel.landCrab += (wrap(noseHeading - trackHeading) - vessel.landCrab) * (dt / CRAB_TIME).coerceAtMost(1.0)
        vessel.landCrab = vessel.landCrab.coerceIn(-MOST_CRAB, MOST_CRAB)
        val now = if (vessel.landHeading.isNaN()) noseHeading else vessel.landHeading
        var turn = wrap(wanted + vessel.landCrab - now)
        // A long way round, it keeps turning the way it started, or a plane flying back down the
        // line the wrong way dithers left and right and never turns.
        if (abs(turn) > LONG_TURN) {
            if (vessel.landTurn == 0.0) vessel.landTurn = if (turn >= 0.0) 1.0 else -1.0
            if (turn * vessel.landTurn < 0.0) turn += 360.0 * vessel.landTurn
        } else if (abs(turn) < LONG_TURN_DONE) {
            vessel.landTurn = 0.0
        }
        // The crab comes off at the last moment, so it touches down along its line without being
        // blown to the edge first.
        val decrabbing = height < DECRAB_FROM
        if (decrabbing) turn -= vessel.landCrab * (1.0 - height / DECRAB_FROM)
        val rate = if (decrabbing) DECRAB_RATE else TURN_RATE
        val step = turn.coerceIn(-rate * dt, rate * dt)
        vessel.landHeading = wrap(now + step)
        return climb
    }

    /** [degrees] brought into -180..180. */
    private fun wrap(degrees: Double): Double {
        var d = degrees % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private val airFlat = Vec3()
    private val touchdown = Vec3()
    private val along = Vec3()
    private val offset = Vec3()
    private val right = Vec3()
    private val fix = Vec3()

    /**
     * The slowest [vessel] can fly level here in m/s, where its flying surfaces at their most lift
     * just hold its weight. All of them count, not only flat ones, since each pushes against all
     * the air across the craft (see Forces). Infinite with none, or no air.
     */
    /** How much slower [vessel] can fly with its flaps as far out as they are now, 1 with none. */
    private fun flapSlowing(vessel: Vessel): Double {
        var clean = 0.0
        var flapped = 0.0
        for (i in vessel.defs.indices) {
            val surface = vessel.defs[i].module<com.rm.apogee.core.part.AeroSurface>() ?: continue
            if (vessel.isBroken(i)) continue
            clean += surface.area * surface.liftCoefficient
            flapped += surface.area * surface.flapLift * vessel.flapPosition.getOrElse(i) { 0.0 }
        }
        if (clean <= 0.0) return 1.0
        return sqrt(clean / (clean + FLAP_WEIGHT * flapped))
    }

    fun stallSpeed(vessel: Vessel, attractor: CelestialBody): Double {
        val altitude = attractor.altitudeOf(vessel.body.position)
        val density = attractor.atmosphere?.densityAt(altitude) ?: return Double.POSITIVE_INFINITY
        var lift = 0.0
        for (i in vessel.defs.indices) {
            val surface = vessel.defs[i].module<com.rm.apogee.core.part.AeroSurface>() ?: continue
            if (vessel.isBroken(i)) continue
            lift += surface.area * surface.liftCoefficient
        }
        if (lift <= 0.0 || density <= 0.0) return Double.POSITIVE_INFINITY
        val weight = vessel.body.mass * attractor.gravityAt(vessel.body.position, spot).length
        return sqrt(2.0 * weight / (density * lift))
    }

    /**
     * One tick of bringing a rotorcraft or airship ([means] says which) straight down, [height]
     * metres over the ground. An airship sinks on the keeper core's height hold over where the
     * landing began ([Vessel.control]'s keep point, body-fixed, with the planet turned to
     * [rotation]).
     *
     * A rotorcraft comes down on its collective, level: a helicopter hangs level under its rotor
     * and a drone is held level. It doesn't try to get back over where it was, since tipping a
     * helicopter toward a spot as the keeper tips a drone leaves it hanging over, wandering, and
     * landing on one skid.
     */
    fun hover(vessel: Vessel, attractor: CelestialBody, rotation: Quat, means: StationKeeping.Means, height: Double, dt: Double, gearDown: () -> Unit): Outcome {
        val control = vessel.control
        frame(vessel, attractor)
        if (height < GEAR_OUT) gearDown()
        if (vessel.touchingGround) {
            control.throttle = 0.0
            control.pitch = 0.0
            control.roll = 0.0
            control.yaw = 0.0
            // Heavy with the ballonets full, so it stays down.
            if (means == StationKeeping.Means.GAS) control.ballast = 1
            return if (settled(vessel, dt)) Outcome.LANDED else Outcome.FLYING
        }
        vessel.landDownFor = 0.0
        // Over the spot it picked first, if it had to move to find somewhere clear (see World's
        // chooseSpot), and only then down. Otherwise straight down where it is.
        if (vessel.landSpotMoved) {
            rotation.rotate(control.keepPoint, spot)
            toSpot.setTo(spot).subInPlace(vessel.body.position)
            toSpot.addScaledInPlace(up, -(toSpot dot up))
        } else toSpot.setZero()
        val over = !vessel.landSpotMoved || (toSpot.length < OVER_SPOT && flat.length < OVER_SPOT_SPEED)
        // Down slower and slower, the pace built up gently. Held at its height while it gets
        // there.
        val goal = if (over || means == StationKeeping.Means.GAS) (height * HOVER_SINK_PER_METRE).coerceIn(HOVER_TOUCHDOWN, HOVER_MOST_SINK) else 0.0
        vessel.landSink = minOf(goal, vessel.landSink + HOVER_SINK_EASE * dt)
        val sink = vessel.landSink
        // The drift wanted to get there, faster the further off, up to a few metres a second.
        wantDrift.setTo(toSpot).mulInPlace(SPOT_GAIN)
        if (wantDrift.length > SPOT_DRIFT) wantDrift.mulInPlace(SPOT_DRIFT / wantDrift.length)
        if (means == StationKeeping.Means.LIFT) {
            val climb = velocity dot up
            val wanted = -sink
            // Always some collective both ways, for rotors that steer by their speeds.
            control.keepTrim = (control.keepTrim + (wanted - climb) * StationKeeping.TRIM_RATE * dt).coerceIn(LEAST_COLLECTIVE, MOST_COLLECTIVE)
            control.throttle = (control.keepTrim + (wanted - climb) * StationKeeping.CLIMB_GAIN).coerceIn(LEAST_COLLECTIVE, MOST_COLLECTIVE)
            // A helicopter is left to hang level under its rotor, hands off. SAS steers through
            // the cyclic against the hang and would tip it over further and further.
            if (vessel.defs.any { r -> r.module<com.rm.apogee.core.part.Rotor>()?.let { !it.tail && it.cyclic > 0.0 } == true }) {
                control.sasEnabled = false
                vessel.assistHolding = false
                // A touch of stick against unwanted drift, as a pilot holds a hover or moves to a
                // clearing, or the tail rotor's push walks it sideways all the way down.
                vessel.body.orientation.rotate(vessel.design.orientation.forward, nose)
                vessel.body.orientation.rotate(vessel.design.orientation.up, level)
                wing.setTo(nose).crossInPlace(level)
                drift.setTo(flat).subInPlace(wantDrift)
                // Plus what it's learned to hold against a steady push like the tail rotor's.
                vessel.landStickPitch = (vessel.landStickPitch + (drift dot nose) * DRIFT_TRIM_RATE * dt).coerceIn(-MOST_STICK, MOST_STICK)
                vessel.landStickRoll = (vessel.landStickRoll - (drift dot wing) * DRIFT_TRIM_RATE * dt).coerceIn(-MOST_STICK, MOST_STICK)
                control.pitch = (vessel.landStickPitch + (drift dot nose) * DRIFT_STICK).coerceIn(-MOST_STICK, MOST_STICK)
                control.roll = (vessel.landStickRoll - (drift dot wing) * DRIFT_STICK).coerceIn(-MOST_STICK, MOST_STICK)
                // And pedals against any turn, since with SAS off nothing else stops it spinning.
                control.yaw = (-(vessel.body.angularVelocity dot level) * YAW_DAMPING).coerceIn(-1.0, 1.0)
                return Outcome.FLYING
            }
            // A drone, on rotors that steer by their speeds, is held level by SAS, tipped into its
            // drift toward the spot as the keeper tips it.
            drift.setTo(wantDrift).subInPlace(flat).mulInPlace(StationKeeping.SPEED_GAIN)
            val g = attractor.gravityAt(vessel.body.position, level).length
            val most = g * kotlin.math.tan(Math.toRadians(StationKeeping.MOST_TILT))
            if (drift.length > most) drift.mulInPlace(most / drift.length)
            level.setTo(up).mulInPlace(g).addInPlace(drift).normalizeInPlace()
            val from = if (vessel.assistHolding) vessel.assistHeld else vessel.body.orientation
            quatFromTo(from.rotate(vessel.design.orientation.up, wing), level, turnDeck)
            // Into its own quaternion first, since [from] may be the held attitude itself.
            turnDeck.mulInPlace(from)
            vessel.assistHeld.setTo(turnDeck)
            vessel.assistHolding = true
            control.sasEnabled = true
            control.sasMode = SasMode.HOLD
            return Outcome.FLYING
        }
        // An airship: the keeper holds a height, so give it one a little under the craft, at the
        // pace it should sink.
        val under = sink * Aerostatics.HEIGHT_DAMPING / Aerostatics.HEIGHT_SPRING
        spot.setTo(control.keepPoint).normalizeInPlace()
        control.keepPoint.setTo(spot).mulInPlace(vessel.body.position.length - under)
        keeper.fly(vessel, attractor, rotation, means, steered = false, dt = dt)
        // As heavy as it can get and still rising: it can't come down here.
        if (vessel.ballonet >= 0.999 && (velocity dot up) > 0.3) {
            control.autopilotNote = "Too light to come down here"
            control.autoLand = false
            control.ballast = 0
        }
        return Outcome.FLYING
    }

    /**
     * Whether [vessel] has stood still on the ground long enough to call it down. A grounded
     * craft's speed never reads quite zero, so it's timed.
     */
    private fun settled(vessel: Vessel, dt: Double): Boolean {
        vessel.landDownFor = if (velocity.length < SETTLED_SPEED) vessel.landDownFor + dt else 0.0
        return vessel.landDownFor > SETTLED_FOR
    }

    /**
     * Holds the nose [noseUp] degrees above the horizon, on [heading] degrees north of east, wings
     * level.
     */
    private fun hold(vessel: Vessel, noseUp: Double, heading: Double) {
        val t = Math.toRadians(noseUp)
        val h = Math.toRadians(heading)
        nose.setTo(east).mulInPlace(cos(h) * cos(t)).addScaledInPlace(north, sin(h) * cos(t)).addScaledInPlace(up, sin(t))
        level.setTo(up).addScaledInPlace(nose, -(up dot nose)).normalizeInPlace()
        val orientation = vessel.design.orientation
        quatFromTo(orientation.forward, nose, turnNose)
        val deckNow = turnNose.rotate(orientation.up)
        quatFromTo(deckNow, level, turnDeck)
        vessel.assistHeld.setTo(turnDeck).mulInPlace(turnNose)
        vessel.assistHolding = true
    }

    /**
     * Up, east and north where [vessel] is, and its velocity over the ground, all and flattened.
     */
    private fun frame(vessel: Vessel, attractor: CelestialBody) {
        up.setTo(vessel.body.position).normalizeInPlace()
        attractor.surfaceVelocityAt(vessel.body.position, east)
        if (east.lengthSq < 1e-9) east.setTo(0.0, 1.0, 0.0).crossInPlace(up)
        east.addScaledInPlace(up, -(east dot up)).normalizeInPlace()
        north.setTo(up).crossInPlace(east)
        attractor.surfaceVelocityAt(vessel.body.position, velocity)
        velocity.negateInPlace().addInPlace(vessel.body.linearVelocity)
        flat.setTo(velocity).addScaledInPlace(up, -(velocity dot up))
        if (flat.lengthSq < 1e-6) {
            vessel.body.orientation.rotate(vessel.design.orientation.forward, flat)
            flat.addScaledInPlace(up, -(flat dot up))
        }
        if (sqrt(flat.lengthSq) < 1e-9) flat.setTo(east)
    }

    companion object {
        /**
         * Lined up on a strip: within this many metres either side of its line and degrees of its
         * heading.
         */
        const val ESTABLISHED_ACROSS = 150.0
        const val ESTABLISHED_TURN = 30.0

        /**
         * Closing on a line: metres off it where it's turned halfway to square; [INTERCEPT]
         * (square) is the most it turns, in degrees.
         */
        const val CLOSING = 300.0

        /**
         * A turn further than this, in degrees, keeps its way round until it's under
         * [LONG_TURN_DONE].
         */
        const val LONG_TURN = 135.0

        /**
         * On the ground, steering back to its line: metres off where it's turned halfway, the most
         * it turns in degrees, and seconds it looks ahead.
         */
        const val ROLL_CLOSING = 20.0
        const val ROLL_INTERCEPT = 10.0
        const val ROLL_LOOK_AHEAD = 2.0

        /** Under what height, in metres, the crab comes off, and how fast, in degrees a second. */
        const val DECRAB_FROM = 5.0
        const val DECRAB_RATE = 10.0
        const val LONG_TURN_DONE = 90.0
        const val INTERCEPT = 90.0

        /** Going round, how far to one side of the line it flies back out, in metres. */
        const val OUT_OFFSET = 1_400.0

        /**
         * How hard a plane keeps to the glide slope, m/s per metre off it, and its most sink and
         * climb.
         */
        const val SLOPE_GAIN = 0.15
        const val STRIP_MOST_SINK = 8.0
        const val STRIP_MOST_CLIMB = 2.0

        /**
         * Nearer in than this, in metres, and not lined up, it goes round again; also past
         * touchdown higher than [GO_AROUND_HEIGHT].
         */
        const val TOO_CLOSE = 1_500.0
        const val GO_AROUND_HEIGHT = 40.0

        /**
         * Nearer in than this, in metres, and further off centre than this, it goes round again.
         */
        const val GATE = 600.0
        const val GATE_ACROSS = 20.0

        /** On the way out to the line, m/s of climb per metre off the height to join it at. */
        const val CRUISE_HEIGHT_GAIN = 0.05

        /**
         * The least height over the ground, in metres, a plane keeps on its way out to the line.
         */
        const val ENROUTE_LEAST = 80.0

        /** How fast a landing plane turns, in degrees a second. */
        const val TURN_RATE = 4.0

        /** How far ahead, in seconds, a plane looks at where it'll be across its line. */
        const val LOOK_AHEAD = 6.0

        /**
         * Seconds a plane takes to learn its wind crab angle, and the most crab it allows in
         * degrees.
         */
        const val CRAB_TIME = 8.0
        const val MOST_CRAB = 25.0

        /** Below this, in metres, the glide slope gives way to the ordinary flare. */
        const val STRIP_FLARE_FROM = 25.0

        /** Metres up the gear and legs go down, and the flaps. */
        const val GEAR_OUT = 150.0
        const val FLAPS_OUT = 300.0

        /**
         * What a flap's lift counts for against a wing's lift coefficient, slowing the approach.
         * More than 1 and the Sparrow lands too hard.
         */
        const val FLAP_WEIGHT = 1.0

        /**
         * A plane's sink on the way in, in m/s, and in the flare below [FLARE_START] metres: m/s
         * per metre up, down to the least at the ground.
         */
        const val APPROACH_SINK = 5.0
        const val FLARE_START = 10.0
        const val SINK_PER_METRE = 0.17
        const val FLARE_SINK = 0.7

        /**
         * The most a plane's nose goes over its flight path landing, in degrees, short of a stall
         * at about 15.
         */
        const val MOST_ATTACK = 11.0

        /** How much over its approach speed a plane holds its height to slow down. */
        const val TOO_FAST = 1.2

        /** Where a plane's nose is held once it's down, in degrees above the horizon. */
        const val ROLLOUT_NOSE = -1.0

        /** A plane's approach speed over its stall speed, the way pilots fly it. */
        const val APPROACH_MARGIN = 1.3


        /**
         * The share of its speed at the start of the landing a plane comes in at, the throttle per
         * m/s short of that, and the throttle trim's creep, a share a second per m/s.
         */
        const val KEEP_SPEED = 1.0
        const val POWER_GAIN = 0.1
        const val POWER_TRIM_RATE = 0.02

        /**
         * Degrees of nose over the flight path per m/s off the sink wanted, and the trim's creep,
         * degrees a second per m/s.
         */
        const val SINK_GAIN = 2.0
        const val SINK_TRIM_RATE = 0.6

        /** Slower than this in m/s for this many seconds, a craft on the ground is down. */
        const val SETTLED_SPEED = 1.0
        const val SETTLED_FOR = 2.0

        /** How fast, in m/s², a rotorcraft's or airship's sink builds up. */
        const val HOVER_SINK_EASE = 0.3

        /**
         * The least and most collective a landing rotorcraft uses, so there's always some to steer
         * with.
         */
        const val LEAST_COLLECTIVE = 0.15
        const val MOST_COLLECTIVE = 0.9

        /**
         * Nearer than this to its spot in metres, and slower than this in m/s, a rotorcraft comes
         * down.
         */
        const val OVER_SPOT = 4.0
        const val OVER_SPOT_SPEED = 1.5

        /** How fast a rotorcraft drifts to its spot, m/s per metre off it, and the most. */
        const val SPOT_GAIN = 0.25
        const val SPOT_DRIFT = 5.0

        /** Stick a helicopter holds against its drift, per m/s, and the most. */
        const val DRIFT_STICK = 0.08

        /** How fast a helicopter's stick learns a steady push, per m/s of drift, per second. */
        const val DRIFT_TRIM_RATE = 0.02

        /** A helicopter's pedal against its turn, per radian a second. */
        const val YAW_DAMPING = 2.0
        const val MOST_STICK = 0.3

        /**
         * A rotorcraft's or airship's sink wanted, m/s per metre up, the most, and at the ground.
         */
        const val HOVER_SINK_PER_METRE = 0.1
        const val HOVER_MOST_SINK = 3.0
        const val HOVER_TOUCHDOWN = 0.4

    }
}
