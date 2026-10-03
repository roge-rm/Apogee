package com.rm.apogee.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadBindingsTest {

    @Test
    fun `the Retroid layout gives every button and stick something to do, flying and on foot`() {
        val bindings = PadBindings.RETROID_MINI
        for (layer in PadLayer.entries) {
            for (button in PadButton.entries) {
                assertTrue("$layer $button", bindings.action(layer, button) != PadAction.NONE)
            }
            assertEquals(StickUse.STEER, bindings.stick(layer, PadStick.LEFT))
            assertEquals(StickUse.LOOK, bindings.stick(layer, PadStick.RIGHT))
        }
        assertEquals(PadAction.STAGE, bindings.action(PadLayer.FLYING, PadButton.A))
        assertEquals(PadAction.JUMP, bindings.action(PadLayer.ON_FOOT, PadButton.A))
        // On foot the triggers swim up and down, since there's no throttle to work.
        assertEquals(PadAction.SWIM_UP, bindings.action(PadLayer.ON_FOOT, PadButton.R2))
        assertEquals(PadAction.SWIM_DOWN, bindings.action(PadLayer.ON_FOOT, PadButton.L2))
    }

    @Test
    fun `a layout comes back the same after being saved`() {
        val changed = PadBindings.RETROID_MINI
            .with(PadLayer.FLYING, PadButton.B, PadAction.THROTTLE_CUT)
            .with(PadLayer.ON_FOOT, PadStick.RIGHT, StickUse.NOTHING)
        val read = PadBindings.parse(changed.format())
        assertEquals(changed, read)
        assertEquals(PadAction.THROTTLE_CUT, read.action(PadLayer.FLYING, PadButton.B))
        assertEquals(StickUse.NOTHING, read.stick(PadLayer.ON_FOOT, PadStick.RIGHT))
    }

    @Test
    fun `anything missing or unknown in a saved layout falls back to the default`() {
        val read = PadBindings.parse("f.b=no-such-thing;f.q=stage;x.a=map;f.y=flaps;garbage")
        assertEquals(PadAction.BRAKES, read.action(PadLayer.FLYING, PadButton.B))
        assertEquals(PadAction.FLAPS, read.action(PadLayer.FLYING, PadButton.Y))
        assertEquals(PadAction.STAGE, read.action(PadLayer.FLYING, PadButton.A))
        assertEquals(PadBindings.RETROID_MINI, PadBindings.parse(""))
    }
}

class PadInputTest {

    /** Everything the controller asked for, in order. */
    private class Target : PadTarget {
        val steers = ArrayList<Pair<Float, Float>>()
        val rolls = ArrayList<Float>()
        val actions = ArrayList<PadAction>()
        val zooms = ArrayList<Float>()
        var looked = 0.0 to 0.0
        var holds = ArrayList<Float>()
        var level = 0f
        override val throttle: Float get() = level
        override fun steer(pitch: Float, yaw: Float) { steers += pitch to yaw }
        override fun roll(roll: Float) { rolls += roll }
        override fun look(yaw: Double, pitch: Double) { looked = looked.first + yaw to looked.second + pitch }
        override fun zoom(factor: Float) { zooms += factor }
        override fun setThrottle(value: Float) { level = value }
        override fun act(action: PadAction) { actions += action }
        override fun stageHold(fraction: Float) { holds += fraction }
    }

    private var config = PadConfig()
    private val input = PadInput { config }
    private val target = Target()
    private val state = PadState()
    private var now = 1_000_000_000L

    private fun frame(mode: PadMode = PadMode.FLIGHT, layer: PadLayer = PadLayer.FLYING, millis: Long = 16) {
        now += millis * 1_000_000L
        input.tick(now, mode, layer, target)
    }

    private fun press(button: PadButton, down: Boolean) {
        state.press(button, down)
        input.update(state)
    }

    @Test
    fun `a stick inside the dead zone steers nothing, and past it steers once per change`() {
        frame()
        state.leftX = 0.1f; state.leftY = -0.05f
        input.update(state)
        frame()
        assertTrue(target.steers.isEmpty())
        state.leftX = 0f; state.leftY = -1f
        input.update(state)
        frame(); frame(); frame()
        assertEquals(listOf(1f to 0f), target.steers)
        state.leftY = 0f
        input.update(state)
        frame()
        assertEquals(0f to 0f, target.steers.last())
    }

    @Test
    fun `R2 held all the way opens the throttle end to end in a second and a half`() {
        frame()
        state[PadButton.R2] = 1f
        input.update(state)
        repeat(15) { frame(millis = 100) }
        assertEquals(1f, target.throttle, 1e-3f)
        state[PadButton.R2] = 0f
        state[PadButton.L2] = 0.5f
        input.update(state)
        repeat(15) { frame(millis = 100) }
        assertEquals(0.5f, target.throttle, 1e-3f)
    }

    @Test
    fun `Select tapped works the gear, and held switches the lights`() {
        frame()
        press(PadButton.SELECT, true)
        frame()
        press(PadButton.SELECT, false)
        frame()
        assertEquals(listOf(PadAction.DEPLOY), target.actions)
        target.actions.clear()
        press(PadButton.SELECT, true)
        repeat(8) { frame(millis = 100) }
        press(PadButton.SELECT, false)
        frame()
        assertEquals(listOf(PadAction.LIGHTS), target.actions)
    }

    @Test
    fun `A has to be held a moment to stage, and a tap doesn't`() {
        frame()
        press(PadButton.A, true)
        frame()
        press(PadButton.A, false)
        frame(millis = 400)
        assertTrue(target.actions.isEmpty())

        press(PadButton.A, true)
        frame()
        frame(millis = 150)
        assertTrue(target.actions.isEmpty())
        assertTrue(target.holds.last() > 0.4f)
        frame(millis = 200)
        assertEquals(listOf(PadAction.STAGE), target.actions)
        frame(millis = 500)
        assertEquals("only once for one hold", 1, target.actions.size)
        assertEquals(0f, target.holds.last())
    }

    @Test
    fun `without the hold, A stages as it goes down`() {
        config = config.copy(holdToStage = false)
        frame()
        press(PadButton.A, true)
        press(PadButton.A, false)
        frame()
        assertEquals(listOf(PadAction.STAGE), target.actions)
    }

    @Test
    fun `a press happens once, even if it's let go before the frame`() {
        frame()
        press(PadButton.X, true)
        frame(); frame(); frame()
        press(PadButton.X, false)
        press(PadButton.Y, true)
        press(PadButton.Y, false)
        frame()
        assertEquals(listOf(PadAction.SAS, PadAction.MAP), target.actions)
    }

    @Test
    fun `on foot the same buttons jump, grab and board`() {
        frame(layer = PadLayer.ON_FOOT)
        press(PadButton.A, true)
        press(PadButton.B, true)
        press(PadButton.X, true)
        frame(layer = PadLayer.ON_FOOT)
        assertEquals(listOf(PadAction.JUMP, PadAction.GRAB, PadAction.BOARD), target.actions)
    }

    @Test
    fun `nothing reaches the craft while a menu has the controller, and the stick lets go`() {
        frame()
        state.leftX = 1f
        input.update(state)
        frame()
        assertEquals(1, target.steers.size)
        press(PadButton.X, true)
        frame(PadMode.MENU)
        assertEquals(0f to 0f, target.steers.last())
        assertTrue(target.actions.isEmpty())
        // Still held when the menu closes: not a new press.
        frame()
        assertTrue(target.actions.isEmpty())
    }

    @Test
    fun `the shoulders roll and the D-pad zooms while they're held`() {
        frame()
        press(PadButton.R1, true)
        press(PadButton.UP, true)
        frame()
        assertEquals(listOf(1f), target.rolls)
        assertTrue(target.zooms.single() > 1f)
        press(PadButton.R1, false)
        frame()
        assertEquals(listOf(1f, 0f), target.rolls)
    }

    @Test
    fun `the look stick turns the camera, the other way up when inverted`() {
        frame()
        state.rightX = 1f; state.rightY = -1f
        input.update(state)
        frame(millis = 100)
        val (yaw, pitch) = target.looked
        assertTrue(yaw > 0.0 && pitch > 0.0)
        config = config.copy(invertLook = true)
        target.looked = 0.0 to 0.0
        frame(millis = 100)
        assertTrue(target.looked.second < 0.0)
    }

    @Test
    fun `time warp steps through the rates and stops at either end`() {
        val rates = listOf(0.0, 1.0, 2.0, 4.0, 10.0)
        assertEquals(2.0, PadInput.warpStep(1.0, true, rates)!!, 0.0)
        assertEquals(0.0, PadInput.warpStep(1.0, false, rates)!!, 0.0)
        assertNull(PadInput.warpStep(10.0, true, rates))
        assertNull(PadInput.warpStep(0.0, false, rates))
        assertEquals(10.0, PadInput.warpStep(5.0, true, rates)!!, 0.0)
    }

    @Test
    fun `the last button pressed is kept for the settings page`() {
        press(PadButton.R3, true)
        assertEquals(PadButton.R3, input.lastPressed)
        assertEquals(1, input.presses)
    }
}
