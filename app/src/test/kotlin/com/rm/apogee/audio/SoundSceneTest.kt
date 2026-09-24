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
    fun `in the air, a far engine is quieter, duller, and to one side`() {
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

    /** Space is silent - except for your own craft, through its hull. */
    @Test
    fun `in vacuum only the flown craft is heard, through its hull`() {
        val scene = SoundScene(16)
        scene.build(vacuum, listOf(rocket(1, own = true, at = Vec3.zero()), rocket(2, own = false, at = Vec3(10.0, 0.0, 0.0))), null)
        assertEquals("one engine", 1, (0 until scene.count).count { scene.recipes[it] == Recipes.ROCKET })
        val i = scene.indexOf(Recipes.ROCKET)!!
        assertEquals(VoiceFlags.HULL, scene.flags[i] and VoiceFlags.HULL)
        assertNull("no wind in space", scene.indexOf(Recipes.WIND))
    }

    @Test
    fun `a dry engine makes no sound`() {
        val scene = SoundScene(16)
        scene.build(air, listOf(rocket(1, own = true, at = Vec3.zero(), output = 0.0)), null)
        assertNull(scene.indexOf(Recipes.ROCKET))
    }

    /** The flash first, the bang after: a blast a kilometre off arrives three seconds late. */
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
    fun `no more voices than the budget, loudest kept`() {
        val scene = SoundScene(4)
        val crafts = (1..10).map { rocket(it.toLong(), own = false, at = Vec3(it * 100.0, 0.0, 0.0)) }
        scene.build(air, crafts, null)
        assertEquals(4, scene.count)
        val kept = (0 until scene.count).map { scene.keys[it] / SoundScene.CRAFT_SLOTS }
        assertTrue("the nearest kept: $kept", 1 in kept)
        assertTrue("the farthest dropped: $kept", 10 !in kept)
    }
}
