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
 * Auto-land for anything that flies on the air instead of its engines: planes, rotorcraft and
 * airships. A rocket brakes down on its engines, and that's [World]'s own auto-land. These come
 * down the way a pilot of each would, somewhere clear.
 *
 * - A plane glides straight ahead, wings level, sinking a few metres a second and less the lower it
 *   gets, and flares just over the ground. The nose sets the sink, like [Cruise], and the throttle
 *   keeps the wing at a comfortable angle: more power if the nose has to come up too far to hold
 *   the sink (it's getting slow), none as it flares. Gear and flaps go down on the way. On the
 *   ground (or the water) it shuts the throttle and brakes to a stop.
 * - A rotorcraft moves over to somewhere clear to set down (World picks it, into its keep point),
 *   and comes straight down, slower and slower, on the collective, the way the keeper core holds a
 *   height, and shuts down on the ground.
 * - An airship makes for the same kind of spot and comes down on its ballonets, and stays down
 *   with them full.
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
     * One tick of bringing plane [vessel] down, [height] metres over the ground or the water, for
     * [dt] seconds. [gearDown] lowers anything it has to stand on.
     */
    fun plane(vessel: Vessel, attractor: CelestialBody, rotation: Quat, height: Double, dt: Double, gearDown: () -> Unit): Outcome {
        frame(vessel, attractor)
        val control = vessel.control
        control.sasEnabled = true
        control.sasMode = SasMode.HOLD
        val speed = flat.length
        if (vessel.touchingGround || vessel.buoyed) {
            // Down: throttle shut, brakes on, and rolling (or drifting) to a stop, with the nose
            // lowered onto the runway so the wing stops flying. Held in the flare's nose-up
            // attitude instead, the wing threw it back into the air, bounce after bounce.
            control.throttle = 0.0
            control.brakes = true
            var track = Math.toDegrees(atan2(flat dot north, flat dot east))
            // Down its strip, steered back to the centre of it. Left to roll the way it touched
            // down, a plane still crabbed into a crosswind ran off the side of the runway.
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
        if (false && height < FLAPS_OUT && vessel.defs.any { (it.module<com.rm.apogee.core.part.AeroSurface>()?.flapLift ?: 0.0) > 0.0 }) control.flaps = true
        val climb = velocity dot up
        // Its speed through the air, which is what keeps it flying: over the ground, with the wind
        // behind it, it's that much slower through the air than it looks.
        rotation.rotate(vessel.air.wind, airFlat).negateInPlace().addInPlace(flat)
        airFlat.addScaledInPlace(up, -(airFlat dot up))
        val airSpeed = airFlat.length
        var track = Math.toDegrees(atan2(flat dot north, flat dot east))
        val path = Math.toDegrees(atan2(climb, speed))
        // In at a pilot's approach speed, a margin over the stall, unless it was already slower.
        // Holding whatever it had when the landing began, a plane asked to land at cruising speed
        // floated the length of the runway.
        if (vessel.landSpeed <= 0.0) vessel.landSpeed = minOf(airSpeed, APPROACH_MARGIN * stallSpeed(vessel, attractor))
        // The nose sets the sink: a few metres a second on the way in, easing to almost nothing at
        // the ground in the flare.
        val approaching = height > FLARE_START
        var climbWanted = -minOf(height * SINK_PER_METRE, APPROACH_SINK).coerceAtLeast(FLARE_SINK)
        // Lined up on a runway or a clear strip (see World's chooseStrip): out to where its line
        // starts, then down it on a glide slope, until the flare takes over near the ground.
        if (vessel.landStripSet) {
            val guided = strip(vessel, attractor, rotation, height, speed, dt)
            track = vessel.landHeading
            // Going round, it climbs away however low it is. Left to the flare below it instead, it
            // turned off the line twenty metres up and came down in the trees beside it.
            if (!guided.isNaN() && (height > STRIP_FLARE_FROM || vessel.landOutbound)) climbWanted = guided
        }
        // Too fast to land, it holds its height and lets the speed bleed off first. Coming down
        // regardless, it arrived at the flare half as fast again, floated, touched, and bounced.
        if (approaching && airSpeed > vessel.landSpeed * TOO_FAST) climbWanted = 0.0
        control.cruiseTrim = (control.cruiseTrim + (climbWanted - climb) * SINK_TRIM_RATE * dt).coerceIn(-MOST_ATTACK, MOST_ATTACK)
        val attack = (control.cruiseTrim + (climbWanted - climb) * SINK_GAIN).coerceIn(-MOST_ATTACK, MOST_ATTACK)
        // The throttle holds the speed on the way in, most of what it had when it was asked to
        // land, and comes off for the flare, which is done on the speed it carries down. Left to
        // hold only the wing's angle instead, a plane without much power over its drag slowed
        // until it had nothing left to flare with, and hit hard.
        if (approaching) {
            val short = vessel.landSpeed * KEEP_SPEED - airSpeed
            control.keepTrim = (control.keepTrim + short * POWER_TRIM_RATE * dt).coerceIn(0.0, 1.0)
            control.throttle = (control.keepTrim + short * POWER_GAIN).coerceIn(0.0, 1.0)
        } else {
            // Eased off through the flare, all of it gone at the ground. Cut at the top of the
            // flare, a plane at a real approach speed slowed to its stall on the way down and sank
            // onto the runway.
            val short = vessel.landSpeed * KEEP_SPEED - airSpeed
            control.throttle = ((control.keepTrim + short * POWER_GAIN) * height / FLARE_START).coerceIn(0.0, 1.0)
        }
        hold(vessel, path + attack, track)
        return Outcome.FLYING
    }

    /**
     * Steers plane [vessel] for its strip: the heading it turns to, into [Vessel.landHeading], and
     * the climb it wants, in m/s, returned. Out of line, it makes for where the strip's line
     * starts, [Vessel.landFinal] metres out, no lower than the glide slope's height there. On the
     * line, it keeps to its centre and comes down a [Approach.GLIDE_DEGREES] slope to the
     * touchdown point. NaN leaves the climb to the ordinary way down.
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
        // Its height over the touchdown point, not the ground under it, which rises and falls.
        val overTouchdown = body.position.length - touchdown.length
        val lined = abs(across) < ESTABLISHED_ACROSS && abs(wrap(trackHeading - lineHeading)) < ESTABLISHED_TURN
        // Going round: past where it should have touched down and still high, or close in and not
        // lined up. Out it goes, alongside the line, until it's far enough out to turn in again.
        val longAndHigh = alongLine >= 0.0 && overTouchdown > GO_AROUND_HEIGHT
        val offGate = alongLine > -GATE && alongLine < 0.0 && abs(across) > GATE_ACROSS && overTouchdown > FLARE_START
        val tooClose = alongLine > -TOO_CLOSE && alongLine < 0.0 && !lined
        // Lined up but so far over the slope, close in, that it can't get down to it in time. It
        // goes round now rather than diving at the runway and going round anyway over the end.
        val tooHigh = lined && alongLine > -TOO_CLOSE && alongLine < 0.0 && overTouchdown > -alongLine * slope * 2.0 + GO_AROUND_HEIGHT
        if (!vessel.landOutbound && (longAndHigh || offGate || tooClose || tooHigh)) {
            vessel.landOutbound = true
            vessel.landOutSide = if (across >= 0.0) 1.0 else -1.0
        }
        if (vessel.landOutbound && alongLine < -final) vessel.landOutbound = false
        val wanted: Double
        val climbWanted: Double
        // Where it'll be across the line a few seconds on, so it eases off before it gets there
        // instead of swinging through it.
        val acrossRate = flat dot right
        if (!vessel.landOutbound) {
            // Onto the line and along it: turned in square to it far off, and less and less the
            // nearer it gets, so it closes on the centre from wherever it is without overshooting.
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
            // Out alongside the line, the other way, far enough to one side to turn back in.
            val outAcross = -(across + acrossRate * LOOK_AHEAD - vessel.landOutSide * OUT_OFFSET)
            wanted = wrap(lineHeading + 180.0) + INTERCEPT * (2.0 / Math.PI) * kotlin.math.atan(outAcross / CLOSING)
            val joinHeight = final * slope
            climbWanted = ((joinHeight - overTouchdown) * CRUISE_HEIGHT_GAIN).coerceIn(-APPROACH_SINK, STRIP_MOST_CLIMB)
        }
        var climb = climbWanted
        // Never low over whatever's between, until it's lined up to land.
        if (!(lined && !vessel.landOutbound) && height < ENROUTE_LEAST) climb = maxOf(climb, STRIP_MOST_CLIMB)
        // [wanted] is the way it wants to go over the ground. The nose points that way, less the
        // angle the wind blows it off by, learned slowly from how its nose and its track differ,
        // the way a pilot crabs into it. Pointed where it wanted to go and no more, it touched down
        // seventy-five metres off to the side of the runway.
        vessel.body.orientation.rotate(vessel.design.orientation.forward, nose)
        val noseHeading = Math.toDegrees(atan2(nose dot north, nose dot east))
        vessel.landCrab += (wrap(noseHeading - trackHeading) - vessel.landCrab) * (dt / CRAB_TIME).coerceAtMost(1.0)
        vessel.landCrab = vessel.landCrab.coerceIn(-MOST_CRAB, MOST_CRAB)
        val now = if (vessel.landHeading.isNaN()) noseHeading else vessel.landHeading
        var turn = wrap(wanted + vessel.landCrab - now)
        // A long way round, it keeps turning the way it started. Choosing afresh each time, a plane
        // flying back down the line, the wrong way, turned left and right as it crossed the centre
        // and never turned round at all.
        if (abs(turn) > LONG_TURN) {
            if (vessel.landTurn == 0.0) vessel.landTurn = if (turn >= 0.0) 1.0 else -1.0
            if (turn * vessel.landTurn < 0.0) turn += 360.0 * vessel.landTurn
        } else if (abs(turn) < LONG_TURN_DONE) {
            vessel.landTurn = 0.0
        }
        // The crab comes off at the last moment, so it touches down pointing along its line. Taken
        // off any sooner, the wind blew it most of the way to the edge of the runway first.
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
     * The slowest [vessel] can fly level here, in m/s: the speed at which its flying surfaces, at
     * their most lift, just hold its weight. All of them count, not only the ones lying flat, because
     * each pushes against all the air across the craft (see Forces). Infinite with none, or no air.
     */
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
     * One tick of bringing a rotorcraft or an airship ([means] says which it's held up by) straight
     * down, [height] metres over the ground. An airship comes down on the keeper core's way of
     * holding a height, sinking over where it was when the landing began ([Vessel.control]'s keep
     * point, body-fixed, with the planet turned to [rotation]).
     *
     * A rotorcraft comes down on its collective, level. A helicopter levels itself, hanging under
     * its rotor, and a drone is held level. It doesn't try to get back over where it was: a helicopter hangs level under its rotor and steers by tilting that, so tipping the
     * whole craft toward a spot the way the keeper tips a drone had it hanging ten degrees over
     * the whole way down, wandering off, and touching down on one skid.
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
        // chooseSpot), and only then down. Otherwise straight down where it is, as always.
        if (vessel.landSpotMoved) {
            rotation.rotate(control.keepPoint, spot)
            toSpot.setTo(spot).subInPlace(vessel.body.position)
            toSpot.addScaledInPlace(up, -(toSpot dot up))
        } else toSpot.setZero()
        val over = !vessel.landSpotMoved || (toSpot.length < OVER_SPOT && flat.length < OVER_SPOT_SPEED)
        // Down slower and slower, the pace built up gently at first. Held at its height while it
        // gets there.
        val goal = if (over || means == StationKeeping.Means.GAS) (height * HOVER_SINK_PER_METRE).coerceIn(HOVER_TOUCHDOWN, HOVER_MOST_SINK) else 0.0
        vessel.landSink = minOf(goal, vessel.landSink + HOVER_SINK_EASE * dt)
        val sink = vessel.landSink
        // The way to drift to get there, faster the further off, up to a few metres a second.
        wantDrift.setTo(toSpot).mulInPlace(SPOT_GAIN)
        if (wantDrift.length > SPOT_DRIFT) wantDrift.mulInPlace(SPOT_DRIFT / wantDrift.length)
        if (means == StationKeeping.Means.LIFT) {
            val climb = velocity dot up
            val wanted = -sink
            // Some collective always kept both ways, for rotors that steer by their speeds.
            control.keepTrim = (control.keepTrim + (wanted - climb) * StationKeeping.TRIM_RATE * dt).coerceIn(LEAST_COLLECTIVE, MOST_COLLECTIVE)
            control.throttle = (control.keepTrim + (wanted - climb) * StationKeeping.CLIMB_GAIN).coerceIn(LEAST_COLLECTIVE, MOST_COLLECTIVE)
            // A helicopter is left to hang level under its rotor, hands off, which is what it does
            // by itself. Stability assist steers it through the cyclic, against the hang, and
            // holding it level that way tipped it over further and further until it went in.
            if (vessel.defs.any { r -> r.module<com.rm.apogee.core.part.Rotor>()?.let { !it.tail && it.cyclic > 0.0 } == true }) {
                control.sasEnabled = false
                vessel.assistHolding = false
                // A touch of stick against any drift it doesn't want, the way a pilot holds a hover
                // or moves over to a clearing, or the tail rotor's push walks it sideways all the
                // way down.
                vessel.body.orientation.rotate(vessel.design.orientation.forward, nose)
                vessel.body.orientation.rotate(vessel.design.orientation.up, level)
                wing.setTo(nose).crossInPlace(level)
                drift.setTo(flat).subInPlace(wantDrift)
                // Plus what it's learned to hold against a steady push, like the tail rotor's.
                // Without it, held on one heading, that push walked it ten metres off a barge's
                // deck on the way down.
                vessel.landStickPitch = (vessel.landStickPitch + (drift dot nose) * DRIFT_TRIM_RATE * dt).coerceIn(-MOST_STICK, MOST_STICK)
                vessel.landStickRoll = (vessel.landStickRoll - (drift dot wing) * DRIFT_TRIM_RATE * dt).coerceIn(-MOST_STICK, MOST_STICK)
                control.pitch = (vessel.landStickPitch + (drift dot nose) * DRIFT_STICK).coerceIn(-MOST_STICK, MOST_STICK)
                control.roll = (vessel.landStickRoll - (drift dot wing) * DRIFT_STICK).coerceIn(-MOST_STICK, MOST_STICK)
                // And the pedals against any turn. With stability assist off, nothing else stopped
                // one, and a helicopter let down into a clearing came down spinning at a turn every
                // ten seconds.
                control.yaw = (-(vessel.body.angularVelocity dot level) * YAW_DAMPING).coerceIn(-1.0, 1.0)
                return Outcome.FLYING
            }
            // A drone, on rotors that steer by their speeds, is held level by stability assist,
            // tipped into its drift toward the spot the way the keeper tips it.
            drift.setTo(wantDrift).subInPlace(flat).mulInPlace(StationKeeping.SPEED_GAIN)
            val g = attractor.gravityAt(vessel.body.position, level).length
            val most = g * kotlin.math.tan(Math.toRadians(StationKeeping.MOST_TILT))
            if (drift.length > most) drift.mulInPlace(most / drift.length)
            level.setTo(up).mulInPlace(g).addInPlace(drift).normalizeInPlace()
            val from = if (vessel.assistHolding) vessel.assistHeld else vessel.body.orientation
            quatFromTo(from.rotate(vessel.design.orientation.up, wing), level, turnDeck)
            // Into its own quaternion first: [from] may be the held attitude itself.
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
        // As heavy as it can make itself and still going up: it can't come down here.
        if (vessel.ballonet >= 0.999 && (velocity dot up) > 0.3) {
            control.autopilotNote = "Too light to come down here"
            control.autoLand = false
            control.ballast = 0
        }
        return Outcome.FLYING
    }

    /**
     * Whether [vessel], on the ground, has been standing still there for long enough to call it
     * down. On the ground a craft's speed never reads quite nothing, so it's how long it's stood.
     */
    private fun settled(vessel: Vessel, dt: Double): Boolean {
        vessel.landDownFor = if (velocity.length < SETTLED_SPEED) vessel.landDownFor + dt else 0.0
        return vessel.landDownFor > SETTLED_FOR
    }

    /** Holds the nose [noseUp] degrees above the horizon, on [heading] degrees north of east, wings level. */
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

    /** Up, east and north where [vessel] is, and its velocity over the ground, all and flattened. */
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
        /** Lined up on a strip: within this many metres either side of its line, and this many degrees of its heading. */
        const val ESTABLISHED_ACROSS = 150.0
        const val ESTABLISHED_TURN = 30.0

        /**
         * Closing on a line: how far off it, in metres, it's turned halfway to square, and square
         * to it is as far as it turns, in degrees.
         */
        const val CLOSING = 300.0

        /** A turn further than this, in degrees, keeps its way round until it's under [LONG_TURN_DONE]. */
        const val LONG_TURN = 135.0

        /** On the ground, steering back to its line: how far off it's turned halfway, the most it turns in, and how far ahead it looks, in metres, degrees and seconds. */
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

        /** How hard a plane keeps to the glide slope, m/s per metre off it, and its most sink and climb. */
        const val SLOPE_GAIN = 0.15
        const val STRIP_MOST_SINK = 8.0
        const val STRIP_MOST_CLIMB = 2.0

        /**
         * Nearer in than this, in metres, and not lined up, it goes round again, and past the
         * touchdown point higher than [GO_AROUND_HEIGHT].
         */
        const val TOO_CLOSE = 1_500.0
        const val GO_AROUND_HEIGHT = 40.0

        /** Nearer in than this, in metres, and further off the centre than this, it goes round again. */
        const val GATE = 600.0
        const val GATE_ACROSS = 20.0

        /** On the way out to the line, m/s of climb per metre off the height to join it at. */
        const val CRUISE_HEIGHT_GAIN = 0.05

        /** The least height over the ground, in metres, a plane keeps on its way out to the line. */
        const val ENROUTE_LEAST = 80.0

        /** How fast a landing plane turns, in degrees a second. */
        const val TURN_RATE = 4.0

        /** How far ahead, in seconds, a plane looks at where it'll be across its line. */
        const val LOOK_AHEAD = 6.0

        /** How long, in seconds, a plane takes to learn the angle the wind blows it off by, and the most it allows. */
        const val CRAB_TIME = 8.0
        const val MOST_CRAB = 25.0

        /** Below this, in metres, the glide slope gives way to the ordinary flare. */
        const val STRIP_FLARE_FROM = 25.0

        /** Metres up the gear and legs go down, and the flaps. */
        const val GEAR_OUT = 150.0
        const val FLAPS_OUT = 300.0

        /**
         * A plane's sink on the way in, in m/s, and in the flare below [FLARE_START] metres: m/s per
         * metre up, down to the least, at the ground.
         */
        const val APPROACH_SINK = 5.0
        const val FLARE_START = 10.0
        const val SINK_PER_METRE = 0.17
        const val FLARE_SINK = 0.7

        /**
         * The most a plane's nose goes over its flight path while it lands, in degrees, safely
         * short of where its wings stall, at about fifteen.
         */
        const val MOST_ATTACK = 11.0

        /** How much over its approach speed a plane holds its height to slow down. */
        const val TOO_FAST = 1.2

        /** Where a plane's nose is held once it's down, in degrees above the horizon. */
        const val ROLLOUT_NOSE = -1.0

        /** A plane's approach speed over its stall speed, the way pilots fly it. */
        const val APPROACH_MARGIN = 1.3


        /**
         * The share of its speed when the landing began that a plane comes in at, the throttle for
         * each m/s it's short of that, and how fast the throttle's trim creeps, a share a second per
         * m/s.
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

        /** Slower than this over the ground for this many seconds, in m/s, a craft on the ground is down. */
        const val SETTLED_SPEED = 1.0
        const val SETTLED_FOR = 2.0

        /** How fast, in m/s², a rotorcraft's or airship's sink builds up. */
        const val HOVER_SINK_EASE = 0.3

        /**
         * The least and most collective a landing rotorcraft uses, so there's always some to steer
         * with either way.
         */
        const val LEAST_COLLECTIVE = 0.15
        const val MOST_COLLECTIVE = 0.9

        /**
         * Nearer than this to its spot, in metres, and slower than this, in m/s, a rotorcraft is over
         * it and comes down.
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

        /** A rotorcraft's or airship's sink wanted, m/s per metre up, the most, and at the ground. */
        const val HOVER_SINK_PER_METRE = 0.1
        const val HOVER_MOST_SINK = 3.0
        const val HOVER_TOUCHDOWN = 0.4

    }
}
