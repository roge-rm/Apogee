package com.rm.apogee.core.world

import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.GroundStation
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.Antenna
import com.rm.apogee.core.part.Command

/** A craft's link home. */
@kotlinx.serialization.Serializable
enum class Signal {
    /** None, so an uncrewed craft can't be flown. */
    NONE,
    /** Straight to a ground station. */
    DIRECT,
    /** Through one or more relays. */
    RELAYED,
}

/**
 * Who can hear whom: a craft's antennas, the ground stations, and the relays in between. Two ends
 * hear each other when both are within the shorter of their two ranges and the line between them
 * clears every body. There's no delay. A craft that can be heard is flown as if from its own seat.
 */
class Comms(private val system: SolarSystem) {

    /** One end of a possible link, placed in the system's frame. */
    private class Node(val vessel: Vessel?, val position: Vec3, val range: Double, val relay: Boolean)

    private val rotation = Quat.identity()

    /**
     * [vessel]'s link home at [time] among [others], meaning its signal and the relays it goes
     * through, nearest first, written into it.
     */
    fun update(vessel: Vessel, others: Collection<Vessel>, time: Double, isDebris: (Vessel) -> Boolean) {
        val own = reach(vessel)
        if (own == null || !vessel.powered) return set(vessel, Signal.NONE, emptyList())
        val start = Node(vessel, place(vessel, time), own.first, own.second)
        val stations = stationNodes(time)
        val relays = ArrayList<Node>()
        for (other in others) {
            if (other === vessel || !other.powered || isDebris(other)) continue
            val r = reach(other) ?: continue
            if (r.second) relays += Node(other, place(other, time), r.first, true)
        }
        // Breadth first, so the path found is the one through the fewest relays.
        val from = HashMap<Node, Node>()
        val queue = ArrayDeque<Node>()
        queue.add(start)
        val seen = HashSet<Node>().apply { add(start) }
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            if (stations.any { hears(here, it) }) {
                val path = ArrayList<Long>()
                var at = here
                while (at !== start) { path.add(at.vessel!!.id.raw); at = from.getValue(at) }
                path.reverse()
                return set(vessel, if (path.isEmpty()) Signal.DIRECT else Signal.RELAYED, path)
            }
            for (next in relays) {
                if (next in seen || !hears(here, next)) continue
                seen += next
                from[next] = here
                queue.add(next)
            }
        }
        set(vessel, Signal.NONE, emptyList())
    }

    private fun set(vessel: Vessel, signal: Signal, path: List<Long>) {
        vessel.signal = signal
        vessel.signalPath = path
    }

    /** Whether [a] and [b] can hear each other. */
    private fun hears(a: Node, b: Node): Boolean {
        val gap = a.position.distanceTo(b.position)
        return gap <= minOf(a.range, b.range) && clear(a.position, b.position)
    }

    private val scratchD = Vec3()
    private val scratchC = Vec3()

    /** Whether the line from [a] to [b] passes clear of every body. */
    private fun clear(a: Vec3, b: Vec3): Boolean {
        scratchD.setTo(b).subInPlace(a)
        val lengthSq = scratchD.lengthSq
        for (body in system.bodies.values) {
            if (body.parentId == null) continue
            val centre = bodyPosition(body.id)
            scratchC.setTo(centre).subInPlace(a)
            val t = if (lengthSq <= 0.0) 0.0 else ((scratchC dot scratchD) / lengthSq).coerceIn(0.0, 1.0)
            scratchC.addScaledInPlace(scratchD, -t)
            // A little inside the sphere, because ground stations and landed craft sit right on it.
            if (scratchC.length < body.radius * GRAZE) return false
        }
        return true
    }

    private val bodyPositions = HashMap<String, Vec3>()
    private var bodyPositionsAt = Double.NaN

    private fun bodyPosition(id: String): Vec3 = bodyPositions.getValue(id)

    private fun placeBodies(time: Double) {
        if (time == bodyPositionsAt) return
        bodyPositionsAt = time
        for (id in system.bodies.keys) bodyPositions[id] = system.positionOf(id, time)
    }

    /** Where [vessel] is in the system's frame. */
    private fun place(vessel: Vessel, time: Double): Vec3 {
        placeBodies(time)
        return bodyPosition(vessel.referenceBodyId).copy().addInPlace(vessel.body.position)
    }

    private fun stationNodes(time: Double): List<Node> {
        placeBodies(time)
        return SolarSystem.groundStations.map { stationNode(it, time) }
    }

    private fun stationNode(station: GroundStation, time: Double): Node {
        val body = system.body(station.bodyId)
        body.rotationAt(time, rotation)
        val local = SolarSystem.surfaceDirection(station.latitude, station.longitude).mulInPlace(body.radius + STATION_HEIGHT)
        val position = rotation.rotate(local, Vec3()).addInPlace(bodyPosition(body.id))
        return Node(null, position, station.range, relay = true)
    }

    /** Where every ground station is at [time], in the system's frame. For drawing. */
    fun stationPositions(time: Double): List<Vec3> = stationNodes(time).map { it.position }

    /**
     * The nearest ground station with a clear line to [at], in the system's frame, at [time], or
     * null for none. It's where a link drawn home ends.
     */
    fun stationInSight(at: Vec3, time: Double): Vec3? =
        stationNodes(time).filter { clear(at, it.position) }.minByOrNull { it.position.distanceTo(at) }?.position

    companion object {
        /** A ground station's dish, in metres above the body's datum. */
        const val STATION_HEIGHT = 100.0
        /** The share of a body's radius a line can pass inside and still count as clear. */
        const val GRAZE = 0.998

        /**
         * [vessel]'s longest reach, and whether it relays, over its working antennas. A fold-out
         * one only counts when it's out. Null if there are none.
         */
        fun reach(vessel: Vessel): Pair<Double, Boolean>? {
            var range = 0.0
            var relay = false
            for (i in vessel.defs.indices) {
                if (vessel.isBroken(i)) continue
                val antenna = vessel.defs[i].module<Antenna>() ?: continue
                if (antenna.deployable && !Power.deployed(vessel, i)) continue
                range = maxOf(range, antenna.range)
                relay = relay || antenna.relay
            }
            return if (range > 0.0) range to relay else null
        }

        /** Whether anyone is aboard [vessel] to fly it by hand. */
        fun crewed(vessel: Vessel): Boolean = vessel.hasCrew()

        /**
         * Whether [vessel] has a working probe core, which is a command part with no seat that's
         * flown from home.
         */
        fun hasProbeCore(vessel: Vessel): Boolean = vessel.defs.indices.any {
            !vessel.isBroken(it) && vessel.defs[it].module<Command>()?.let { c -> c.crewCapacity == 0 } == true
        }

        /**
         * Whether [vessel] needs a signal to be flown: it has a working command part, and nobody
         * aboard.
         */
        fun needsSignal(vessel: Vessel): Boolean =
            !crewed(vessel) && vessel.defs.indices.any { !vessel.isBroken(it) && vessel.defs[it].module<Command>() != null }
    }
}
