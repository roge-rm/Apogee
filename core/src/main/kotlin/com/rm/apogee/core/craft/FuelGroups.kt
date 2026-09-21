package com.rm.apogee.core.craft

import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.PartDef

/**
 * Which parts share propellant.
 *
 * Parts are in the same group when they are connected without a decoupler in
 * between - the decoupler is the break in the plumbing. This is the rule that
 * makes staging mean anything: without it an engine draws from every tank on
 * the craft, so a first stage quietly burns the upper stage's propellant and
 * separating buys nothing but lost mass.
 *
 * Shared by the live simulation ([Vessel]) and the builder's delta-v analysis
 * ([CraftStats]) on purpose. Two implementations of this rule would eventually
 * disagree, and the builder would confidently predict a flight the simulation
 * could not fly.
 */
object FuelGroups {

    /**
     * @param members the part indices to consider; others are treated as absent.
     *   Used by the builder to analyse a craft mid-way through its staging
     *   sequence, with earlier stages already discarded.
     * @return group id per part index, or -1 for parts not in [members].
     */
    fun compute(
        design: CraftDesign,
        defs: List<PartDef>,
        members: Set<Int> = design.parts.indices.toSet(),
    ): IntArray {
        val count = design.parts.size
        val groups = IntArray(count) { -1 }
        var nextGroup = 0

        val children = Array(count) { ArrayList<Int>(2) }
        design.parts.forEachIndexed { index, part ->
            if (index in members && part.parentIndex in 0 until count &&
                part.parentIndex in members
            ) {
                children[part.parentIndex].add(index)
            }
        }

        fun blocks(index: Int) = defs[index].module<Decoupler>() != null

        for (start in 0 until count) {
            if (start !in members || groups[start] != -1) continue
            val group = nextGroup++
            val queue = ArrayDeque<Int>()
            queue.add(start)
            groups[start] = group

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                // A decoupler joins nothing: it is where the plumbing stops.
                if (blocks(current)) continue

                val neighbours = ArrayList<Int>(children[current].size + 1)
                neighbours.addAll(children[current])
                design.parts[current].parentIndex.let {
                    if (it >= 0 && it in members) neighbours.add(it)
                }

                for (neighbour in neighbours) {
                    if (neighbour !in members || groups[neighbour] != -1) continue
                    if (blocks(neighbour)) continue
                    groups[neighbour] = group
                    queue.add(neighbour)
                }
            }
        }
        return groups
    }
}
