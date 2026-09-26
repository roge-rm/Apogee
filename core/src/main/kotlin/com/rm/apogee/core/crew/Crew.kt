package com.rm.apogee.core.crew

import com.rm.apogee.core.part.Command
import com.rm.apogee.core.part.Habitat
import com.rm.apogee.core.part.PartDef
import kotlinx.serialization.Serializable

/** Where a crew member is: ready at home, aboard a craft, or gone. */
@Serializable
enum class CrewStatus { AVAILABLE, ABOARD, LOST }

/**
 * One of a player's crew: a person, not a part property. Belongs to
 * [owner] (a client id); aboard [vessel] while [status] is ABOARD; and once
 * LOST, remembered with [lostAt] (universe seconds), [lostWhere] and
 * [lostHow] on the memorial.
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
    /** The craft they were lost with, by id; -1 for none known. */
    val lastVessel: Long = -1L,
)

object Crew {

    /** How many people part [def] seats: a pod's or cockpit's crew, a habitat's residents. */
    fun seatsIn(def: PartDef): Int =
        (def.module<Command>()?.crewCapacity ?: 0) + (def.module<Habitat>()?.capacity ?: 0)

    /** A name for recruit [n]: the same every time for the same number, so worlds are reproducible. */
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
