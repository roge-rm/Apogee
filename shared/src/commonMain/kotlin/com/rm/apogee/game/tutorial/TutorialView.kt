package com.rm.apogee.game.tutorial

import com.rm.apogee.core.world.SasMode

/**
 * What a tutorial's steps look at, taken a few times a second. Plain values, so a step's check is
 * a pure function and easy to test. Heights in metres, speeds in m/s, angles in degrees.
 */
data class TutorialView(
    val agl: Double = 0.0,
    val altitude: Double = 0.0,
    val surfaceSpeed: Double = 0.0,
    val verticalSpeed: Double = 0.0,
    val apoapsis: Double = 0.0,
    val periapsis: Double = 0.0,
    val inOrbit: Boolean = false,
    val inAir: Boolean = true,
    val stage: Int = 0,
    val throttle: Double = 0.0,
    val sasEnabled: Boolean = false,
    val sasMode: SasMode? = null,
    val heading: Double = 0.0,
    /** How far the craft's motion leans from straight up. */
    val lean: Double = 0.0,
    val mapMode: Boolean = false,
    val burnPlanned: Boolean = false,
    val chute: String? = null,
    val brakes: Boolean = false,
    val afloat: Boolean = false,
    val grounded: Boolean = false,
    val anchored: Boolean = false,
    val isSuit: Boolean = false,
    val docked: Boolean = false,
    val onApproach: Boolean = false,
    val targetName: String? = null,
    val targetDistance: Double = 0.0,
    val closingSpeed: Double = 0.0,
    /** In the Vehicle Assembly instead of flying, and what's been built. */
    val builder: BuilderView? = null,
) {
    /** Stopped on the ground or the water. */
    val resting: Boolean get() = (grounded || afloat) && surfaceSpeed < 1.0

    companion object {
        /** The craft being flown, from [hud] and [session]. */
        fun of(hud: com.rm.apogee.game.HudState, session: com.rm.apogee.game.GameSession): TutorialView {
            val t = hud.telemetry
            val lean = t.prograde?.let { p ->
                val c = (p dot t.up) / (p.length * t.up.length).coerceAtLeast(1e-9)
                com.rm.apogee.core.math.Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
            } ?: 0.0
            return TutorialView(
                agl = t.heightAboveGround,
                altitude = t.altitude,
                surfaceSpeed = t.surfaceSpeed,
                verticalSpeed = t.verticalSpeed,
                apoapsis = t.apoapsisAltitude,
                periapsis = t.periapsisAltitude,
                inOrbit = t.inOrbit,
                inAir = t.inAir,
                stage = t.stage,
                throttle = hud.throttle.toDouble(),
                sasEnabled = hud.sasEnabled,
                sasMode = t.sasMode,
                heading = t.heading,
                lean = lean,
                mapMode = hud.mapMode,
                burnPlanned = hud.burn != null,
                chute = hud.chute,
                brakes = hud.brakes,
                afloat = hud.afloat,
                grounded = session.controlledGrounded,
                anchored = session.controlledAnchored,
                isSuit = hud.isSuit,
                docked = hud.joints.isNotEmpty(),
                onApproach = hud.approach != null,
                targetName = t.targetName,
                targetDistance = t.targetDistance,
                closingSpeed = t.closingSpeed,
            )
        }
    }
}

/** What's been built in a tutorial's Vehicle Assembly, and whether the stages were opened. */
data class BuilderView(
    val hasPod: Boolean = false,
    val hasTank: Boolean = false,
    val hasEngine: Boolean = false,
    val hasChute: Boolean = false,
    val stagingSeen: Boolean = false,
) {
    companion object {
        fun of(design: com.rm.apogee.core.craft.CraftDesign, catalog: com.rm.apogee.core.part.PartCatalog, stagingSeen: Boolean): BuilderView {
            val defs = design.parts.mapNotNull { catalog[it.partId] }
            // Parts of their own, so a pod's own small tank or chute doesn't count.
            val others = defs.filter { !it.hasModule<com.rm.apogee.core.part.Command>() }
            return BuilderView(
                hasPod = defs.any { it.hasModule<com.rm.apogee.core.part.Command>() },
                hasTank = others.any { it.hasModule<com.rm.apogee.core.part.Tank>() },
                hasEngine = others.any { it.hasModule<com.rm.apogee.core.part.Engine>() },
                hasChute = others.any { it.hasModule<com.rm.apogee.core.part.Parachute>() },
                stagingSeen = stagingSeen,
            )
        }
    }
}
