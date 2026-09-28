package com.rm.apogee.render

import com.rm.apogee.core.math.Math

/**
 * The colours of a crew member's suit, numbered the way [com.rm.apogee.core.crew.Crew] numbers
 * them: a stripe, which is their player's, and a visor, which is their own. The suit's accent pieces
 * take the stripe and its glass the visor.
 */
object SuitColours {

    class Choice(val name: String, val rgb: FloatArray)

    /** Bright enough to pick out across a landing site, none of them neon. Orange was the old trim. */
    val STRIPES = listOf(
        Choice("Orange", floatArrayOf(0.88f, 0.56f, 0.16f, 1f)),
        Choice("Red", floatArrayOf(0.82f, 0.24f, 0.22f, 1f)),
        Choice("Yellow", floatArrayOf(0.92f, 0.78f, 0.22f, 1f)),
        Choice("Green", floatArrayOf(0.36f, 0.70f, 0.30f, 1f)),
        Choice("Teal", floatArrayOf(0.20f, 0.66f, 0.66f, 1f)),
        Choice("Blue", floatArrayOf(0.25f, 0.45f, 0.85f, 1f)),
        Choice("Purple", floatArrayOf(0.58f, 0.36f, 0.82f, 1f)),
        Choice("Pink", floatArrayOf(0.90f, 0.44f, 0.62f, 1f)),
    )

    /**
     * Darker and glossier than the stripes, as glass is. Neighbours differ a lot, because recruits
     * who join together take them in order.
     */
    val VISORS = listOf(
        Choice("Blue", floatArrayOf(0.30f, 0.48f, 0.66f, 1f)),
        Choice("Gold", floatArrayOf(0.80f, 0.62f, 0.22f, 1f)),
        Choice("Green", floatArrayOf(0.28f, 0.58f, 0.42f, 1f)),
        Choice("Rose", floatArrayOf(0.72f, 0.36f, 0.46f, 1f)),
        Choice("Silver", floatArrayOf(0.62f, 0.66f, 0.72f, 1f)),
        Choice("Violet", floatArrayOf(0.46f, 0.38f, 0.72f, 1f)),
        Choice("Copper", floatArrayOf(0.78f, 0.44f, 0.24f, 1f)),
        Choice("Smoke", floatArrayOf(0.22f, 0.24f, 0.30f, 1f)),
    )

    fun stripe(index: Int): FloatArray = STRIPES[Math.floorMod(index, STRIPES.size)].rgb
    fun visor(index: Int): FloatArray = VISORS[Math.floorMod(index, VISORS.size)].rgb
}
