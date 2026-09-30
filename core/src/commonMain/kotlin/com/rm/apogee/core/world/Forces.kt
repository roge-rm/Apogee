package com.rm.apogee.core.world

import com.rm.apogee.core.weather.Weather
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.core.part.AeroSurface
import com.rm.apogee.core.part.Engine
import com.rm.apogee.core.part.Parachute
import com.rm.apogee.core.part.Rcs
import com.rm.apogee.core.part.Sail
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * Everything that pushes on a vessel.
 *
 * It's one stateless object with scratch space made up front, instead of a family of force classes,
 * because this runs for every vessel every tick, and the overhead of polymorphic force objects
 * would cost more than the whole rest of the step.
 */
class Forces {

    /**
     * The sea's height at body-fixed point for a craft, from the waves the water pass keeps for it,
     * or NaN when there aren't any fresh enough or there's no open water under it. Working the sea
     * out afresh at every propeller, every step, cost a phone a good share of a tick.
     */
    var waterAt: ((Vessel, CelestialBody, Vec3, Double) -> Double)? = null

    /** Standard gravity, only used to turn specific impulse into mass flow. */
    private val g0 = 9.80665

    private val scratchDirection = Vec3()
    private val scratchOffset = Vec3()
    private val scratchForce = Vec3()
    private val scratchVelocity = Vec3()
    private val scratchSurface = Vec3()
    private val scratchWind = Vec3()
    private val scratchGust = Vec3()
    private val scratchGustPoint = Vec3()
    private val scratchLocalVelocity = Vec3()
    private val scratchAxis = Vec3()
    private val scratchCrossFlow = Vec3()
    private val scratchRadial = Vec3()
    private val scratchNormal = Vec3()
    private val scratchTorqueAxis = Vec3()
    private val scratchCommand = Vec3()
    private val scratchTorque = Vec3()
    private val gimbalRotation = Quat()
    private val gimbalPitch = Quat()
    private val gimbalYaw = Quat()

    /** Newton-seconds of propellant burned this tick, for telemetry. */
    var lastMassFlow: Double = 0.0
        private set

    /**
     * Parachute parts torn off by opening too fast this call.
     *
     * These are indices instead of events, because [Forces] applies forces and shouldn't know what
     * a world event is. The world turns these into events. It's a fixed array for the same reason
     * [com.rm.apogee.core.physics.ContactReport] uses one: this runs for every vessel every tick
     * and mustn't allocate.
     */
    val tornParachutes = IntArray(MAX_TORN)

    /**
     * Wings and control surfaces that failed under load in the last drag pass, [overstressedCount]
     * of them.
     */
    val overstressed = IntArray(MAX_TORN)
    var overstressedCount = 0
        private set
    var tornCount: Int = 0
        private set

    fun applyGravity(vessel: Vessel, attractor: CelestialBody) {
        attractor.gravityAt(vessel.body.position, scratchForce)
        scratchForce.mulInPlace(vessel.body.mass)
        vessel.body.applyCentralForce(scratchForce)
    }

    /**
     * Fires every lit engine, using up propellant and applying thrust at each engine's own
     * position, so an uneven burn really does yaw the craft.
     *
     * Thrust and Isp are blended against ambient pressure. That's the mechanic that makes staging
     * matter. A lifter tuned for sea level loses efficiency in vacuum, and a vacuum engine is
     * nearly useless on the pad.
     */
    fun applyThrust(vessel: Vessel, attractor: CelestialBody, dt: Double, time: Double = 0.0) {
        lastMassFlow = 0.0
        vessel.fitPose()
        vessel.engineOutput.fill(0.0)
        val throttle = vessel.control.throttle
        if (throttle <= 0.0) return

        val altitude = attractor.altitudeOf(vessel.body.position)
        val pressureRatio = attractor.atmosphere?.pressureRatioAt(altitude) ?: 0.0

        for (partIndex in vessel.activeEngines()) {
            val engine = vessel.defs[partIndex].module<Engine>() ?: continue

            // A water propeller only pushes for as much of it as is under water.
            val immersion = engine.waterProp?.let { immersion(vessel, partIndex, it, attractor, time) } ?: 1.0
            if (immersion <= 0.0) continue

            val effectiveThrottle =
                (if (throttle < engine.minThrottle) engine.minThrottle else throttle) * immersion

            // A propeller has to get up to speed, and pushes with how fast it's turning, so what
            // it's doing shows in its blades. A rocket or a jet answers the throttle at once.
            val propeller = engine.exhaustKind == com.rm.apogee.core.part.Exhaust.PROP ||
                engine.exhaustKind == com.rm.apogee.core.part.Exhaust.WATER
            val spunBefore = if (propeller) vessel.spool.getOrElse(partIndex) { 0.0 } else 0.0
            val drive = if (propeller) {
                spinTo(spunBefore, effectiveThrottle, dt).also { if (partIndex < vessel.spool.size) vessel.spool[partIndex] = it }
            } else effectiveThrottle

            val thrustMagnitude = lerp(engine.thrustVacuum, engine.thrustSeaLevel, pressureRatio) *
                drive
            val isp = lerp(engine.ispVacuum, engine.ispSeaLevel, pressureRatio)
            if (thrustMagnitude <= 0.0 || isp <= 0.0) continue

            // Mass flow from the rocket equation's definition of Isp.
            var massFlow = thrustMagnitude / (isp * g0)
            val electric = engine.propellant == com.rm.apogee.core.part.ResourceType.ELECTRIC_CHARGE
            val unitsNeeded: Double
            val unitsDrawn: Double
            if (electric) {
                // A motor running on batteries. Charge weighs nothing, so its "isp" is just how
                // many newton-seconds a unit of charge buys, and it runs on what the whole craft
                // holds or not at all.
                unitsNeeded = massFlow * dt
                unitsDrawn = if (vessel.drawCharge(unitsNeeded)) unitsNeeded else 0.0
                massFlow = 0.0
            } else {
                unitsNeeded = massFlow * dt / engine.propellant.densityPerUnit
                unitsDrawn = vessel.drainFromGroupOf(partIndex, engine.propellant, unitsNeeded)
            }
            if (unitsDrawn <= 0.0) {
                // Out of fuel, a propeller winds down instead.
                if (propeller && partIndex < vessel.spool.size) vessel.spool[partIndex] = spinTo(spunBefore, 0.0, dt)
                continue
            }

            // An engine that isn't getting enough feed makes proportionally less thrust instead of
            // cutting out, so the last fraction of a second of a tank tapers off instead of
            // dropping off a cliff.
            val feedFraction = if (unitsNeeded > 0.0) unitsDrawn / unitsNeeded else 0.0
            val actualThrust = thrustMagnitude * feedFraction
            lastMassFlow += massFlow * feedFraction
            vessel.engineOutput[partIndex] = drive * feedFraction

            // Something pushing on the water steers with less of its swing once the turn is pulling
            // the boat hard sideways. Full lock at thirty knots threw a jet boat round so hard the
            // water tripped her over.
            val steer = if (engine.waterProp != null) {
                waterSteer(vessel, attractor)
            } else 1.0
            gimballedDirection(vessel, engine, scratchDirection, steer)
            vessel.body.orientation.rotate(scratchDirection, scratchDirection)

            scratchForce.setTo(scratchDirection).mulInPlace(actualThrust)
            vessel.partOffsetWorld(partIndex, scratchOffset)
            vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
            vessel.recordForce(partIndex, scratchForce)
        }
    }

    /**
     * Fires the thruster blocks: to slide the craft, and when armed, to help turn it whenever it's
     * being steered, by the stick or by SAS.
     *
     * Sliding goes through the centre of mass, so it doesn't rotate the craft too. Applying each
     * block's thrust at its own position was tried first, on the idea that placement should matter.
     * It doesn't survive the geometry. Four blocks ringing a craft *below* its centre of mass all
     * push sideways with the same lever arm, so their torques add up instead of cancelling, and a
     * perfectly symmetric set tumbled at 0.045 rad/s. Real thruster blocks have nozzles facing
     * several ways and a control law picks the combination that moves the craft cleanly. This
     * models what that control law achieves instead of the plumbing underneath it.
     *
     * Turning works the same way. Each block pushes across the turn where it sits, and the craft
     * gets the torque that makes (thrust times how far the block is from the axis) as a pure turn.
     * Placement does matter here: blocks far from the middle turn it harder.
     *
     * Each block's push, sliding and turning together, is at most its thrust. It draws
     * monopropellant from the block's own fuel group, and tapers with what it actually gets instead
     * of cutting out, so running dry is a fade.
     */
    fun applyRcs(vessel: Vessel, dt: Double) {
        vessel.fitPose()
        val firing = vessel.rcsFiring
        firing.fill(0.0)
        val control = vessel.control
        // A jetpack is for flying. On their feet, it's off.
        if (!control.rcsEnabled || vessel.onFeet || vessel.swimming) return

        // The slide asked for, in the craft's own axes. It's never more than one block's worth
        // however the axes combine, because a diagonal is just a direction.
        scratchDirection.setTo(control.translateX, control.translateY, control.translateZ)
        val demand = scratchDirection.length
        var slide = demand.coerceAtMost(1.0)
        if (demand > 1e-6) scratchDirection.mulInPlace(1.0 / demand)
        // Standing on the ground, a slide pushes at the centre of mass while the feet grip, so a
        // tall craft rocks and past a point goes over. A landed tug tipped over at anything above a
        // third of its thrust, SAS or not. So the control law watches it rock and eases off the
        // slide the way a pilot would, walking it along instead of pushing it over. Only the push
        // along the ground is eased. Lifting takes weight off the legs, which is how you walk a
        // module, so that's left alone.
        if (slide > 0.0 && vessel.touchingGround) {
            scratchNormal.setTo(vessel.body.position).normalizeInPlace()
            scratchAxis.setTo(vessel.body.angularVelocity)
            scratchAxis.addScaledInPlace(scratchNormal, -(scratchAxis dot scratchNormal))
            val rocking = ((scratchAxis.length - ROCK_ALLOWED) / ROCK_SPAN).coerceIn(0.0, 1.0)
            if (rocking > 0.0) {
                // Up, in the craft's own axes, with the push split into up and along.
                vessel.body.orientation.inverseRotate(scratchNormal, scratchNormal)
                val lift = scratchDirection dot scratchNormal
                scratchDirection.addScaledInPlace(scratchNormal, -lift).mulInPlace(1.0 - rocking)
                scratchDirection.addScaledInPlace(scratchNormal, lift)
                val left = scratchDirection.length
                if (left < 1e-9) slide = 0.0 else {
                    slide *= left
                    scratchDirection.mulInPlace(1.0 / left)
                }
            }
        }

        // The turn asked for, around the craft's own axes (x pitch, y roll, z yaw): the stick's or
        // SAS's command, or with SAS on and nothing to hold, against the spin, the same way the
        // reaction wheels damp it.
        if (control.sasEnabled && !control.stickOverrides && !vessel.assistHolding) {
            vessel.body.orientation.inverseRotate(vessel.body.angularVelocity, scratchCommand)
            val spin = scratchCommand.length
            if (spin > 1e-6) scratchCommand.mulInPlace(-(spin / SAS_SATURATION_RATE).coerceAtMost(1.0) / spin)
            else scratchCommand.setZero()
        } else {
            scratchCommand.setTo(control.commandPitch, control.commandRoll, control.commandYaw)
        }
        val turn = scratchCommand.length.coerceAtMost(1.0)
        if (slide < 1e-6 && turn < 1e-6) return
        if (turn > 1e-6) scratchTorqueAxis.setTo(scratchCommand).normalizeInPlace()

        var slideForce = 0.0
        scratchTorque.setZero()
        vessel.centerOfMass(scratchRadial)
        for (partIndex in vessel.defs.indices) {
            // Not gated on staging, unlike an engine. A thruster block is plumbing, not a step in a
            // sequence. It works from the moment it's bolted on, like reaction wheels do, and
            // having to remember to stage it before nudging a module into place would be a puzzle
            // with no answer worth finding.
            if (vessel.isBroken(partIndex)) continue
            val rcs = vessel.defs[partIndex].module<Rcs>() ?: continue

            // This block's push: across the turn where it sits (turn axis x its position from the
            // middle), since a block on the axis itself can't help turn, then the slide with
            // whatever thrust is left. The turn comes first, like a real control law puts attitude
            // first. At full slide on the ground the thrusters used to have nothing left to keep
            // the craft upright, and over it went.
            scratchOffset.setTo(vessel.design.parts[partIndex].position).subInPlace(scratchRadial)
            scratchForce.setZero()
            var lever = 0.0
            if (turn > 1e-6) {
                scratchAxis.setTo(scratchTorqueAxis).crossInPlace(scratchOffset)
                lever = scratchAxis.length
                if (lever > 1e-3) scratchForce.addScaledInPlace(scratchAxis, turn / lever)
            }
            // The most slide that fits alongside the turn: |a d + t| = 1, where d is the slide's
            // direction (unit) and t is the turn's push (at most 1).
            val dt2 = scratchDirection dot scratchForce
            val room = (dt2 * dt2 - scratchForce.lengthSq + 1.0).coerceAtLeast(0.0)
            val slideHere = if (slide < 1e-6) 0.0 else minOf(slide, (-dt2 + kotlin.math.sqrt(room)).coerceAtLeast(0.0))
            scratchForce.addScaledInPlace(scratchDirection, slideHere)
            val share = scratchForce.length
            if (share < 1e-6) continue
            val scale = if (share > 1.0) 1.0 / share else 1.0

            val thrust = rcs.thrust * share * scale
            val massFlow = thrust / (rcs.isp * g0)
            val unitsNeeded = massFlow * dt / rcs.propellant.densityPerUnit
            val unitsDrawn = vessel.drainFromGroupOf(partIndex, rcs.propellant, unitsNeeded)
            val feed = if (unitsNeeded > 0.0) unitsDrawn / unitsNeeded else 0.0
            if (feed <= 0.0) continue

            val k = scale * feed
            firing[partIndex * 3] = scratchForce.x * k
            firing[partIndex * 3 + 1] = scratchForce.y * k
            firing[partIndex * 3 + 2] = scratchForce.z * k
            slideForce += rcs.thrust * slideHere * k
            if (lever > 1e-3) scratchTorque.addScaledInPlace(scratchTorqueAxis, rcs.thrust * turn * k * lever)
        }

        if (slideForce > 0.0) {
            vessel.body.orientation.rotate(scratchDirection, scratchAxis)
            scratchForce.setTo(scratchAxis).mulInPlace(slideForce)
            vessel.body.applyCentralForce(scratchForce)
        }
        if (scratchTorque.lengthSq > 0.0) {
            vessel.body.orientation.rotate(scratchTorque, scratchTorque)
            vessel.body.applyTorque(scratchTorque)
        }
    }

    /**
     * Swings an engine's thrust axis by the control input, within its gimbal range. This is what
     * steers a rocket while it's still going fast enough that fins don't matter, but too fast for
     * reaction wheels to matter.
     */
    /**
     * How far under the water part-local [point] of part [partIndex] is: 0 in air, up to 1 at a
     * propeller's depth ([PROP_IMMERSION_DEPTH]) or more. No sea, or ground above the water here,
     * gives 0.
     */
    private fun immersion(vessel: Vessel, partIndex: Int, point: Vec3, attractor: CelestialBody, time: Double): Double {
        val ocean = attractor.ocean ?: return 0.0
        vessel.partPointOffsetWorld(partIndex, point, scratchWaterPoint).addInPlace(vessel.body.position)
        attractor.rotationAt(time, scratchWaterRotation)
        attractor.toBodyFixed(scratchWaterPoint, scratchWaterRotation, scratchWaterDirection)
        // The craft's own waves, when they've been worked out lately, over open water.
        val kept = waterAt?.invoke(vessel, attractor, scratchWaterDirection, time) ?: Double.NaN
        if (!kept.isNaN()) return ((attractor.radius + kept - scratchWaterPoint.length) / PROP_IMMERSION_DEPTH).coerceIn(0.0, 1.0)
        val surface = ocean.surfaceHeight(scratchWaterDirection, time)
        val terrain = attractor.terrain
        if (terrain != null && terrain.elevation(scratchWaterDirection) >= surface) return 0.0
        val depth = attractor.radius + surface - scratchWaterPoint.length
        return (depth / PROP_IMMERSION_DEPTH).coerceIn(0.0, 1.0)
    }

    private val scratchWaterPoint = Vec3()
    private val scratchWaterDirection = Vec3()
    private val scratchWaterRotation = Quat.identity()

    private val scratchWaterSpeed = Vec3()

    /**
     * The share of its steering a craft on the water gets: all of it, until it's turning so fast
     * for its speed that the turn pulls it sideways at [WATER_TURN_PULL], and less as it comes up
     * to that, the way a helmsman eases the wheel before the boat trips over itself. A light boat
     * at thirty knots got there in a moment, and a big slow one never does. He eases it as she
     * heels past [WATER_HEEL_FROM] as well: a boat with skegs that grip well rolls out over a turn
     * long before it pulls her sideways that hard.
     */
    private fun waterSteer(vessel: Vessel, attractor: CelestialBody): Double {
        attractor.surfaceVelocityAt(vessel.body.position, scratchWaterSpeed).negateInPlace().addInPlace(vessel.body.linearVelocity)
        val speed = scratchWaterSpeed.length
        if (speed < 1.0) return 1.0
        scratchWaterSpeed.setTo(vessel.body.position).normalizeInPlace()
        val turning = kotlin.math.abs(vessel.body.angularVelocity dot scratchWaterSpeed)
        val most = WATER_TURN_PULL / speed
        val byTurn = ((most - turning) / (most * WATER_TURN_EASE)).coerceIn(0.0, 1.0)
        vessel.body.orientation.rotate(vessel.design.orientation.up, scratchWaterDirection)
        val heel = kotlin.math.acos((scratchWaterDirection dot scratchWaterSpeed).coerceIn(-1.0, 1.0))
        val byHeel = ((WATER_HEEL_FROM + WATER_HEEL_OVER - heel) / WATER_HEEL_OVER).coerceIn(0.0, 1.0)
        return byTurn * byHeel
    }

    /** [engine]'s thrust, swung by the stick through [share] of its gimbal range, in its part's axes. */
    private fun gimballedDirection(vessel: Vessel, engine: Engine, out: Vec3, share: Double = 1.0): Vec3 {
        if (engine.gimbalRange <= 0.0) return out.setTo(engine.thrustDirection)

        val range = Math.toRadians(engine.gimbalRange) * share
        // Negated, and that sign matters a lot. An engine sits *below* the centre of mass, so
        // swinging its thrust toward +Z makes a torque around -X, which is the opposite of what a
        // reaction wheel does for the same positive pitch command. Without the negation the two
        // fight each other, and a craft with both is *less* controllable than one with either.
        // ForcesTest pins this.
        Quat.fromAxisAngle(Vec3.unitX(), -vessel.control.commandPitch * range, gimbalPitch)
        Quat.fromAxisAngle(Vec3.unitZ(), -vessel.control.commandYaw * range, gimbalYaw)
        gimbalRotation.setTo(gimbalPitch).mulInPlace(gimbalYaw)
        return gimbalRotation.rotate(engine.thrustDirection, out)
    }

    /**
     * Reaction wheel authority: torque without reaction mass.
     *
     * The convention matches the mesh axes. +Y is the nose, so roll is around Y, pitch around X and
     * yaw around Z.
     */
    fun applyReactionWheels(vessel: Vessel, attractor: CelestialBody? = null) {
        vessel.wheelWork = 0.0
        // No charge, no wheels.
        if (!vessel.powered) return
        var authority = 0.0
        for (i in vessel.defs.indices) {
            if (vessel.isBroken(i)) continue
            authority += vessel.defs[i]
                .module<com.rm.apogee.core.part.Command>()
                ?.reactionTorque ?: 0.0
        }
        if (authority <= 0.0) return

        val control = vessel.control
        // SAS with nothing to hold (on the ground, or before a hold has been taken) falls back to
        // bleeding off rotation.
        if (control.sasEnabled && !control.stickOverrides && !vessel.assistHolding) {
            dampRotation(vessel, authority)
            vessel.wheelWork = authority * DAMPING_WORK
            return
        }
        vessel.wheelWork = authority * (kotlin.math.abs(control.commandPitch) + kotlin.math.abs(control.commandRoll) + kotlin.math.abs(control.commandYaw)).coerceAtMost(1.0)

        // On foot, the stick walks, so only turning around their own height (their roll) is left to
        // the wheels.
        val tip = if (vessel.onFeet || vessel.swimming) 0.0 else 1.0
        // A boat afloat is given less turn when she's already turning hard for her speed, the same
        // as a motor's swing: at thirty knots a seat's wheels alone spun a light boat round so hard
        // the water tripped her. (A boat: something with a hull. A drone that dips a foot in the
        // sea isn't one.)
        var turn = tip
        if (attractor != null && vessel.buoyed && !vessel.submerged && vessel.defs.any { it.hasModule<com.rm.apogee.core.part.Buoyancy>() }) {
            turn *= waterSteer(vessel, attractor)
        }
        scratchTorque.setTo(
            control.commandPitch * authority * tip,
            control.commandRoll * authority,
            control.commandYaw * authority * turn,
        )
        vessel.body.orientation.rotate(scratchTorque, scratchTorque)
        vessel.body.applyTorque(scratchTorque)
    }

    /**
     * Stability assist with no input: bleed off rotation.
     *
     * The torque opposes the angular velocity and is capped at the craft's authority, so SAS
     * settles a tumble instead of freezing it instantly, and it can't overshoot into an oscillation
     * by applying more torque than it has.
     */
    private fun dampRotation(vessel: Vessel, authority: Double) {
        val omega = vessel.body.angularVelocity
        val magnitude = omega.length
        if (magnitude < 1e-6) return

        // Scale in as the rotation grows, maxing out at full authority.
        val strength = (magnitude / SAS_SATURATION_RATE).coerceAtMost(1.0)
        scratchTorque.setTo(omega)
            .mulInPlace(-authority * strength / magnitude)
        vessel.body.applyTorque(scratchTorque)
    }

    /**
     * Atmospheric drag, applied part by part.
     *
     * Each part gets its own drag area and its force is applied at its own position, instead of one
     * force through the centre of mass. That difference is what makes fins work. A force at the
     * centre makes no torque wherever the fins are, so a finned rocket flew exactly like one
     * without fins and the fins were just dead weight.
     *
     * There are three kinds of area, because they behave differently:
     *
     * - **Bodies** are corrected for shielding. Adding up every tank's frontal area would count a
     *   ten-tank stack as ten tanks of frontal area when nine of them sit in the first one's wake,
     *   so the stack's total is taken from its largest cross-section and shared out between them.
     * - **Aerodynamic surfaces** use their declared planform area, not their frontal cross-section.
     *   A fin is millimetres thick edge-on, and judged by its bounding box it would add almost
     *   nothing, which is exactly the wrong answer for the part whose whole job is aerodynamic
     *   control. They aren't shielded either, because they stick out into clean air.
     * - **Parachutes** use their own, and dominate everything when open.
     *
     * Each part's local velocity includes the craft's rotation, so aerodynamic damping comes out of
     * the same loop.
     */
    /**
     * The craft's whole drag area, Cd x A in m², the way [applyDrag] would find it head-on: the
     * stack's body drag, its fins edge-on, and any canopy as far open as it is. This is what a
     * descent gets predicted with (see [com.rm.apogee.core.orbit.Descent]). Lift and wind are left
     * out.
     */
    fun dragArea(vessel: Vessel): Double {
        var maxRadius = 0.0
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        var total = 0.0
        val enclosed = vessel.enclosed()
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (enclosed.getOrElse(i) { false }) continue
            closedShell(vessel, i)?.let { fairing ->
                maxRadius = maxOf(maxRadius, fairing.radius)
                high = maxOf(high, vessel.design.parts[i].position.y + def.boundsHalfExtents.y + fairing.height)
            }
            val surface = def.module<AeroSurface>()
            if (surface != null) { total += surface.area * FIN_PARASITIC_CD; continue }
            val extents = def.boundsHalfExtents
            maxRadius = maxOf(maxRadius, extents.x, extents.z)
            val y = vessel.design.parts[i].position.y
            low = minOf(low, y - extents.y)
            high = maxOf(high, y + extents.y)
            def.module<Parachute>()?.let { chute ->
                val open = vessel.legDeploy.getOrElse(i) { 0.0 }
                if (open > 0.0 && vessel.isWorking(i)) total += chute.deployedDragCoefficient * def.referenceArea * Parachute.dragShare(open)
            }
        }
        val fineness = if (maxRadius > 0.0) (high - low) / (2.0 * maxRadius) else 3.0
        val blunt = ((BLUNT_UNTIL - fineness) / (BLUNT_UNTIL - 1.0)).coerceIn(0.0, 1.0)
        return total + PI * maxRadius * maxRadius * (AVERAGE_BODY_CD + (BLUNT_BODY_CD - AVERAGE_BODY_CD) * blunt)
    }

    /** Part [index]'s fairing, if it has one that's still closed. */
    private fun closedShell(vessel: Vessel, index: Int): com.rm.apogee.core.part.Fairing? =
        vessel.defs[index].module<com.rm.apogee.core.part.Fairing>()?.takeIf { !vessel.activated[index] }

    fun applyDrag(
        vessel: Vessel,
        attractor: CelestialBody,
        /** The air's weather, when it has any. [Vessel.air] holds this tick's sample of it. */
        weather: Weather? = null,
        /** The body's rotation now, because weather is in its turning frame. */
        bodyRotation: Quat? = null,
        time: Double = 0.0,
        /** The tick, in seconds, because a chute fills over time. */
        dt: Double = 1.0 / 60.0,
    ) {
        tornCount = 0
        overstressedCount = 0
        val atmosphere = attractor.atmosphere ?: return
        val altitude = attractor.altitudeOf(vessel.body.position)
        // Cloud water and ice thicken the air a craft has to push through.
        val air = vessel.air
        val density = atmosphere.densityAt(altitude) * air.loading
        if (density <= 0.0) return
        // The speed of sound here, for how the wings lift past it.
        soundSpeed = kotlin.math.sqrt(GAS_RATIO * GAS_CONSTANT * atmosphere.temperatureAt(altitude))

        // The wind, in the world's frame. The air moves with the ground, and on top of that with
        // the weather.
        val windy = bodyRotation != null && air.wind.lengthSq > 0.0
        if (windy) bodyRotation!!.rotate(air.wind, scratchWind) else scratchWind.setZero()
        val gusty = windy && weather != null && air.turbulence > 0.0

        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        scratchSurface.addInPlace(scratchWind)
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        // Still air and a still craft, so there's nothing to do. A craft standing in a gale isn't
        // still relative to the air, so it doesn't get skipped.
        if (scratchVelocity.lengthSq < 1e-6 && !gusty) return

        // Rain: a load straight down, on everything it falls on.
        if (air.precipitation > 0.0) {
            var catchment = 0.0
            for (def in vessel.defs) {
                catchment += def.module<AeroSurface>()?.area ?: (def.boundsHalfExtents.x * def.boundsHalfExtents.z * 4.0)
            }
            scratchForce.setTo(vessel.body.position).normalizeInPlace()
                .mulInPlace(-air.precipitation * RAIN_PRESSURE * catchment)
            vessel.body.applyCentralForce(scratchForce)
        }

        // Flying through rain costs something. Every drop you hit has to be pushed out of the way,
        // and a wet skin is a rough one. It's drag only and adds no lift.
        val rainDrag = 1.0 + air.precipitation * RAIN_DRAG

        // Pass one: the stack's body drag, corrected for shielding. Whatever rides inside a closed
        // fairing is out of the air, and the fairing's shell counts instead, as wide and as tall as
        // it stands.
        val enclosed = vessel.enclosed()
        var maxRadius = 0.0
        var bodySum = 0.0
        var low = Double.MAX_VALUE
        var high = -Double.MAX_VALUE
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (def.module<AeroSurface>() != null || def.module<Sail>() != null || enclosed.getOrElse(i) { false }) continue
            val extents = def.boundsHalfExtents
            maxRadius = maxOf(maxRadius, extents.x, extents.z)
            bodySum += def.referenceArea * def.dragCoefficient
            val y = vessel.design.parts[i].position.y
            low = minOf(low, y - extents.y)
            high = maxOf(high, y + extents.y)
            closedShell(vessel, i)?.let { fairing ->
                maxRadius = maxOf(maxRadius, fairing.radius)
                high = maxOf(high, y + extents.y + fairing.height)
            }
        }
        // Stubby is draggy. A capsule falling on its shield pushes a wall of air ahead of it, while
        // a slender rocket slips through. Without this, a pod on its own fell at 400 m/s a
        // kilometre up, and its chute, which waits for a safe speed, never got to open before it
        // hit.
        val fineness = if (maxRadius > 0.0) (high - low) / (2.0 * maxRadius) else 3.0
        val blunt = ((BLUNT_UNTIL - fineness) / (BLUNT_UNTIL - 1.0)).coerceIn(0.0, 1.0)
        val bodyCd = AVERAGE_BODY_CD + (BLUNT_BODY_CD - AVERAGE_BODY_CD) * blunt
        val bodyCdA = PI * maxRadius * maxRadius * bodyCd
        val bodyScale = if (bodySum > 0.0) bodyCdA / bodySum else 0.0

        // The stack axis, for splitting the airflow into along the body and across it.
        vessel.body.orientation.rotate(Vec3.unitY(), scratchAxis)

        // Pass two: each part's own force, at its own place on the craft.
        for (i in vessel.defs.indices) {
            val def = vessel.defs[i]
            if (enclosed.getOrElse(i) { false }) continue

            vessel.partOffsetWorld(i, scratchOffset)
            vessel.body.velocityAtOffset(scratchOffset, scratchLocalVelocity)
            scratchLocalVelocity.subInPlace(scratchSurface)
            // Gusts, part by part, so a wing tip and a tail each meet their own.
            if (gusty) {
                scratchGustPoint.setTo(vessel.body.position).addInPlace(scratchOffset)
                bodyRotation!!.inverseRotate(scratchGustPoint, scratchGustPoint)
                weather!!.turbulence(scratchGustPoint, time, air.wind, air.turbulence, scratchGust)
                bodyRotation.rotate(scratchGust, scratchGust)
                scratchLocalVelocity.subInPlace(scratchGust)
            }
            val localSpeed = scratchLocalVelocity.length
            if (localSpeed < 1e-6) continue

            // A sail makes its own lift and drag.
            def.module<Sail>()?.let { sail ->
                sail(vessel, i, sail, density)
                continue
            }

            // A surface that has failed makes no lift. It just hangs there as drag.
            val surface = def.module<AeroSurface>()?.takeIf { !vessel.isBroken(i) }

            // Drag along the airflow.
            var cdA = if (surface != null) {
                // A fin edge-on to the airflow has almost no drag. Charging it its planform area
                // times a *lift* coefficient, which is what the first version of this did, made
                // four fins cost five times the drag of the whole rocket body, and the stock craft
                // stopped reaching orbit.
                surface.area * FIN_PARASITIC_CD
            } else {
                def.referenceArea * def.dragCoefficient * bodyScale
            }
            def.module<Parachute>()?.let { parachute ->
                // Once staged it's armed, and it opens by itself when it's safe. This happens here
                // because this is the only place a part's airspeed is already known.
                val state = vessel.legDeploy.getOrElse(i) { 0.0 }
                if (vessel.isWorking(i)) when {
                    state < 0.0 -> Unit // Cut away after landing.
                    state == 0.0 -> {
                        if (localSpeed <= parachute.maxDeploymentSpeed * Parachute.OPEN_SHARE &&
                            density >= Parachute.OPEN_DENSITY
                        ) vessel.setLegDeploy(i, dt / Parachute.INFLATE_SECONDS)
                    }
                    localSpeed > parachute.maxDeploymentSpeed -> {
                        // Open, and going too fast for it, so it gets torn away.
                        if (vessel.breakPart(i) && tornCount < MAX_TORN) {
                            tornParachutes[tornCount++] = i
                        }
                    }
                    // Down, and either slow or down for a moment, so it gets cut away. With only
                    // the slow check, a pod landed in a breeze got dragged along the ground by its
                    // full canopy for half a minute.
                    vessel.touchingGround && (localSpeed < Parachute.CUT_SPEED || vessel.groundedSeconds > Parachute.CUT_AFTER) ->
                        vessel.setLegDeploy(i, -1.0)
                    // Down in the sea, so let go straight away. If it stayed on, the canopy towed a
                    // capsule on its side across the waves in a wind.
                    vessel.buoyed && !vessel.touchingGround ->
                        vessel.setLegDeploy(i, -1.0)
                    else -> {
                        // Two stages. The drogue fills and holds for a fast, steady fall until the
                        // ground is close, then the main fills and lets it down gently for the last
                        // few hundred metres.
                        val main = state >= Parachute.DROGUE_FULL && heightAboveSurface(vessel, attractor, time) < Parachute.MAIN_HEIGHT
                        val open = if (state < Parachute.DROGUE_FULL) minOf(Parachute.DROGUE_FULL, state + 0.5 * dt / Parachute.INFLATE_SECONDS)
                            else if (main || state > Parachute.DROGUE_FULL + 0.02) minOf(1.0, state + 0.5 * dt / Parachute.INFLATE_SECONDS)
                            else state
                        vessel.setLegDeploy(i, open)
                        // Reefed. However fast it opens, it pulls no harder than a few g. A canopy
                        // big enough to land under, opened at 250 m/s, would otherwise stop the
                        // craft dead in a fraction of a second.
                        val full = parachute.deployedDragCoefficient * def.referenceArea * Parachute.dragShare(open)
                        val pull = 0.5 * density * localSpeed * localSpeed * full
                        val most = Parachute.MOST_PULL_G * STANDARD_GRAVITY * vessel.body.mass
                        val chuteCdA = if (pull > most) most / (0.5 * density * localSpeed * localSpeed) else full
                        cdA += chuteCdA
                    }
                }
            }

            if (cdA > 0.0) {
                val magnitude = 0.5 * density * localSpeed * localSpeed * cdA * rainDrag
                scratchForce.setTo(scratchLocalVelocity).mulInPlace(-magnitude / localSpeed)
                vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
                vessel.recordForce(i, scratchForce)
            }

            // Cross-flow force on aerodynamic surfaces.
            //
            // This is where a fin earns its mass. Only the part of the airflow *across* the stack
            // acts on it, so a craft flying straight feels nothing, and one at an angle of attack
            // feels a force pushing the fin back into line. Applied at the fin's position, behind
            // the centre of mass, that becomes the restoring torque that keeps the pointy end
            // forward.
            if (surface != null) {
                val along = scratchLocalVelocity dot scratchAxis
                scratchCrossFlow.setTo(scratchLocalVelocity)
                    .addScaledInPlace(scratchAxis, -along)
                val crossSpeed = scratchCrossFlow.length
                // Cross-flow times along-flow, not cross-flow squared.
                //
                // This is the flat-plate normal force, proportional to sin(a)cos(a) instead of
                // sin(a)^2, and the difference isn't a refinement. At the two or three degrees a
                // wing actually cruises at, squaring the cross-flow gives roughly a thirtieth of
                // the real force. Fins got away with it because a rocket only needs them when it's
                // already badly out of line. A wing has to hold an aircraft up at small angles, and
                // couldn't.
                //
                // And it rises with the angle at a real wing's lift slope, up to the surface's
                // [AeroSurface.liftCoefficient], which is the most lift it makes, where it stalls.
                // That number was once used as the slope, and every wing made a fifth of the lift
                // a real one does: the Sparrow needed seventy-eight metres a second to leave the
                // ground.
                val normalForce = if (crossSpeed > 1e-6) {
                    val attack = kotlin.math.atan2(crossSpeed, abs(along))
                    val speed = kotlin.math.sqrt(crossSpeed * crossSpeed + along * along)
                    0.5 * density * speed * speed * surface.area * normalCoefficient(attack, surface.liftCoefficient, slopeAt(speed / soundSpeed))
                } else 0.0
                if (crossSpeed > 1e-6) {
                    scratchForce.setTo(scratchCrossFlow)
                        .mulInPlace(-normalForce / crossSpeed)
                    vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
                    vessel.recordForce(i, scratchForce)
                }

                val flapForce = flaps(vessel, i, surface, density)

                val controlForce = if (surface.controllable) {
                    deflect(vessel, i, surface, density, localSpeed)
                } else 0.0

                // How hard it's working, for the stress pass to report.
                val airLoad = abs(normalForce) + abs(controlForce) + flapForce
                if (i < vessel.surfaceLoad.size) vessel.surfaceLoad[i] = (airLoad / surface.loadLimit).toFloat()
                // Past what it was built for, like a gust at speed or a hard pull in rough air, it
                // snaps off, and the world tears it away.
                if (airLoad > surface.loadLimit &&
                    overstressedCount < MAX_TORN
                ) {
                    overstressed[overstressedCount++] = i
                }
            }
        }
    }

    /**
     * Flaps down on part [partIndex]: the extra lift they give, square to the airflow on the side the
     * sky is on, and the extra drag. It returns the lift, for the surface's load.
     *
     * The flat-plate lift above only comes from the angle of attack. Flaps add camber, which lifts
     * even with the wing flying straight into the airflow, so a plane can hold itself up at a
     * slower speed and a lower nose, and land shorter. They cost drag while they do it.
     * [scratchLocalVelocity] is the part's airflow, as the drag pass left it.
     */
    /**
     * How hard the air pushes square to a surface meeting it at [attack] radians (0 edge on, pi/2
     * broadside), as a share of the dynamic pressure times its area, for a surface whose most lift
     * is [most].
     *
     * Before it stalls it's a real wing's: the lift slope times the flat plate's sin(a)cos(a),
     * which is the slope times the angle at the few degrees a wing flies at. It stalls where that
     * reaches [most], about fifteen degrees for a wing's 1.4, and drops to [STALL_KEEP] of it. Past
     * that it's a flat plate held into the wind, up to [PLATE_BROADSIDE] square on.
     */
    fun normalCoefficient(attack: Double, most: Double, slope: Double = LIFT_SLOPE): Double {
        val stall = 0.5 * kotlin.math.asin((2.0 * most / slope).coerceAtMost(1.0))
        return if (attack < stall) slope * kotlin.math.sin(attack) * kotlin.math.cos(attack)
        else maxOf(most * STALL_KEEP, PLATE_BROADSIDE * kotlin.math.sin(attack))
    }

    /** The speed of sound where the craft is, this pass, in m/s. */
    private var soundSpeed = 340.0

    private fun flaps(vessel: Vessel, partIndex: Int, surface: AeroSurface, density: Double): Double {
        val out = vessel.flapPosition.getOrElse(partIndex) { 0.0 }
        if (out <= 0.0 || surface.flapLift <= 0.0) return 0.0
        val speed = scratchLocalVelocity.length
        if (speed < 1e-3) return 0.0
        // The part's motion through the air, unit length.
        scratchFlapFlow.setTo(scratchLocalVelocity).mulInPlace(1.0 / speed)
        // Only the motion along the wing's chord counts (leading edge at +Y), and a wing flying
        // backwards gets nothing.
        vessel.design.parts[partIndex].rotation.rotate(Vec3.unitY(), scratchFlapChord)
        vessel.body.orientation.rotate(scratchFlapChord, scratchFlapChord)
        val along = scratchLocalVelocity dot scratchFlapChord
        if (along <= 0.0) return 0.0
        val pressure = 0.5 * density * along * along * surface.area * out
        // The plate's normal, turned to the sky's side and made square to the airflow.
        vessel.design.parts[partIndex].rotation.rotate(Vec3.unitZ(), scratchFlapLift)
        vessel.body.orientation.rotate(scratchFlapLift, scratchFlapLift)
        if ((scratchFlapLift dot vessel.body.position) < 0.0) scratchFlapLift.negateInPlace()
        scratchFlapLift.addScaledInPlace(scratchFlapFlow, -(scratchFlapLift dot scratchFlapFlow))
        val sideways = scratchFlapLift.length
        if (sideways < 1e-6) return 0.0
        scratchFlapLift.mulInPlace(1.0 / sideways)
        val lift = pressure * surface.flapLift * sideways
        scratchForce.setTo(scratchFlapLift).mulInPlace(lift)
            .addScaledInPlace(scratchFlapFlow, -pressure * surface.flapDrag)
        vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
        vessel.recordForce(partIndex, scratchForce)
        return lift
    }

    /**
     * The wind's push on sail [partIndex], trimmed to drive the boat as well as it can.
     * [scratchLocalVelocity] is the part's motion through the air and [scratchOffset] its place on
     * the craft, as the drag pass left them.
     *
     * It works in the horizontal plane. The wind comes from an angle off the bow, and the sail is
     * swung out to leeward to meet it at [SAIL_ATTACK], as far as the sheet lets it ([SAIL_MOST_OUT]).
     * The air pushes square to the sail, harder the more it's angled to the wind, up to a flat plate
     * square to the flow, plus a little drag along it. Pointed closer to the wind than
     * [SAIL_CLOSEST], it just flaps, and that's only drag.
     */
    private fun sail(vessel: Vessel, partIndex: Int, sail: Sail, density: Double) {
        vessel.sailFill[partIndex] = 0.0
        // A sail needs no staging: it's up whenever it's whole and the sheet is out.
        val set = if (!vessel.isBroken(partIndex) && vessel.groupState(partIndex) >= 0) vessel.control.throttle.coerceIn(0.0, 1.0) else 0.0
        // The sky's direction, and the wind across the deck in the horizontal plane.
        scratchSailUp.setTo(vessel.body.position).normalizeInPlace()
        scratchSailWind.setTo(scratchLocalVelocity).negateInPlace()
        scratchSailWind.addScaledInPlace(scratchSailUp, -(scratchSailWind dot scratchSailUp))
        val wind = scratchSailWind.length
        // The boat's forward, flattened the same way.
        vessel.body.orientation.rotate(vessel.design.orientation.forward, scratchSailForward)
        scratchSailForward.addScaledInPlace(scratchSailUp, -(scratchSailForward dot scratchSailUp))
        val forwardLength = scratchSailForward.length
        if (wind < 0.2 || forwardLength < 1e-3) { settleSail(vessel, partIndex, 0.0); return }
        scratchSailWind.mulInPlace(1.0 / wind)
        scratchSailForward.mulInPlace(1.0 / forwardLength)
        // How far off the bow the wind comes from: 0 dead ahead, pi dead astern.
        val offBow = kotlin.math.acos((-(scratchSailWind dot scratchSailForward)).coerceIn(-1.0, 1.0))
        // Leeward: the side the wind blows the sail out to. Dead ahead or astern, keep the side it
        // was already on.
        scratchSailLee.setTo(scratchSailWind).addScaledInPlace(scratchSailForward, -(scratchSailWind dot scratchSailForward))
        if (scratchSailLee.length < 1e-3) {
            scratchSailLee.setTo(scratchSailUp).crossInPlace(scratchSailForward)
            if (vessel.sailAngle[partIndex] < 0.0) scratchSailLee.negateInPlace()
        }
        scratchSailLee.normalizeInPlace()
        // Set to meet the wind at the best angle, as far out as the sheet goes.
        val out = (offBow - SAIL_ATTACK).coerceIn(0.0, SAIL_MOST_OUT)
        val attack = offBow - out
        val drawing = smooth(SAIL_LUFFS, SAIL_CLOSEST, offBow)
        val normal = if (attack < PI / 4.0) SAIL_NORMAL * kotlin.math.sin(2.0 * attack) else SAIL_NORMAL
        // Eased as she heels, the way a crew spills wind in a gust: past a moderate heel, less of
        // the sail draws the harder she's pressed. Carried full, a sail big enough to move her in
        // light air laid her over in a fresh breeze, lost its drive, and let her round up into
        // the wind and stop.
        vessel.body.orientation.rotate(vessel.design.orientation.up, scratchSailMast)
        val heel = kotlin.math.acos((scratchSailMast dot scratchSailUp).coerceIn(-1.0, 1.0))
        val eased = (1.0 - (heel - SAIL_EASE_FROM) / SAIL_EASE_OVER).coerceIn(SAIL_EASED_MOST, 1.0)
        val pressure = 0.5 * density * wind * wind * sail.area * set * eased
        // Square to the sail, on its leeward side: forward by sin(out) and to leeward by cos(out).
        val push = pressure * normal * drawing
        val drag = pressure * (SAIL_DRAG + SAIL_FLOGGING * (1.0 - drawing))
        scratchForce.setTo(scratchSailForward).mulInPlace(push * kotlin.math.sin(out))
            .addScaledInPlace(scratchSailLee, push * kotlin.math.cos(out))
            .addScaledInPlace(scratchSailWind, drag)
        // At the centre of effort, up the mast.
        vessel.design.parts[partIndex].rotation.rotate(Vec3.unitY(), scratchSailMast)
        vessel.body.orientation.rotate(scratchSailMast, scratchSailMast)
        scratchSailPoint.setTo(scratchOffset).addScaledInPlace(scratchSailMast, sail.effortHeight)
        if (set > 0.0) {
            vessel.body.applyForceAtOffset(scratchForce, scratchSailPoint)
            vessel.recordForce(partIndex, scratchForce)
        }
        vessel.sailFill[partIndex] = set * drawing
        // The chord runs aft from the mast, swung out to leeward: its angle around the mast in the
        // part's own frame, for drawing. Flapping, it streams straight downwind instead.
        scratchSailChord.setTo(scratchSailForward).mulInPlace(-kotlin.math.cos(out)).addScaledInPlace(scratchSailLee, kotlin.math.sin(out))
        if (drawing < 1.0) {
            scratchSailChord.mulInPlace(drawing).addScaledInPlace(scratchSailWind, 1.0 - drawing)
        }
        vessel.body.orientation.inverseRotate(scratchSailChord, scratchSailChord)
        vessel.design.parts[partIndex].rotation.inverseRotate(scratchSailChord, scratchSailChord)
        // Around the mast (+Y), from the rest chord (-Z): positive swings it toward +X.
        settleSail(vessel, partIndex, kotlin.math.atan2(scratchSailChord.x, -scratchSailChord.z))
    }

    private fun settleSail(vessel: Vessel, partIndex: Int, angle: Double) {
        vessel.sailAngle[partIndex] = angle.coerceIn(-PI, PI)
    }

    private fun smooth(from: Double, to: Double, x: Double): Double {
        val t = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private val scratchSailUp = Vec3()
    private val scratchSailWind = Vec3()
    private val scratchSailForward = Vec3()
    private val scratchSailLee = Vec3()
    private val scratchSailMast = Vec3()
    private val scratchSailPoint = Vec3()
    private val scratchSailChord = Vec3()

    private val scratchFlapFlow = Vec3()
    private val scratchFlapChord = Vec3()
    private val scratchFlapLift = Vec3()

    /**
     * A control surface moving with the stick.
     *
     * Without this an aircraft can't fly at all, and not for lack of lift. A tail fin weathervanes
     * the nose into the airflow, which drives the angle of attack to nothing, and a wing at no
     * angle of attack makes no lift. The plane mushes down at forty degrees nose-low, with the fin
     * doing exactly what it was built to do. Holding an angle of attack is the elevator's job, and
     * nothing was doing it. [AeroSurface.controllable] and [AeroSurface.controlAuthority] were
     * carried in the schema and read by nobody.
     *
     * Which way a surface moves comes from where it's bolted, not from a flag saying "elevator".
     * The force it can make is at right angles to both the fuselage and its own mounting radius,
     * the torque that makes is r x F, and the deflection is the pilot's command projected onto that
     * torque. So a surface behind the centre of mass pitches, one out on a wing rolls, and one that
     * can do neither sits still. All of it comes out of the geometry, the same way the thrusters
     * do.
     */
    private fun deflect(
        vessel: Vessel,
        partIndex: Int,
        surface: AeroSurface,
        density: Double,
        airspeed: Double,
    ): Double {
        // How far: worked out once per tick by [controlDeflection], so the force and the surface
        // the players see move by the same amount.
        val deflection = vessel.surfaceDeflection.getOrElse(partIndex) { 0.0 }
        if (abs(deflection) < 1e-6) return 0.0

        // The mounting radius: how far off the fuselage axis this surface is.
        val axial = scratchOffset dot scratchAxis
        scratchRadial.setTo(scratchOffset).addScaledInPlace(scratchAxis, -axial)
        val radialLength = scratchRadial.length
        if (radialLength < 1e-6) return 0.0
        scratchRadial.mulInPlace(1.0 / radialLength)

        // The direction it can push: across both the fuselage and its radius.
        scratchNormal.setTo(scratchAxis).crossInPlace(scratchRadial)
        val normalLength = scratchNormal.length
        if (normalLength < 1e-6) return 0.0
        scratchNormal.mulInPlace(1.0 / normalLength)

        // The same lift curve the surface flies on, at the deflection. Charging the surface's full
        // broadside force made four rocket fins worth tens of kilonewtons at max q.
        val angle = Math.toRadians(surface.maxDeflection) * deflection
        // Blown back by the air at speed: its actuators can't hold it further over than pushes
        // [BLOWBACK_SHARE] of what it's built to carry. Held at full travel at nine hundred metres
        // a second, a stabilator pushed five times that and snapped off.
        val force = (0.5 * density * airspeed * airspeed *
            surface.area * surface.controlAuthority *
            normalCoefficient(abs(angle), surface.liftCoefficient, slopeAt(airspeed / soundSpeed))).coerceAtMost(BLOWBACK_SHARE * surface.loadLimit) * kotlin.math.sign(angle)
        scratchForce.setTo(scratchNormal).mulInPlace(force)
        vessel.body.applyForceAtOffset(scratchForce, scratchOffset)
        vessel.recordForce(partIndex, scratchForce)
        return force
    }

    /**
     * How far control surface [partIndex] moves for the craft's command this tick, -1..1 of its
     * travel, and 0 for anything that isn't a control surface.
     *
     * Which way comes from where it's bolted, like [deflect] explains. The push it can make is
     * across both the fuselage and its own mounting radius, the torque that push makes is r x F,
     * and the deflection is the command projected onto that torque. It's worked in the craft's own
     * axes, so it needs no airflow and no orientation. It's what the stick asks of this surface, on
     * the runway as much as in flight.
     *
     * It isn't normalised. A hair of stick is a hair of deflection, which is what gentle
     * corrections, by a thumb or by stability assist, are made of.
     */
    fun controlDeflection(vessel: Vessel, partIndex: Int): Double {
        val def = vessel.defs[partIndex]
        val controllable = def.module<AeroSurface>()?.controllable == true ||
            def.module<com.rm.apogee.core.part.HydroSurface>()?.controllable == true
        if (!controllable) return 0.0
        val control = vessel.control
        val pitch = control.commandPitch
        val roll = control.commandRoll
        val yaw = control.commandYaw
        if (pitch == 0.0 && yaw == 0.0 && roll == 0.0) return 0.0

        val offset = vessel.centerOfMass(poseOffset)
            .mulInPlace(-1.0).addInPlace(vessel.design.parts[partIndex].position)
        // The mounting radius: how far off the fuselage axis, +Y, it is.
        poseRadial.setTo(offset.x, 0.0, offset.z)
        val radialLength = poseRadial.length
        if (radialLength < 1e-6) return 0.0
        poseRadial.mulInPlace(1.0 / radialLength)
        // The way it can push: across the fuselage and the radius.
        poseNormal.setTo(0.0, 1.0, 0.0).crossInPlace(poseRadial)
        if (poseNormal.length < 1e-6) return 0.0
        poseNormal.normalizeInPlace()
        // The torque a unit push there makes.
        poseTorque.setTo(offset).crossInPlace(poseNormal)
        val torqueLength = poseTorque.length
        if (torqueLength < 1e-6) return 0.0
        poseTorque.mulInPlace(1.0 / torqueLength)
        // Pitch around X, roll around Y, yaw around Z, the same as the reaction wheels.
        return (pitch * poseTorque.x + roll * poseTorque.y + yaw * poseTorque.z).coerceIn(-1.0, 1.0)
    }

    private val poseOffset = Vec3()
    private val poseRadial = Vec3()
    private val poseNormal = Vec3()
    private val poseTorque = Vec3()

    /**
     * Dynamic pressure, in Pa. This is the number that decides whether a craft survives the ascent.
     * It's against the air as it moves, [Vessel.air]'s wind, turned into the world's frame by
     * [bodyRotation].
     */
    fun dynamicPressure(vessel: Vessel, attractor: CelestialBody, bodyRotation: Quat? = null): Double {
        val atmosphere = attractor.atmosphere ?: return 0.0
        val density = atmosphere.densityAt(attractor.altitudeOf(vessel.body.position)) * vessel.air.loading
        if (density <= 0.0) return 0.0
        attractor.surfaceVelocityAt(vessel.body.position, scratchSurface)
        if (bodyRotation != null) scratchSurface.addInPlace(bodyRotation.rotate(vessel.air.wind, scratchWind))
        scratchVelocity.setTo(vessel.body.linearVelocity).subInPlace(scratchSurface)
        return 0.5 * density * scratchVelocity.lengthSq
    }

    /** A propeller's speed [from], a tick of [dt] closer to [to], at a propeller's pace. */
    private fun spinTo(from: Double, to: Double, dt: Double): Double {
        val next = from + (to - from) * (1.0 - kotlin.math.exp(-dt / PROPELLER_SPOOL))
        return if (to == 0.0 && next < 1e-3) 0.0 else next
    }

    private fun lerp(vacuum: Double, seaLevel: Double, pressureRatio: Double): Double =
        vacuum + (seaLevel - vacuum) * pressureRatio.coerceIn(0.0, 1.0)

    private val scratchSurfaceUp = Vec3()

    /** How high [vessel] is over the ground or sea under it, in metres. */
    private fun heightAboveSurface(vessel: Vessel, attractor: CelestialBody, time: Double): Double {
        val altitude = attractor.altitudeOf(vessel.body.position)
        val terrain = attractor.terrain ?: return altitude
        attractor.toBodyFixed(vessel.body.position, attractor.rotationAt(time), scratchSurfaceUp).normalizeInPlace()
        return altitude - maxOf(terrain.elevation(scratchSurfaceUp), 0.0)
    }

    private companion object {
        /** How long a propeller takes to get most of the way to a new speed, in seconds. */
        const val PROPELLER_SPOOL = 0.6

        /** The angle a sail is set to meet the wind at, in radians: about twenty degrees. */
        const val SAIL_ATTACK = 0.35

        /** The furthest out the sheet lets a sail go, in radians: about eighty-five degrees. */
        const val SAIL_MOST_OUT = 1.48

        /**
         * Closer to the wind than this, in radians, a sail only flaps, and from [SAIL_CLOSEST] out
         * it draws fully (about thirty and forty-two degrees off the bow).
         */
        const val SAIL_LUFFS = 0.52
        const val SAIL_CLOSEST = 0.73

        /** The push square to a sail at its best, as a coefficient, like a flat plate across the flow. */
        const val SAIL_NORMAL = 1.6

        /** A drawing sail's drag along the wind, and a flapping one's extra, as coefficients. */
        const val SAIL_DRAG = 0.05
        const val SAIL_FLOGGING = 0.25

        /**
         * The most a turn on the water is let pull a boat sideways, in m/s², and the share of that
         * turn rate over which the steering eases off to nothing.
         */
        const val WATER_TURN_PULL = 4.0
        const val WATER_TURN_EASE = 0.3

        /** The heel, in radians, from which the steering on the water eases, and over which it goes. */
        const val WATER_HEEL_FROM = 0.44
        const val WATER_HEEL_OVER = 0.26

        /**
         * A sail eases from full once the boat heels past [SAIL_EASE_FROM] (radians), down to
         * [SAIL_EASED_MOST] of it by [SAIL_EASE_OVER] further.
         */
        const val SAIL_EASE_FROM = 0.26
        const val SAIL_EASE_OVER = 0.35
        const val SAIL_EASED_MOST = 0.25

        /** The share of the wheels' strength counted as used while only damping rotation. */
        const val DAMPING_WORK = 0.1

        /** Metres under water at which a propeller gets its full bite. */
        const val PROP_IMMERSION_DEPTH = 0.3

        /** More chutes than any sane craft carries. */
        const val MAX_TORN = 8

        /** Pascals on a flat surface in the heaviest rain. */
        const val RAIN_PRESSURE = 30.0

        /** The turn rate, in rad/s, at which SAS applies full authority. */
        const val SAS_SATURATION_RATE = 0.35

        /**
         * How fast it can rock over, in rad/s, while sliding along the ground before the thrusters
         * ease off, and how much more stops it.
         */
        const val ROCK_ALLOWED = 0.02
        const val ROCK_SPAN = 0.06

        /**
         * The drag coefficient for a stack of hull parts.
         *
         * It's one number instead of an average over the parts. The stack's drag is mostly its nose
         * and its base, and averaging the coefficients of the tanks in between describes nothing
         * physical.
         */
        const val AVERAGE_BODY_CD = 0.3

        /** For weighing a chute's pull in g. */
        const val STANDARD_GRAVITY = 9.81

        /** Air's ratio of specific heats, and its gas constant in J/(kg K), for the speed of sound. */
        const val GAS_RATIO = 1.4
        const val GAS_CONSTANT = 287.0

        /** The most of its load limit a control surface pushes with, blown back by the air at speed. */
        const val BLOWBACK_SHARE = 0.6

        /** The share of its most lift a surface keeps once it has stalled. */
        const val STALL_KEEP = 0.7

        /** A flat plate's push held square to the wind, as a coefficient. */
        const val PLATE_BROADSIDE = 1.2

        /** Extra drag in the heaviest rain, as a share: a quarter more. */
        const val RAIN_DRAG = 0.25

        /** A blunt body's, like a capsule on its shield: about four times a slender one's. */
        const val BLUNT_BODY_CD = 1.1

        /**
         * Length over diameter from which a stack counts as slender. At 1 or less it's fully blunt.
         */
        const val BLUNT_UNTIL = 3.0

        /** The drag coefficient of a fin edge-on to the airflow. Small, on purpose. */
        const val FIN_PARASITIC_CD = 0.03
    }
}

/** The speed of a circular orbit at the given radius. Shared by spawn and telemetry. */
fun circularSpeed(mu: Double, radius: Double): Double = sqrt(mu / radius)

/**
 * A wing's lift slope per radian: less than a thin aerofoil's 2 pi, for a wing of ordinary
 * proportions, whose tips leak some of it.
 */
const val LIFT_SLOPE = 5.5

/**
 * The lift slope at [mach]: [LIFT_SLOPE] below the speed of sound, and past it the supersonic
 * one, 4 / sqrt(M^2 - 1), a third of it at Mach 2.5, blended across the transonic between.
 * Flown at its low-speed slope at Mach 3, a Sparrow pulling out of a dive at one degree was
 * pulling ten g, and broke up.
 */
fun slopeAt(mach: Double): Double {
    if (!(mach > SUPERSONIC_FROM)) return LIFT_SLOPE
    val supersonic = 4.0 / kotlin.math.sqrt(maxOf(mach, SUPERSONIC_FULL) * maxOf(mach, SUPERSONIC_FULL) - 1.0)
    val share = ((mach - SUPERSONIC_FROM) / (SUPERSONIC_FULL - SUPERSONIC_FROM)).coerceIn(0.0, 1.0)
    return minOf(LIFT_SLOPE, LIFT_SLOPE + (supersonic - LIFT_SLOPE) * share)
}

/** Mach numbers from which a wing starts to lift like a supersonic one, and does entirely. */
private const val SUPERSONIC_FROM = 1.0
private const val SUPERSONIC_FULL = 1.2
