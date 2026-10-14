package com.rm.apogee.desktop

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.render.*
import org.junit.Assert.*
import org.junit.Test

class CloudTransitionsTest {
    private fun puff(id: Int, x: Double = 0.0, opacity: Float = .8f, detail: Int = 2) = RenderItem(
        CloudPuff(id % 12, false, detail), Vec3(x, 0.0, 0.0), Quat.identity(),
        floatArrayOf(.4f, .4f, .4f, opacity), scale = Vec3(1000.0, 2000.0, 1000.0),
        key = RenderItem.cloudKey(123, id), orderedTransparency = true)

    @Test fun budgetReplacementAndWorkerCorrectionsAreContinuousAndFinishWhilePaused() {
        val sky = CloudTransitions()
        val a = Any(); val b = Any()
        fun at(items: List<RenderItem>, revision: Any, time: Long) = ArrayList<RenderItem>().also { sky.append(items, revision, time, it) }
        val first = at(listOf(puff(1)), a, 0)
        assertEquals(0f, first.single().color[3], 0f)
        val full = at(listOf(puff(1)), a, 500_000_000)
        assertEquals(.8f, full.single().color[3], 0f)
        val changed = at(listOf(puff(2)), b, 600_000_000)
        assertEquals(.8f, changed.first { it.key == puff(1).key }.color[3], 0f)
        assertEquals(0f, changed.first { it.key == puff(2).key }.color[3], 0f)
        val midway = at(listOf(puff(2)), b, 850_000_000)
        assertEquals(.4f, midway.first { it.key == puff(1).key }.color[3], .001f)
        assertEquals(.4f, midway.first { it.key == puff(2).key }.color[3], .001f)
        assertEquals(puff(2).key, at(listOf(puff(2)), b, 1_100_000_000).single().key)
        val c = Any()
        assertEquals(0.0, at(listOf(puff(2, 1000.0)), c, 1_200_000_000).single().position.x, .001)
        assertEquals(500.0, at(listOf(puff(2, 1000.0)), c, 1_450_000_000).single().position.x, .001)
        assertEquals(1000.0, at(listOf(puff(2, 1000.0)), c, 1_700_000_000).single().position.x, .001)
    }

    @Test fun reorderingCloudsCannotMatchThemToUnrelatedPuffsOrSwitchTheirMesh() {
        val sky = CloudTransitions(); val first = Any(); val second = Any()
        val before = listOf(puff(1, 100.0), puff(2, 8000.0))
        sky.append(before, first, 0, ArrayList())
        sky.append(before, first, 500_000_000, ArrayList())
        val reordered = ArrayList<RenderItem>()
        sky.append(listOf(puff(2, 8000.0, detail = 1), puff(1, 100.0, detail = 3)), second, 600_000_000, reordered)
        val matcher = ItemMatcher()
        matcher.match(reordered, before)
        for ((index, item) in reordered.withIndex()) {
            assertEquals(item.key, matcher.partners[index]!!.key)
            assertEquals(matcher.partners[index]!!.position.x, item.position.x, 0.0)
            assertEquals(2, (item.shape as CloudPuff).detail)
        }
    }
    @Test fun frequentRefreshesNeitherRestartFadeOutNorPreventNewCloudsBecomingVisible() {
        val sky = CloudTransitions()
        val initial = Any()
        sky.append(listOf(puff(1)), initial, 0, ArrayList())
        sky.append(listOf(puff(1)), initial, 500_000_000, ArrayList())
        var frame = ArrayList<RenderItem>()
        for (step in 0..12) {
            frame = ArrayList()
            sky.append(listOf(puff(2)), Any(), 600_000_000L + step * 50_000_000, frame)
        }
        assertEquals("old cloud expires despite new publications", puff(2).key, frame.single().key)
        assertEquals("new cloud reaches full opacity", .8f, frame.single().color[3], .001f)
    }

}
