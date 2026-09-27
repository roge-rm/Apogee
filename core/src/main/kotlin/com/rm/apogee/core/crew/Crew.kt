package com.rm.apogee.core.crew

import com.rm.apogee.core.part.Command
import com.rm.apogee.core.part.Habitat
import com.rm.apogee.core.part.PartDef
import kotlinx.serialization.Serializable

/** Where a crew member is: ready at home, aboard a craft, or gone. */
@Serializable
enum class CrewStatus { AVAILABLE, ABOARD, LOST }

/**
 * One of a player's crew. This is a person, not a property of a part. They belong to [owner] (a
 * client id) and are aboard [vessel] while [status] is ABOARD. Once they're LOST, the memorial
 * remembers them with [lostAt] (universe seconds), [lostWhere] and [lostHow].
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
    /** The craft they were lost with, by id, or -1 if it isn't known. */
    val lastVessel: Long = -1L,
    /** Their visor's colour, 0 until [Crew.VISORS], or -1 for one of their own. See [Crew.visorOf]. */
    val visor: Int = -1,
)

object Crew {

    /**
     * How many stripe colours a suit can have, and how many visor colours. A player's stripe is the
     * same on all their crew, so you can tell whose they are, and each one's visor is their own,
     * so crew out together can be told apart. The colours themselves are the app's.
     */
    const val STRIPES = 8
    const val VISORS = 8

    /** [member]'s visor: the one picked for them, or else one of their own, from their id. */
    fun visorOf(member: CrewMember): Int =
        if (member.visor in 0 until VISORS) member.visor else Math.floorMod(member.id - 1, VISORS.toLong()).toInt()

    /** The stripe [owner]'s crew wear: the one they [picked], or else one from who they are. */
    fun stripeFor(owner: String, picked: Int?): Int =
        if (picked != null && picked in 0 until STRIPES) picked else Math.floorMod(owner.hashCode(), STRIPES)

    /** How many people part [def] seats: a pod's or cockpit's crew, or a habitat's residents. */
    fun seatsIn(def: PartDef): Int =
        (def.module<Command>()?.crewCapacity ?: 0) + (def.module<Habitat>()?.capacity ?: 0)

    /**
     * A name for recruit [n]. It's the same every time for the same number, so worlds come out the
     * same.
     */
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
