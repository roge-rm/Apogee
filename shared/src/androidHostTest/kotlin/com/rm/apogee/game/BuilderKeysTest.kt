package com.rm.apogee.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuilderKeysTest {
    @Test
    fun `control keys`() {
        assertEquals(BuilderKey.UNDO, BuilderKeys.map("KeyZ", ctrl = true, shift = false))
        assertEquals(BuilderKey.REDO, BuilderKeys.map("KeyZ", ctrl = true, shift = true))
        assertEquals(BuilderKey.REDO, BuilderKeys.map("KeyY", ctrl = true, shift = false))
        assertEquals(BuilderKey.DUPLICATE, BuilderKeys.map("KeyD", ctrl = true, shift = false))
        assertEquals(BuilderKey.SAVE, BuilderKeys.map("KeyS", ctrl = true, shift = false))
    }

    @Test
    fun `plain keys`() {
        assertEquals(BuilderKey.DELETE, BuilderKeys.map("Delete", ctrl = false, shift = false))
        assertEquals(BuilderKey.DELETE, BuilderKeys.map("Backspace", ctrl = false, shift = false))
        assertEquals(BuilderKey.TURN, BuilderKeys.map("KeyR", ctrl = false, shift = false))
        assertEquals(BuilderKey.SYMMETRY, BuilderKeys.map("KeyX", ctrl = false, shift = false))
        assertEquals(BuilderKey.GROUP, BuilderKeys.map("KeyG", ctrl = false, shift = false))
        assertEquals(BuilderKey.CANCEL, BuilderKeys.map("Escape", ctrl = false, shift = false))
        assertEquals(BuilderKey.RECENTRE, BuilderKeys.map("KeyF", ctrl = false, shift = false))
        assertEquals(BuilderKey.PAN_LEFT, BuilderKeys.map("ArrowLeft", ctrl = false, shift = false))
    }

    @Test
    fun `other combinations are left alone`() {
        assertNull(BuilderKeys.map("KeyZ", ctrl = false, shift = false))
        assertNull(BuilderKeys.map("KeyR", ctrl = true, shift = false))
        assertNull(BuilderKeys.map("Delete", ctrl = true, shift = false))
        assertNull(BuilderKeys.map("Space", ctrl = false, shift = false))
    }
}
