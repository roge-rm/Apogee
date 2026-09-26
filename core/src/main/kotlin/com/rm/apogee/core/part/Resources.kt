package com.rm.apogee.core.part

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Things a craft carries and consumes.
 *
 * Deliberately a small closed set rather than a fully data-driven resource
 * system. The v1 catalogue needs two of these, and an enum keeps tank plumbing
 * and the delta-v calculation honest at compile time; widening it later is a
 * mechanical change, whereas unwinding a stringly-typed resource graph is not.
 */
@Serializable
enum class ResourceType(
    /** kg per unit, so tank capacities can be authored in sensible units. */
    val densityPerUnit: Double,
    val displayName: String,
) {
    /**
     * Bipropellant, modelled as one substance. Splitting fuel and oxidiser into
     * separate resources adds mixture-ratio plumbing to every tank and engine
     * for no gain in the rocket-to-orbit slice; the two can be separated later
     * without touching anything outside this file and the catalogue.
     */
    @SerialName("propellant")
    PROPELLANT(densityPerUnit = 5.0, displayName = "Propellant"),

    @SerialName("monopropellant")
    MONOPROPELLANT(densityPerUnit = 4.0, displayName = "Monopropellant"),

    /** Massless by convention, as in most of the genre. */
    @SerialName("electricCharge")
    ELECTRIC_CHARGE(densityPerUnit = 0.0, displayName = "Electric Charge"),

    /** What a heat shield chars away to keep the craft behind it cool. */
    @SerialName("ablator")
    ABLATOR(densityPerUnit = 1.0, displayName = "Ablator"),

    /** Rock dug out of the ground, for refining into propellant. See `Deposits`. */
    @SerialName("ore")
    ORE(densityPerUnit = 10.0, displayName = "Ore"),

    /** Ice dug out of the ground, melted: refines faster and more cheaply than ore. */
    @SerialName("water")
    WATER(densityPerUnit = 5.0, displayName = "Water");

    /** Whether a tank of it goes up when the tank is destroyed. */
    val explosive: Boolean get() = this == PROPELLANT || this == MONOPROPELLANT

    /** Whether a new craft's tanks of it come full: what is dug up does not. */
    val startsFull: Boolean get() = this != ORE && this != WATER
}
