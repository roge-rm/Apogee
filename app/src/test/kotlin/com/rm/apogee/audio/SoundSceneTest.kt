package com.rm.apogee.audio

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.Exhaust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoundSceneTest {

    private val air = SoundScene.Listener(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), density = 1.2)
    private val vacuum = SoundScene.Listener(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), density = 0.0)

    private fun rocket(id: Long, own: Boolean, at: Vec3, output: Double = 1.0) =
        SoundScene.Craft(id, own, at, pressure = 1.0).also { it.engine(Exhaust.ROCKET, output, 200_000.0) }

    private fun SoundScene.indexOf(recipe: Int) = (0 until count).firstOrNull { recipes[it] == recipe }
    private fun SoundScene.param(i: Int, p: Int) = params[i * SharedParams.COUNT + p]

    @Test
    fun `the native and Kotlin recipe lists agree`() {
        val header = File("src/main/cpp/synth/recipes.h").readText()
        val native = Regex("""constexpr int ([A-Z_]+) = (\d+);""").findAll(header)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val fields = Recipes::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && '$' !in it.name && it.name != "INSTANCE" }
        assertTrue("found the recipes", fields.size > 20)
        for (field in fields) {
            val name = field.name
            assertEquals("recipe $name", native[name], field.getInt(null))
        }
    }

    @Test
    fun `in the air a far engine is quieter, duller, and off to one side`() {
        val scene = SoundScene(16)
        scene.build(air, listOf(rocket(1, own = false, at = Vec3(30.0, 0.0, 0.0))), null)
        val near = scene.indexOf(Recipes.ROCKET)!!
        val nearGain = scene.param(near, SharedParams.GAIN)
        val nearLowpass = scene.param(near, SharedParams.LOWPASS)
        assertTrue("to the right", scene.param(near, SharedParams.PAN) > 0.5f)

        scene.build(air, listOf(rocket(1, own = false, at = Vec3(3_000.0, 0.0, 0.0))), null)
        val far = scene.indexOf(Recipes.ROCKET)!!
        assertTrue(scene.param(far, SharedParams.GAIN) < nearGain * 0.2f)
        assertTrue(scene.param(far, SharedParams.LOWPASS) < nearLowpass * 0.5f)
    }

    /** Space is silent, except for your own craft, through its hull. */
    @Test
    fun `in vacuum you only hear the flown craft, through its hull`() {
        val scene = SoundScene(16)
        scene.build(vacuum, listOf(rocket(1, own = true, at = Vec3.zero()), rocket(2, own = false, at = Vec3(10.0, 0.0, 0.0))), null)
        assertEquals("one engine", 1, (0 until scene.count).count { scene.recipes[it] == Recipes.ROCKET })
        val i = scene.indexOf(Recipes.ROCKET)!!
        assertEquals(VoiceFlags.HULL, scene.flags[i] and VoiceFlags.HULL)
        assertNull("no wind in space", scene.indexOf(Recipes.WIND))
    }

    @Test
    fun `a flogging sail is heard in a wind, louder in more, and not in a calm`() {
        fun heard(wind: Double): Float? {
            val scene = SoundScene(16)
            val listener = SoundScene.Listener(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), density = 1.2, wind = wind)
            val boat = SoundScene.Craft(1, own = true, Vec3(5.0, 0.0, 0.0), pressure = 1.0).also { it.luff = 1.0 }
            scene.build(listener, listOf(boat), null)
            return scene.indexOf(Recipes.SAIL)?.let { scene.param(it, 1) }
        }
        assertNull("calm", heard(0.5))
        val breeze = heard(6.0)!!
        val gale = heard(18.0)!!
        assertTrue("stronger in more wind: $breeze, $gale", gale > breeze)
        // Drawing well, not flogging, it's silent.
        val scene = SoundScene(16)
        val listener = SoundScene.Listener(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0), density = 1.2, wind = 10.0)
        scene.build(listener, listOf(SoundScene.Craft(1, own = true, Vec3(5.0, 0.0, 0.0), pressure = 1.0)), null)
        assertNull(scene.indexOf(Recipes.SAIL))
    }

    @Test
    fun `a dry engine makes no sound`() {
        val scene = SoundScene(16)
        scene.build(air, listOf(rocket(1, own = true, at = Vec3.zero(), output = 0.0)), null)
        assertNull(scene.indexOf(Recipes.ROCKET))
    }

    /** The flash first and the bang after. A blast a kilometre away arrives three seconds late. */
    @Test
    fun `a distant explosion arrives at the speed of sound`() {
        val scene = SoundScene(16)
        val shot = scene.shot(air, SoundScene.Kind.EXPLOSION, Vec3(1_029.0, 0.0, 0.0), 2_000.0, own = false)
        assertNotNull(shot)
        assertEquals(3.0f, shot!!.delay, 0.01f)
        assertNull("but not in vacuum", scene.shot(vacuum, SoundScene.Kind.EXPLOSION, Vec3(100.0, 0.0, 0.0), 2_000.0, own = false))
        val felt = scene.shot(vacuum, SoundScene.Kind.IMPACT, Vec3.zero(), 20.0, own = true)
        assertEquals("your own knocks are felt at once, through the hull", 0f, felt!!.delay, 0f)
        assertEquals(VoiceFlags.HULL, felt.flags)
    }

    @Test
    fun `no more voices than the budget, and the loudest are kept`() {
        val scene = SoundScene(4)
        val crafts = (1..10).map { rocket(it.toLong(), own = false, at = Vec3(it * 100.0, 0.0, 0.0)) }
        scene.build(air, crafts, null)
        assertEquals(4, scene.count)
        val kept = (0 until scene.count).map { scene.keys[it] / SoundScene.CRAFT_SLOTS }
        assertTrue("the nearest kept: $kept", 1 in kept)
        assertTrue("the farthest dropped: $kept", 10 !in kept)
    }

    @Test
    fun `a craft coming closer sounds higher, going away sounds lower, and your own sounds as it is`() {
        val scene = SoundScene(16)
        // 500 m away to the east, flying west toward us at 150 m/s.
        val coming = SoundScene.Craft(1, false, Vec3(500.0, 0.0, 0.0), 1.0, velocity = Vec3(-150.0, 0.0, 0.0))
        val going = SoundScene.Craft(1, false, Vec3(500.0, 0.0, 0.0), 1.0, velocity = Vec3(150.0, 0.0, 0.0))
        assertEquals(343.0 / (343.0 - 150.0), scene.doppler(air, coming), 1e-9)
        assertEquals(343.0 / (343.0 + 150.0), scene.doppler(air, going), 1e-9)
        // Riding along with it at the same speed, so there's no shift.
        val riding = SoundScene.Listener(Vec3(), Vec3(1.0, 0.0, 0.0), density = 1.2, velocity = Vec3(150.0, 0.0, 0.0))
        val ours = SoundScene.Craft(1, true, Vec3(10.0, 0.0, 0.0), 1.0, velocity = Vec3(150.0, 0.0, 0.0))
        assertEquals(1.0, scene.doppler(riding, ours), 1e-9)
        // Past the speed of sound, held to an octave.
        val fast = SoundScene.Craft(1, false, Vec3(500.0, 0.0, 0.0), 1.0, velocity = Vec3(-900.0, 0.0, 0.0))
        assertEquals(2.0, scene.doppler(air, fast), 1e-9)

        coming.engine(Exhaust.JET, 1.0, 30_000.0)
        scene.build(air, listOf(coming), null)
        assertTrue(scene.param(scene.indexOf(Recipes.JET)!!, SharedParams.PITCH) > 1.5f)
    }

    @Test
    fun `a vacuum engine sounds like one, and a booster like a booster`() {
        assertEquals(0.22, SoundScene.vacuumBuilt(167_000.0, 215_000.0), 0.01)
        assertEquals(0.75, SoundScene.vacuumBuilt(15_000.0, 60_000.0), 0.01)
        val craft = SoundScene.Craft(1, true, Vec3(), 1.0)
        craft.engine(Exhaust.ROCKET, 1.0, 60_000.0, character = 0.75)
        val scene = SoundScene(16)
        scene.build(air, listOf(craft), null)
        assertEquals(0.75f, scene.param(scene.indexOf(Recipes.ROCKET)!!, 4), 1e-6f)
    }

    @Test
    fun `you hear surf by the shore, and not in space`() {
        val scene = SoundScene(16)
        val beach = SoundScene.Listener(Vec3(), Vec3(1.0, 0.0, 0.0), density = 1.2, shore = 0.8)
        scene.build(beach, emptyList(), null)
        assertNotNull(scene.indexOf(Recipes.SURF))
        scene.build(air, emptyList(), null)
        assertNull("inland", scene.indexOf(Recipes.SURF))
        scene.build(SoundScene.Listener(Vec3(), Vec3(1.0, 0.0, 0.0), density = 0.0, shore = 0.8), emptyList(), null)
        assertNull("in vacuum", scene.indexOf(Recipes.SURF))
    }

    @Test
    fun `thrusters puff in the air, come through the hull in vacuum, and another craft's are silent there`() {
        val scene = SoundScene(16)
        val mine = SoundScene.Craft(1, true, Vec3(), 1.0).also { it.rcs(0.8) }
        scene.build(air, listOf(mine), null)
        val i = scene.indexOf(Recipes.RCS)!!
        assertEquals(0.8f, scene.param(i, 0), 1e-6f)
        assertEquals(0, scene.flags[i] and VoiceFlags.HULL)

        scene.build(vacuum, listOf(mine), null)
        assertEquals(VoiceFlags.HULL, scene.flags[scene.indexOf(Recipes.RCS)!!] and VoiceFlags.HULL)

        val theirs = SoundScene.Craft(2, false, Vec3(20.0, 0.0, 0.0), 0.0).also { it.rcs(1.0) }
        scene.build(vacuum, listOf(theirs), null)
        assertNull(scene.indexOf(Recipes.RCS))
    }
}
