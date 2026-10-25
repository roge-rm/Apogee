package com.rm.apogee.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {
    private fun pick(i: UiScaleInputs) = UiScale.pick(i)

    @Test
    fun toolkitFirst() {
        val p = pick(UiScaleInputs(awt = 1.5f, env = mapOf("GDK_SCALE" to "2")))
        assertEquals(1.5f, p.scale, 0f)
        assertEquals("window toolkit", p.source)
    }

    @Test
    fun envThenXrdbThenKde() {
        assertEquals(2f, pick(UiScaleInputs(env = mapOf("GDK_SCALE" to "2"))).scale, 0f)
        assertEquals(1.5f, pick(UiScaleInputs(env = mapOf("GDK_SCALE" to "1", "GDK_DPI_SCALE" to "1.5"))).scale, 0f)
        assertEquals(1.75f, pick(UiScaleInputs(env = mapOf("QT_SCALE_FACTOR" to "1.75"))).scale, 0f)
        assertEquals(1.25f, pick(UiScaleInputs(xrdb = "Xft.antialias:\t1\nXft.dpi:\t120\n")).scale, 0f)
        val kde = "[General]\nScaleFactor=3\n[KScreen]\nScaleFactor=1.5\n"
        assertEquals(1.5f, pick(UiScaleInputs(kdeglobals = kde)).scale, 0f)
        assertEquals(2f, pick(UiScaleInputs(kdeglobals = "[KScreen]\nScreenScaleFactors=eDP-1=2;HDMI-1=1;\n")).scale, 0f)
    }

    @Test
    fun kwinOutputs() {
        val json = """[{"data":[{"name":"eDP-1","scale":1.75},{"name":"DP-2","scale":1}],"name":"outputs"}]"""
        assertEquals(1.75f, pick(UiScaleInputs(kwinOutputs = json)).scale, 0f)
        assertEquals(1.25f, pick(UiScaleInputs(xrdb = "Xft.dpi: 120", kwinOutputs = json)).scale, 0f)
    }

    @Test
    fun ones_are_skipped() {
        val p = pick(UiScaleInputs(env = mapOf("GDK_SCALE" to "1"), xrdb = "Xft.dpi: 96", monitorDpi = 165f))
        assertEquals(1.5f, p.scale, 0f)
    }

    @Test
    fun deckBeforePhysical() {
        assertEquals(1.5f, pick(UiScaleInputs(steamDeck = true, monitorDpi = 216f)).scale, 0f)
    }

    @Test
    fun physicalDpi() {
        // 11 inch 1920x1200 is about 205 dpi.
        assertEquals(1.75f, pick(UiScaleInputs(monitorDpi = 192f)).scale, 0f)
        assertEquals(2.5f, pick(UiScaleInputs(monitorDpi = 400f)).scale, 0f)
        assertEquals(1f, pick(UiScaleInputs(monitorDpi = 96f)).scale, 0f)
        assertEquals(1f, pick(UiScaleInputs(monitorDpi = 500f)).scale, 0f)
        assertEquals(1f, pick(UiScaleInputs(monitorDpi = 50f)).scale, 0f)
        assertEquals(1f, pick(UiScaleInputs(monitorDpi = null)).scale, 0f)
    }

    @Test
    fun xrandr() {
        val out = "Screen 0: minimum 16 x 16\nHDMI-1 disconnected (normal)\neDP-1 connected primary 1920x1200+0+0 (normal) 301mm x 188mm\n"
        assertEquals(162f, UiScale.xrandrDpi(out)!!, 1f)
        assertEquals(null, UiScale.xrandrDpi("DP-1 connected 1920x1080+0+0 (normal) 0mm x 0mm\n"))
    }

    @Test
    fun hungCommandTimesOut() {
        val start = System.nanoTime()
        assertEquals(null, UiScale.run("sleep", "30", seconds = 1))
        val took = (System.nanoTime() - start) / 1e9
        org.junit.Assert.assertTrue("took $took s", took < 5)
        assertEquals("hi\n", UiScale.run("echo", "hi"))
    }
}
