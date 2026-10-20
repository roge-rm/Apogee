package com.rm.apogee.game

import com.rm.apogee.core.craft.CraftStore
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.render.FrameBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuilderWorkingCopyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `leaving and returning restores unfinished edits and an empty new vehicle`() {
        val store = CraftStore(folder.newFolder("craft"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val catalog = StockParts.catalog
        fun open() = BuilderSession(FrameBus(), catalog, store).also { it.start(scope) }
        try {
            val first = open()
            val stock = StockCraft.sparkler(catalog)
            val edited = stock.copy(name = "Still building", stages = stock.stages.reversed(), manualStaging = true)
            first.edit(edited)
            first.stop()
            val second = open()
            assertEquals(edited, second.builder.design)
            assertTrue(second.savedCraft.isEmpty())
            second.clear()
            val empty = second.builder.design
            second.stop()
            val third = open()
            assertEquals(empty, third.builder.design)
            third.stop()
        } finally { scope.cancel() }
    }
}
