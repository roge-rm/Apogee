package com.rm.apogee.game.tutorial

import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.world.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialsTest {
    private val catalog = StockParts.catalog

    @Test
    fun `every tutorial has a unique id and some steps`() {
        val ids = Tutorials.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (t in Tutorials.all) assertTrue("${t.id} has no steps", t.steps.isNotEmpty())
    }

    @Test
    fun `every craft is sound and every site exists`() {
        for (t in Tutorials.all) {
            when (val start = t.start) {
                is TutorialStart.OnSite -> {
                    assertTrue("${t.id}: ${start.siteId}", World.launchSites.any { it.id == start.siteId })
                    assertEquals("${t.id}'s craft", emptyList<String>(), start.craft(catalog).validate(catalog))
                }
                is TutorialStart.InOrbit -> assertEquals("${t.id}'s craft", emptyList<String>(), start.craft(catalog).validate(catalog))
                TutorialStart.Builder -> {}
            }
        }
    }

    @Test
    fun `the words are short and plain`() {
        val us = Regex("\\b(color|center|meter|gray)\\b", RegexOption.IGNORE_CASE)
        for (t in Tutorials.all) for (line in t.steps.map { it.text } + t.title) {
            assertTrue("too long: $line", line.length <= 48)
            assertTrue("a dash: $line", !line.contains('-') && !line.contains('–') && !line.contains('—'))
            assertTrue("US spelling: $line", !us.containsMatchIn(line))
            assertTrue(line, !Regex("ksp|kerbal|kerbin", RegexOption.IGNORE_CASE).containsMatchIn(line))
        }
    }
}
