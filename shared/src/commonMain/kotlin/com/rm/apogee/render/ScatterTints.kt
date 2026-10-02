package com.rm.apogee.render

import com.rm.apogee.core.terrain.ScatterKind

/** Each world's colour for its scatter, multiplied into the kind's own, so Rubra's rocks are red. */
object ScatterTints {
    private val PLAIN = floatArrayOf(1f, 1f, 1f)

    private fun rgb(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b)

    /** Rocks by world. Anything else keeps its own colour. */
    private val ROCKS: Map<String, FloatArray> = mapOf(
        "celer" to rgb(0.90f, 0.88f, 0.86f),
        "caligo" to rgb(1.05f, 0.85f, 0.65f),
        "rubra" to rgb(1.25f, 0.75f, 0.55f),
        "timor" to rgb(0.65f, 0.60f, 0.55f),
        "pavor" to rgb(0.68f, 0.62f, 0.56f),
        "fornax" to rgb(1.10f, 0.95f, 0.62f),
        "crusta" to rgb(1.75f, 1.80f, 1.85f),
        "maxima" to rgb(0.85f, 0.82f, 0.78f),
        "cicatrix" to rgb(0.55f, 0.54f, 0.52f),
        "aurantia" to rgb(1.00f, 0.82f, 0.58f),
        "fons" to rgb(2.05f, 2.15f, 2.30f),
        "aversa" to rgb(1.60f, 1.45f, 1.40f),
        "ultima" to rgb(1.40f, 1.25f, 1.10f),
        "portitor" to rgb(0.95f, 0.95f, 0.95f),
    )

    private val rocks = setOf(ScatterKind.BOULDER_SMALL.ordinal, ScatterKind.BOULDER_LARGE.ordinal, ScatterKind.PINNACLE.ordinal)

    /** Kind [kind]'s tint on [world]. */
    fun of(world: String, kind: Int): FloatArray =
        if (kind in rocks) ROCKS[world] ?: PLAIN else PLAIN
}
