package com.rm.apogee.core.craft

import com.rm.apogee.core.part.Decoupler
import com.rm.apogee.core.part.PartDef

/**
 * Which parts share propellant.
 *
 * Parts are in the same group when they're connected without a decoupler in between, because the
 * decoupler is where the plumbing stops. This rule is what makes staging mean anything. Without it
 * an engine draws from every tank on the craft, so a first stage quietly burns the upper stage's
 * propellant and separating gets you nothing except lost mass.
 *
 * Both the live simulation ([Vessel]) and the builder's delta-v analysis ([CraftStats]) use this on
 * purpose. Two versions of the rule would eventually disagree, and the builder would confidently
 * predict a flight the simulation couldn't fly.
 */
object FuelGroups {

    /**
     * @param members the part indices to consider. Others are treated as not there. The builder
     *     uses this to analyse a craft part way through its staging sequence, with earlier stages
     *     already thrown away.
     * @return the group id for each part index, or -1 for parts not in [members].
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

        fun blocks(index: Int) = defs[index].module<Decoupler>()?.let { !it.feeds } ?: false

        for (start in 0 until count) {
            if (start !in members || groups[start] != -1) continue
            val group = nextGroup++
            val queue = ArrayDeque<Int>()
            queue.add(start)
            groups[start] = group

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                // A decoupler joins nothing. It's where the plumbing stops.
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

    /**
     * Which tanks are drunk first: each part's feed tier, the number of feeding decouplers between
     * it and the root it hangs from. A side booster on a feed clamp is 1, one on a booster is 2,
     * and the core is 0. The highest tier with anything in it is drawn from first, so the boosters
     * run dry in order, outside in, and the core stays full until they've gone.
     */
    fun tiers(design: CraftDesign, defs: List<PartDef>): IntArray {
        val count = design.parts.size
        val out = IntArray(count) { -1 }
        fun tier(i: Int): Int {
            if (out[i] >= 0) return out[i]
            val parent = design.parts[i].parentIndex
            val above = if (parent in 0 until count && parent != i) tier(parent) else 0
            val here = if (defs[i].module<Decoupler>()?.feeds == true) 1 else 0
            out[i] = above + here
            return out[i]
        }
        for (i in 0 until count) tier(i)
        return out
    }
}
