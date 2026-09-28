package com.rm.apogee.core.part

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Things a craft carries and uses up.
 *
 * This is deliberately a small fixed set instead of a fully data-driven resource system. The v1
 * catalogue needed two of these, and an enum keeps tank plumbing and the delta-v calculation honest
 * at compile time. Widening it later is a simple change, whereas untangling a resource graph held
 * together with strings isn't.
 */
@Serializable
enum class ResourceType(
    /** kg per unit, so tank capacities can be written in sensible units. */
    val densityPerUnit: Double,
    val displayName: String,
) {
    /**
     * Bipropellant, modelled as one substance. Splitting fuel and oxidiser into separate resources
     * adds mixture-ratio plumbing to every tank and engine, and gains nothing for getting a rocket
     * to orbit. The two can be separated later without touching anything outside this file and the
     * catalogue.
     */
    @SerialName("propellant")
    PROPELLANT(densityPerUnit = 5.0, displayName = "Propellant"),

    @SerialName("monopropellant")
    MONOPROPELLANT(densityPerUnit = 4.0, displayName = "Monopropellant"),

    /** Massless by convention, the same as most games like this. */
    @SerialName("electricCharge")
    ELECTRIC_CHARGE(densityPerUnit = 0.0, displayName = "Electric Charge"),

    /** What a heat shield burns away to keep the craft behind it cool. */
    @SerialName("ablator")
    ABLATOR(densityPerUnit = 1.0, displayName = "Ablator"),

    /** Rock dug out of the ground, for refining into propellant. See `Deposits`. */
    @SerialName("ore")
    ORE(densityPerUnit = 10.0, displayName = "Ore"),

    /** Ice dug out of the ground and melted. It refines faster and cheaper than ore. */
    @SerialName("water")
    WATER(densityPerUnit = 5.0, displayName = "Water");

    /** Whether a tank of it explodes when the tank is destroyed. */
    val explosive: Boolean get() = this == PROPELLANT || this == MONOPROPELLANT

    /** Whether a new craft's tanks of it start full. What gets dug up doesn't. */
    val startsFull: Boolean get() = this != ORE && this != WATER
}
