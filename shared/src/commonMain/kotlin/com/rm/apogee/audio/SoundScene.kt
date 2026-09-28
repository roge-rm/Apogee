package com.rm.apogee.audio

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Exhaust
import kotlin.math.exp
import kotlin.math.sqrt
import com.rm.apogee.core.math.Math

/**
 * What can be heard this frame, worked out from the game's state and handed to the [AudioEngine]:
 * which sounds are held, how loud they are, where they are, and the one-shots and when they arrive.
 *
 * It's pure. Nothing here touches the native engine, so it gets tested as it is.
 *
 * Out in the air, sound falls off with distance, loses its top end the further it has come, arrives
 * late at the speed of sound, and pans with the direction it comes from. In vacuum none of that
 * happens, because nothing reaches you at all, except through the hull of your own craft: its
 * engines as a rumble in the structure, the knocks and bangs it takes, and the cabin's hum. Another
 * craft's engine, however close, is silent.
 */
class SoundScene(private val budget: Int) {

    /** One craft, as the scene hears it. */
    class Craft(
        val id: Long,
        /** The one being flown. */
        val own: Boolean,
        /** Where it is, and the air's pressure there as a share of sea level's. */
        val position: Vec3,
        val pressure: Double,
        /** Its velocity through the air, in m/s, in the same frame as [position], for Doppler. */
        val velocity: Vec3 = Vec3(),
    ) {
        /** Per exhaust kind: output weighted by thrust (0..1), and the thrust it's out of, in N. */
        val output = DoubleArray(Exhaust.entries.size)
        val thrust = DoubleArray(Exhaust.entries.size)
        /**
         * Per exhaust kind, weighted by thrust: how much its engines are built for vacuum, from 0
         * for a sea-level booster to 1 for a vacuum engine. That's how they sound (see
         * [vacuumBuilt]).
         */
        val character = DoubleArray(Exhaust.entries.size)
        /** Parts of it that are on fire. */
        var burning = 0

        /** How much its sails are flogging, 0..1: set, with the wind gone out of them. */
        var luff = 0.0

        /** Its rotors: how hard the busiest one works, 0..1, and the biggest one's width, in metres. */
        var rotor = 0.0
            private set
        var rotorSize = 0.0
            private set

        fun rotor(output: Double, diameter: Double) {
            rotor = maxOf(rotor, output.coerceIn(0.0, 1.0))
            rotorSize = maxOf(rotorSize, diameter)
        }

        /** How hard its thrusters are firing, 0..1, from the hardest-working block. */
        var rcs = 0.0
            private set

        fun rcs(strength: Double) { rcs = maxOf(rcs, strength.coerceIn(0.0, 1.0)) }

        /** Adds an engine of [kind] putting out [output] (0..1) of [fullThrust], of [character]. */
        fun engine(kind: Exhaust, output: Double, fullThrust: Double, character: Double = 0.0) {
            val k = kind.ordinal
            val total = thrust[k] + fullThrust
            if (total > 0.0) {
                this.output[k] = (this.output[k] * thrust[k] + output * fullThrust) / total
                this.character[k] = (this.character[k] * thrust[k] + character * fullThrust) / total
            }
            thrust[k] = total
        }
    }

    /** The flown craft's own air and strain. */
    class Own(
        val airspeed: Double,
        val dynamicPressure: Double,
        val mach: Double,
        /** Re-entry heating, from 0 for none to 1 for fully alight. */
        val heat: Double,
        /** The hardest-loaded joint, as a share of its strength. */
        val stress: Double,
        val crewed: Boolean,
        /**
         * On wheels: how fast it's going over the ground, how hard it's driven, the ground's grit
         * (how much it crunches) and softness (0 rock to 1 sand or snow).
         */
        val wheelSpeed: Double = 0.0,
        val wheelLoad: Double = 0.0,
        val grit: Double = 0.0,
        val softness: Double = 0.0,
        /** How much the hull is still settling to a change of outside pressure, 0..1. See [HullSettling]. */
        val settling: Double = 0.0,
        /** Being filled from a base, so its pump hums through the hull. */
        val pumping: Boolean = false,
    )

    /** Where the ears are. */
    class Listener(
        val position: Vec3,
        /** The camera's right, for panning. */
        val right: Vec3,
        /** Air density there, in kg/m³. With none, only the hull carries sound. */
        val density: Double,
        /** Wind there, in m/s, and how gusty it is (0..1), and rain (0..1). */
        val wind: Double = 0.0,
        val turbulence: Double = 0.0,
        val rain: Double = 0.0,
        /**
         * How the ears are moving through the air, in m/s, in the frame of [position], for Doppler.
         */
        val velocity: Vec3 = Vec3(),
        /** Waves breaking on a shore nearby, from 0 for none to 1 right on the beach. */
        val shore: Double = 0.0,
        /**
         * The open sea around the listener: how near and loud (0..1), how rough (0..1), and how
         * stormy (0..1).
         */
        val sea: Double = 0.0,
        val seaRough: Double = 0.0,
        val seaStorm: Double = 0.0,
        /**
         * The launch complex around the listener, from 0 away to 1 among its pads, and whether its
         * lamps are lit.
         */
        val complex: Double = 0.0,
        val lampsLit: Boolean = false,
        /** The harbour around the listener, from 0 away to 1 on its jetty. */
        val port: Double = 0.0,
    ) {
        val inAir: Boolean get() = density > AIRLESS
    }

    /** A one-shot, ready for [AudioEngine.event]. */
    class Shot(val recipe: Int, val flags: Int, val delay: Float, val params: FloatArray)

    // The scene as it was last built, in parallel arrays, the way the native side wants it.
    var count = 0
        private set
    val keys = IntArray(budget)
    val recipes = IntArray(budget)
    val flags = IntArray(budget)
    val params = FloatArray(budget * SharedParams.COUNT)

    private class Entry(val key: Int, val recipe: Int, val flags: Int, val params: FloatArray, val weight: Float)
    private val entries = ArrayList<Entry>(64)
    private val scratch = Vec3()

    /** Builds this frame's held sounds from [crafts], the flown one's [own] state, and the [listener]. */
    fun build(listener: Listener, crafts: List<Craft>, own: Own?) {
        entries.clear()
        val air = listener.inAir

        for (craft in crafts) {
            val base = craft.id.toInt() * CRAFT_SLOTS
            // In vacuum only your own craft is heard at all, through its hull.
            if (!air && !craft.own) continue
            val (gain, pan, lowpass) = if (air) place(listener, craft.position, 1.0) else Triple(1.0f, 0.0f, 0.0f)
            val hull = if (air) 0 else VoiceFlags.HULL

            for (kind in Exhaust.entries) {
                val out = craft.output[kind.ordinal]
                val thrust = craft.thrust[kind.ordinal]
                if (out < 0.01 || thrust <= 0.0) continue
                val size = (thrust / FULL_SIZE_THRUST).coerceIn(0.05, 1.0)
                // Big engines carry further.
                val (g, p, lp) = if (air) place(listener, craft.position, 0.5 + 1.5 * size) else Triple(0.8f, 0.0f, 0.0f)
                val v = FloatArray(SharedParams.COUNT)
                val recipe = when (kind) {
                    Exhaust.ROCKET -> {
                        v[0] = out.toFloat(); v[1] = size.toFloat(); v[2] = craft.pressure.toFloat(); v[3] = (0.3 + 0.4 * size).toFloat()
                        v[4] = craft.character[kind.ordinal].toFloat()
                        Recipes.ROCKET
                    }
                    Exhaust.JET -> { v[0] = (0.35 + 0.65 * out).toFloat(); v[1] = out.toFloat(); v[2] = 0.5f; Recipes.JET }
                    Exhaust.PROP -> { v[0] = (0.3 + 0.7 * out).toFloat(); v[1] = (30 + 70 * out).toFloat(); v[2] = out.toFloat(); Recipes.PROP }
                    Exhaust.WATER -> { v[0] = (0.4 + 0.6 * out).toFloat(); v[1] = out.toFloat(); Recipes.OUTBOARD }
                }
                if (air) v[SharedParams.PITCH] = doppler(listener, craft).toFloat()
                add(base + kind.ordinal, recipe, hull, v, g, p, lp, weight = g * (0.5f + out.toFloat()))
            }

            if (craft.rcs > 0.02) {
                // Thrusters: puffs of gas, heard through the hull out in vacuum.
                val (g, p, lp) = if (air) place(listener, craft.position, 0.4) else Triple(0.8f, 0.0f, 0.0f)
                val v = FloatArray(SharedParams.COUNT)
                v[0] = craft.rcs.toFloat()
                if (air) v[SharedParams.PITCH] = doppler(listener, craft).toFloat()
                add(base + SLOT_RCS, Recipes.RCS, hull, v, g, p, lp, weight = g * (0.3f + craft.rcs.toFloat()))
            }

            if (air && craft.rotor > 0.02) {
                // Rotors: a big one's slow chop, a drone's small ones a soft buzz, faster the smaller.
                val size = (craft.rotorSize / BIG_ROTOR).coerceIn(0.05, 1.0)
                val (g, p, lp) = place(listener, craft.position, 0.6 + 1.2 * size)
                val v = FloatArray(SharedParams.COUNT)
                v[0] = (0.4 + 0.6 * craft.rotor).toFloat()
                // Blades passing a second: about twenty for a helicopter, far more for a drone.
                v[1] = (18.0 / size.coerceAtLeast(0.1)).coerceAtMost(160.0).toFloat()
                v[2] = size.toFloat()
                v[SharedParams.PITCH] = doppler(listener, craft).toFloat()
                add(base + SLOT_ROTOR, Recipes.ROTOR, 0, v, g, p, lp, weight = g * (0.4f + v[0]))
            }
            if (air && craft.luff > 0.02 && listener.wind > CALM) {
                // A sail flogging, louder and quicker in more wind.
                val (g, p, lp) = place(listener, craft.position, 0.5)
                val v = FloatArray(SharedParams.COUNT)
                v[0] = craft.luff.coerceIn(0.0, 1.0).toFloat()
                v[1] = ((listener.wind - CALM) / (GALE - CALM)).coerceIn(0.0, 1.0).toFloat()
                add(base + SLOT_SAIL, Recipes.SAIL, 0, v, g, p, lp, weight = g * 0.4f * v[0])
            }
            if (air && craft.burning > 0) {
                val (g, p, lp) = place(listener, craft.position, 0.6)
                val v = FloatArray(SharedParams.COUNT)
                v[0] = (0.4 + 0.15 * craft.burning).coerceAtMost(1.0).toFloat()
                add(base + SLOT_FIRE, Recipes.FIRE, 0, v, g, p, lp, weight = g * 0.6f)
            }
            if (craft.own && own != null && own.wheelSpeed + own.wheelLoad > 0.05 && air) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = own.wheelLoad.toFloat(); v[1] = own.wheelSpeed.toFloat(); v[2] = own.grit.toFloat()
                v[4] = own.softness.toFloat()
                v[SharedParams.PITCH] = doppler(listener, craft).toFloat()
                add(base + SLOT_ROVER, Recipes.ROVER, 0, v, gain, pan, lowpass, weight = gain * 0.7f)
            }
        }

        if (own != null) {
            if (air) {
                // The air it's tearing through, heard as if you were riding along.
                val loud = sqrt(own.dynamicPressure / 30_000.0).coerceIn(0.0, 1.2)
                if (loud > 0.02) {
                    val v = FloatArray(SharedParams.COUNT)
                    v[0] = loud.toFloat()
                    v[1] = (own.airspeed / 500.0).coerceIn(0.0, 1.0).toFloat()
                    val m = (own.mach - 1.0) / 0.12
                    v[2] = exp(-m * m).toFloat()
                    add(KEY_AIRFLOW, Recipes.AIRFLOW, 0, v, 1f, 0f, 0f, weight = loud.toFloat())
                }
                if (own.heat > 0.02) {
                    val v = FloatArray(SharedParams.COUNT)
                    v[0] = own.heat.toFloat(); v[1] = own.heat.toFloat()
                    add(KEY_REENTRY, Recipes.REENTRY, 0, v, 1f, 0f, 0f, weight = own.heat.toFloat() * 1.5f)
                }
            }
            // The cabin: its hum only out in vacuum, where nothing drowns it out, and the hull's
            // ticks wherever it's still settling to the pressure. Being filled from a base, the
            // pump's hum comes through the hull too, using the cabin's own sound, already measured,
            // instead of a new noise.
            if (own.pumping || (own.crewed && (!air || own.settling > 0.02))) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = 0.6f
                v[1] = own.settling.coerceIn(0.0, 1.0).toFloat()
                v[2] = if (air && !own.pumping) 0f else 1f
                add(KEY_CABIN, Recipes.CABIN, if (air) 0 else VoiceFlags.HULL, v, 1f, 0f, 0f, weight = 0.2f)
            }
            if (own.stress > STRESS_AUDIBLE) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = ((own.stress - STRESS_AUDIBLE) / (1.0 - STRESS_AUDIBLE)).coerceIn(0.0, 1.0).toFloat()
                add(KEY_STRESS, Recipes.STRESS, if (air) 0 else VoiceFlags.HULL, v, 1f, 0f, 0f, weight = 0.8f)
            }
        }

        if (air) {
            // Wind and rain where the camera is, thinning with the air. Calm is quiet: nothing
            // below a breeze, then rising with it.
            val thin = sqrt((listener.density / SEA_LEVEL_DENSITY).coerceIn(0.0, 1.0))
            val breeze = ((listener.wind - CALM) / (GALE - CALM)).coerceIn(0.0, 1.0)
            val wind = Math.pow(breeze, 1.3) * thin
            if (wind > 0.02) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = wind.toFloat()
                v[1] = (0.5 + 0.5 * listener.turbulence.coerceIn(0.0, 1.0)).toFloat()
                v[2] = breeze.toFloat()
                add(KEY_WIND, Recipes.WIND, 0, v, 1f, 0f, 0f, weight = wind.toFloat() * 0.5f)
            }
            if (listener.rain > 0.02) {
                val r = FloatArray(SharedParams.COUNT)
                r[0] = (listener.rain * 1.5).coerceAtMost(1.0).toFloat(); r[1] = 0.3f
                add(KEY_RAIN, Recipes.RAIN, 0, r, 1f, 0f, 0f, weight = r[0] * 0.5f)
            }
            if (listener.shore > 0.02) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = listener.shore.coerceIn(0.0, 1.0).toFloat()
                add(KEY_SURF, Recipes.SURF, 0, v, 1f, 0f, 0f, weight = v[0] * 0.4f)
            }
            if (listener.sea > 0.02) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = listener.sea.coerceIn(0.0, 1.0).toFloat()
                v[1] = listener.seaRough.coerceIn(0.0, 1.0).toFloat()
                v[2] = listener.seaStorm.coerceIn(0.0, 1.0).toFloat()
                add(KEY_SEA, Recipes.SEA, 0, v, 1f, 0f, 0f, weight = v[0] * 0.35f)
            }
            if (listener.complex > 0.02) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = listener.complex.coerceIn(0.0, 1.0).toFloat()
                v[1] = if (listener.lampsLit) 1f else 0f
                add(KEY_COMPLEX, Recipes.COMPLEX, 0, v, 1f, 0f, 0f, weight = v[0] * 0.25f)
            }
            if (listener.port > 0.02) {
                val v = FloatArray(SharedParams.COUNT)
                v[0] = listener.port.coerceIn(0.0, 1.0).toFloat()
                add(KEY_PORT, Recipes.PORT, 0, v, 1f, 0f, 0f, weight = v[0] * 0.3f)
            }
        }

        // The loudest, as many as there are voices for.
        entries.sortByDescending { it.weight }
        count = minOf(entries.size, budget)
        for (i in 0 until count) {
            val e = entries[i]
            keys[i] = e.key
            recipes[i] = e.recipe
            flags[i] = e.flags
            e.params.copyInto(params, i * SharedParams.COUNT)
        }
    }

    /**
     * A one-shot at [position]: an impact at [amount] m/s on [material] (into water if [water]), a
     * part destroyed or torn off, or an explosion of [amount] kg. Null if it can't be heard from
     * here.
     */
    fun shot(listener: Listener, kind: Kind, position: Vec3, amount: Double, own: Boolean, material: Int = Materials.EARTH, water: Boolean = false): Shot? {
        val air = listener.inAir
        if (!air && !own) return null
        val v = FloatArray(SharedParams.COUNT)
        val (recipe, reach) = when (kind) {
            Kind.IMPACT -> if (water) {
                v[0] = (amount / 30.0).coerceIn(0.1, 2.0).toFloat(); Recipes.SPLASH to 0.8
            } else {
                v[0] = ((amount - 3.0) / 25.0).coerceIn(0.05, 2.0).toFloat(); v[1] = material.toFloat(); v[2] = 0.5f; Recipes.IMPACT to 0.8
            }
            Kind.DESTROYED -> { v[0] = 1f; Recipes.CRUNCH to 1.0 }
            Kind.DETACHED -> { v[0] = 1f; Recipes.TEAR to 0.8 }
            Kind.LATCH -> { v[0] = 1f; Recipes.CLUNK to 0.6 }
            Kind.RELEASE -> { v[0] = 0.6f; Recipes.CLUNK to 0.6 }
            Kind.SLAP -> { v[0] = amount.coerceIn(0.1, 1.0).toFloat(); Recipes.SLAP to 0.5 }
            Kind.EXPLOSION -> {
                val size = sqrt(amount / 2_000.0).coerceIn(0.15, 1.5)
                v[0] = size.toFloat(); Recipes.EXPLOSION to 8.0 * size
            }
        }
        if (!air) {
            v[SharedParams.GAIN] = 0.9f
            return Shot(recipe, VoiceFlags.HULL, 0f, v)
        }
        val (gain, pan, lowpass) = place(listener, position, reach)
        if (gain < AUDIBLE) return null
        v[SharedParams.GAIN] = gain; v[SharedParams.PAN] = pan; v[SharedParams.LOWPASS] = lowpass
        // Sound takes its time, so the flash comes first and the bang after.
        val delay = (position.distanceTo(listener.position) / SPEED_OF_SOUND).coerceAtMost(MAX_DELAY)
        return Shot(recipe, 0, delay.toFloat(), v)
    }

    enum class Kind { IMPACT, DESTROYED, DETACHED, EXPLOSION, LATCH, RELEASE, SLAP }

    /**
     * How a sound at [position] reaches the listener: its loudness by distance, relative to one
     * that carries [reach] times as far as usual, its pan, and the low pass the air puts on it.
     */
    fun place(listener: Listener, position: Vec3, reach: Double): Triple<Float, Float, Float> {
        scratch.setTo(position).subInPlace(listener.position)
        val d = scratch.length
        val ref = REFERENCE_DISTANCE * reach
        val gain = (ref / (ref + d)).toFloat()
        val pan = if (d > 1e-3) ((scratch dot listener.right) / d * 0.8).toFloat() else 0f
        // Air takes the top off with distance.
        val lowpass = (18_000.0 / (1.0 + d / 400.0)).coerceIn(250.0, 18_000.0).toFloat()
        return Triple(gain, pan, lowpass)
    }

    /**
     * How much higher [craft] sounds than it really is, as heard by [listener]. Above 1 it's coming
     * closer, and below 1 it's going away: (c + v_listener) / (c + v_source) along the line between
     * them. The craft you're riding moves with you and sounds as it is. It's kept within an octave
     * either way, because past the speed of sound the formula runs away, and the shock is a
     * different sound.
     */
    fun doppler(listener: Listener, craft: Craft): Double {
        scratch.setTo(craft.position).subInPlace(listener.position)
        val d = scratch.length
        if (d < 1.0) return 1.0
        scratch.mulInPlace(1.0 / d) // listener to source
        val towardSource = listener.velocity dot scratch
        val awayFromListener = craft.velocity dot scratch
        val below = (SPEED_OF_SOUND + awayFromListener).coerceAtLeast(SPEED_OF_SOUND * 0.5)
        return ((SPEED_OF_SOUND + towardSource) / below).coerceIn(0.5, 2.0)
    }

    private fun add(key: Int, recipe: Int, flags: Int, v: FloatArray, gain: Float, pan: Float, lowpass: Float, weight: Float) {
        if (gain < AUDIBLE) return
        v[SharedParams.GAIN] = gain
        v[SharedParams.PAN] = pan
        v[SharedParams.LOWPASS] = lowpass
        entries.add(Entry(key, recipe, flags, v, weight))
    }

    companion object {
        /** Air thinner than this, in kg/m³, carries nothing. */
        const val AIRLESS = 1e-5
        const val SEA_LEVEL_DENSITY = 1.225
        const val SPEED_OF_SOUND = 343.0

        /** Wind below this makes no sound, in m/s. At [GALE] it's as loud as it gets. */
        const val CALM = 1.5
        const val GALE = 20.0
        const val MAX_DELAY = 8.0

        /** Where a source of ordinary reach is at half loudness, in metres. */
        const val REFERENCE_DISTANCE = 40.0

        /** The thrust at which an engine sounds as big as it gets, in N. */
        const val FULL_SIZE_THRUST = 400_000.0

        /**
         * The joint load, as a share of strength, where the structure starts to creak. It's where
         * the seam starts to spark (StrainLook.SPARKS_FROM), so you hear it when you see it. At
         * 0.55 it creaked through most of an ascent once fins counted, which was far too much.
         */
        const val STRESS_AUDIBLE = 0.75

        /** Quieter than this, a sound isn't worth a voice. */
        const val AUDIBLE = 0.01f

        const val CRAFT_SLOTS = 10
        const val SLOT_SAIL = 4

        const val SLOT_ROTOR = 8

        /** A rotor this wide, in metres, sounds as big as a rotor gets. */
        const val BIG_ROTOR = 8.0
        const val SLOT_FIRE = 5
        const val SLOT_ROVER = 6
        const val SLOT_RCS = 7

        // Keys for the scene's own sounds, well clear of craft keys.
        const val KEY_AIRFLOW = -1
        const val KEY_REENTRY = -2
        const val KEY_CABIN = -3
        const val KEY_STRESS = -4
        const val KEY_WIND = -5
        const val KEY_RAIN = -6
        const val KEY_SURF = -7
        const val KEY_SEA = -8
        const val KEY_COMPLEX = -9
        const val KEY_PORT = -10

        /**
         * How much an engine is built for vacuum, 0..1, from how much of its thrust it keeps at sea
         * level. A booster keeps most, and a vacuum engine's big bell keeps little.
         */
        fun vacuumBuilt(thrustSeaLevel: Double, thrustVacuum: Double): Double =
            if (thrustVacuum <= 0.0) 0.0 else (1.0 - thrustSeaLevel / thrustVacuum).coerceIn(0.0, 1.0)
    }
}
