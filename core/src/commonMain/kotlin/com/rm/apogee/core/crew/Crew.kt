package com.rm.apogee.core.crew

import com.rm.apogee.core.part.Command
import com.rm.apogee.core.part.Habitat
import com.rm.apogee.core.part.PartDef
import kotlinx.serialization.Serializable
import com.rm.apogee.core.math.Math

/** Where a crew member is: ready at home, aboard a craft, or gone. */
@Serializable
enum class CrewStatus { AVAILABLE, ABOARD, LOST }

/**
 * One of a player's crew: a person, not a part property. Belongs to [owner] (a client id) and is
 * aboard [vessel] while ABOARD. Once LOST, the memorial shows [lostAt] (universe seconds),
 * [lostWhere] and [lostHow].
 */
@Serializable
data class CrewMember(
    val id: Long,
    val name: String,
    val owner: String,
    val status: CrewStatus = CrewStatus.AVAILABLE,
    val vessel: Long = -1L,
    val lostAt: Double = 0.0,
    val lostWhere: String = "",
    val lostHow: String = "",
    /** The craft they were lost with, by id, or -1 if unknown. */
    val lastVessel: Long = -1L,
    /** Visor colour, 0 until [Crew.VISORS], or -1 for their default. See [Crew.visorOf]. */
    val visor: Int = -1,
)

object Crew {

    /**
     * Suit stripe and visor colour counts. The stripe is per player, so you can tell whose crew;
     * the visor is per person, so crew can be told apart. The app owns the colours.
     */
    const val STRIPES = 8
    const val VISORS = 8

    /** [member]'s visor: the one picked, else a default from their id. */
    fun visorOf(member: CrewMember): Int =
        if (member.visor in 0 until VISORS) member.visor else Math.floorMod(member.id - 1, VISORS.toLong()).toInt()

    /** The stripe [owner]'s crew wear: the one [picked], else one from their id. */
    fun stripeFor(owner: String, picked: Int?): Int =
        if (picked != null && picked in 0 until STRIPES) picked else Math.floorMod(owner.hashCode(), STRIPES)

    /** Seats in part [def]: a pod's or cockpit's crew plus a habitat's residents. */
    fun seatsIn(def: PartDef): Int =
        (def.module<Command>()?.crewCapacity ?: 0) + (def.module<Habitat>()?.capacity ?: 0)

    /** A name for recruit [n], the same for the same number so worlds are deterministic. */
    fun nameFor(n: Long): String {
        val h = (n * 0x9E3779B97F4A7C15uL.toLong()) xor (n ushr 17)
        val given = GIVEN[((h ushr 8) and 0x7FFFFFFF).toInt() % GIVEN.size]
        val family = FAMILY[((h ushr 36) and 0x7FFFFFFF).toInt() % FAMILY.size]
        return "$given $family"
    }

    private val GIVEN = listOf(
        "Ada", "Bram", "Cleo", "Dara", "Emil", "Freya", "Gus", "Hana", "Ivo", "Juno",
        "Kit", "Lena", "Milo", "Nell", "Otto", "Pia", "Quin", "Rhea", "Sol", "Tove",
        "Uma", "Vic", "Wren", "Xan", "Yara", "Zed", "Ari", "Bea", "Cal", "Dot",
        "Esme", "Finn", "Greta", "Hugo", "Iris", "Jem", "Kai", "Lior", "Mae", "Noor",
    )

    private val FAMILY = listOf(
        "Mercer", "Halden", "Okafor", "Lindqvist", "Tanaka", "Marchetti", "Adeyemi", "Novak", "Castell", "Ferreira",
        "Brandt", "Achebe", "Kowal", "Rosen", "Vance", "Ibarra", "Holm", "Sato", "Delacroix", "Mwangi",
        "Arden", "Quill", "Barros", "Nakamura", "Strand", "Oduya", "Keller", "Moreau", "Petrov", "Lund",
        "Ashby", "Rahman", "Voss", "Carrow", "Ekdahl", "Salo", "Varga", "Ortiz", "Hale", "Winter",
    )
}
