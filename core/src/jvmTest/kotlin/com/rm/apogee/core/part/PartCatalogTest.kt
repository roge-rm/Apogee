package com.rm.apogee.core.part

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartCatalogTest {

    @Test
    fun `stock catalogue parses`() {
        val catalog = StockParts.catalog
        assertTrue("expected a non-trivial catalogue, got ${catalog.size}", catalog.size >= 8)
        assertNotNull(catalog["pod-halo"])
        assertNotNull(catalog["engine-ember"])
    }

    @Test
    fun `engine modules survive polymorphic decoding`() {
        val engine = StockParts.catalog.require("engine-ember").module<Engine>()
        assertNotNull("the Ember lifter should carry an Engine module", engine)
        assertEquals(215_000.0, engine!!.thrustVacuum, 0.0)
        assertEquals(320.0, engine.ispVacuum, 0.0)
        assertTrue("lifter should out-thrust at altitude", engine.thrustVacuum > engine.thrustSeaLevel)
    }

    @Test
    fun `wet mass accounts for a full propellant load`() {
        val tank = StockParts.catalog.require("tank-cask2")
        // 200 units at 5 kg/unit on top of 250 kg of structure.
        assertEquals(1250.0, tank.wetMass, 1e-9)
    }

    @Test
    fun `content hash ignores ordering and formatting`() {
        val defs = StockParts.catalog.parts.values.toList()
        val forward = PartCatalog.of(defs)
        val reversed = PartCatalog.of(defs.reversed())
        assertEquals(
            "catalogue hash must depend on content, not on file order",
            forward.contentHash,
            reversed.contentHash,
        )
    }

    @Test
    fun `content hash changes when a part changes`() {
        val defs = StockParts.catalog.parts.values.toList()
        val tweaked = defs.map { if (it.id == "tank-cask2") it.copy(dryMass = 251.0) else it }
        assertTrue(
            "a mass change must move the hash, or a mismatched client connects cleanly",
            PartCatalog.of(defs).contentHash != PartCatalog.of(tweaked).contentHash,
        )
    }

    @Test
    fun `duplicate ids are rejected at load`() {
        val one = StockParts.catalog.require("tank-cask2")
        val failure = runCatching { PartCatalog.of(listOf(one, one)) }.exceptionOrNull()
        assertTrue("expected a duplicate-id failure, got $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("Duplicate part id"))
    }

    @Test
    fun `every attach node direction is a usable unit vector`() {
        for (part in StockParts.catalog.parts.values) {
            for (node in part.attachNodes) {
                assertEquals(
                    "${part.id}:${node.id} direction should be unit length",
                    1.0,
                    node.direction.length,
                    1e-9,
                )
            }
        }
    }
}
