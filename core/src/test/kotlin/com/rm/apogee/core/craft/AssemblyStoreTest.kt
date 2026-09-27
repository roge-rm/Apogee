package com.rm.apogee.core.craft

import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Saved pieces of craft, and craft files shared between devices. */
class AssemblyStoreTest {

    @get:Rule val folder = TemporaryFolder()
    private val catalog = StockParts.catalog

    @Test
    fun `a piece saved comes back the same, and a second of the same name doesn't replace it`() {
        val design = StockCraft.starterRocket(catalog)
        val piece = Assemblies.extract(design, 1)!!
        val store = AssemblyStore(folder.newFolder("pieces"))
        store.save("Upper stage", piece).getOrThrow()
        store.save("Upper stage", piece).getOrThrow()
        val saved = store.list()
        assertEquals(2, saved.size)
        assertEquals("Upper stage", saved[0].first.name)
        assertEquals(piece.parts.map { it.partId }, saved[0].first.parts.map { it.partId })
        assertEquals(piece.parts.map { it.parentIndex }, saved[0].first.parts.map { it.parentIndex })
        assertTrue(store.delete(saved[0].second))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `a shared craft file comes back the same, and one with unknown parts is refused`() {
        val store = CraftStore(folder.newFolder("craft"))
        val design = StockCraft.sparrow(catalog)
        val text = store.encode(design)
        // Read, each quaternion is normalised, which can move its last digit, so it comes back
        // the same to within that.
        val back = store.decode(text, catalog).getOrThrow()
        assertEquals(design.parts.map { it.partId to it.parentIndex }, back.parts.map { it.partId to it.parentIndex })
        assertEquals(design.stages, back.stages)
        for ((a, b) in design.parts.zip(back.parts)) {
            assertTrue(a.position.distanceTo(b.position) < 1e-12)
            assertTrue(kotlin.math.abs(kotlin.math.abs(a.rotation.dot(b.rotation)) - 1.0) < 1e-12)
        }
        val odd = design.copy(parts = design.parts.mapIndexed { i, p -> if (i == 0) p.copy(partId = "pod-from-the-future") else p })
        val refused = store.decode(store.encode(odd), catalog)
        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull()!!.message!!.contains("pod-from-the-future"))
        assertTrue(store.decode("not a craft", catalog).isFailure)
        store.save(design)
        assertEquals("${design.name} 2", store.freeName(design.name))
    }
}
