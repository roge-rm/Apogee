package com.rm.apogee.core.world

import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.craft.VesselId
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.SolarSystem
import com.rm.apogee.core.part.StockParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForcesTest {

    private val catalog = StockParts.catalog
    private val system = SolarSystem.defaultSystem()
    private val terra = system.body("terra")

    private fun rocketInVacuum(): Vessel {
        val design = StockCraft.starterRocket(catalog)
        val vessel = Vessel(
            id = VesselId(1),
            design = design,
            defs = design.parts.map { catalog.require(it.partId) },
            referenceBodyId = "terra",
        )
        // Well above the atmosphere, nose along +Y, so body axes are world axes.
        vessel.body.position.setTo(0.0, terra.radius + 200_000.0, 0.0)
        vessel.body.orientation.setIdentity()
        vessel.activateNextStage()
        vessel.recomputeMass(shiftBodyPosition = false)
        return vessel
    }

    /**
     * The regression this exists for: an engine sits below the centre of mass,
     * so a naive gimbal deflection produces torque *opposite* to what a
     * reaction wheel produces for the same command. When they disagree, a craft
     * carrying both is harder to fly than one carrying either, and the symptom
     * on a device is a rocket that sluggishly refuses to turn.
     */
    @Test
    fun `gimbal and reaction wheels torque the craft the same way`() {
        val gimbalOnly = rocketInVacuum()
        gimbalOnly.control.throttle = 1.0
        gimbalOnly.control.pitch = 1.0
        gimbalOnly.body.clearAccumulators()
        Forces().applyThrust(gimbalOnly, terra, 1.0 / 60.0)
        val gimbalTorqueX = gimbalOnly.body.torque.x

        val wheelsOnly = rocketInVacuum()
        wheelsOnly.control.pitch = 1.0
        wheelsOnly.body.clearAccumulators()
        Forces().applyReactionWheels(wheelsOnly)
        val wheelTorqueX = wheelsOnly.body.torque.x

        assertTrue("gimbal produced no torque", kotlin.math.abs(gimbalTorqueX) > 1.0)
        assertTrue("reaction wheels produced no torque", kotlin.math.abs(wheelTorqueX) > 1.0)
        assertEquals(
            "gimbal and reaction wheels must agree in sign " +
                "(gimbal=$gimbalTorqueX, wheels=$wheelTorqueX)",
            kotlin.math.sign(wheelTorqueX),
            kotlin.math.sign(gimbalTorqueX),
            0.0,
        )
    }

    @Test
    fun `a centred engine at zero gimbal produces thrust but no torque`() {
        val vessel = rocketInVacuum()
        vessel.control.throttle = 1.0
        vessel.body.clearAccumulators()

        Forces().applyThrust(vessel, terra, 1.0 / 60.0)

        assertTrue("should produce thrust", vessel.body.force.length > 1_000.0)
        assertTrue(
            "an on-axis engine should not torque the craft, got ${vessel.body.torque}",
            vessel.body.torque.length < 1.0,
        )
    }

    @Test
    fun `thrust is higher in vacuum than at sea level`() {
        val high = rocketInVacuum()
        high.control.throttle = 1.0
        high.body.clearAccumulators()
        Forces().applyThrust(high, terra, 1.0 / 60.0)
        val vacuumThrust = high.body.force.length

        val low = rocketInVacuum()
        low.body.position.setTo(0.0, terra.radius + 10.0, 0.0)
        low.control.throttle = 1.0
        low.body.clearAccumulators()
        Forces().applyThrust(low, terra, 1.0 / 60.0)
        val seaLevelThrust = low.body.force.length

        assertTrue(
            "vacuum thrust ($vacuumThrust) should exceed sea level ($seaLevelThrust)",
            vacuumThrust > seaLevelThrust,
        )
    }

    @Test
    fun `drag opposes motion and vanishes above the atmosphere`() {
        val inAir = rocketInVacuum()
        inAir.body.position.setTo(0.0, terra.radius + 5_000.0, 0.0)
        inAir.body.linearVelocity.setTo(0.0, 400.0, 0.0)
        inAir.body.clearAccumulators()
        Forces().applyDrag(inAir, terra)

        assertTrue("drag should act", inAir.body.force.length > 1.0)
        assertTrue(
            "drag must oppose velocity, got ${inAir.body.force}",
            (inAir.body.force dot inAir.body.linearVelocity) < 0.0,
        )

        val inSpace = rocketInVacuum()
        inSpace.body.linearVelocity.setTo(0.0, 2_000.0, 0.0)
        inSpace.body.clearAccumulators()
        Forces().applyDrag(inSpace, terra)
        assertEquals("no drag above the atmosphere", 0.0, inSpace.body.force.length, 0.0)
    }

    @Test
    fun `gravity pulls toward the attractor and follows the inverse square law`() {
        val vessel = rocketInVacuum()
        vessel.body.clearAccumulators()
        Forces().applyGravity(vessel, terra)

        assertTrue(
            "gravity should point down, got ${vessel.body.force}",
            vessel.body.force.y < 0.0,
        )

        val near = vessel.body.force.length
        val far = rocketInVacuum().also {
            it.body.position.setTo(0.0, (terra.radius + 200_000.0) * 2.0, 0.0)
            it.body.clearAccumulators()
            Forces().applyGravity(it, terra)
        }.body.force.length

        // Twice the distance, a quarter of the force.
        assertEquals(near / 4.0, far, near / 4.0 * 1e-9)
    }

    @Test
    fun `SAS damps rotation rather than adding to it`() {
        val vessel = rocketInVacuum()
        vessel.control.sasEnabled = true
        vessel.body.angularVelocity.setTo(0.0, 0.0, 0.4)
        vessel.body.clearAccumulators()

        Forces().applyReactionWheels(vessel)

        assertTrue(
            "SAS torque must oppose the spin, got ${vessel.body.torque}",
            (vessel.body.torque dot vessel.body.angularVelocity) < 0.0,
        )
    }

    /**
     * The reason drag is applied part by part rather than through the centre of
     * mass.
     *
     * A single central force produces no torque wherever the fins are, so a
     * finned rocket flew exactly like a finless one - the fins were pure mass.
     * Here the craft is put at an angle of attack and the aerodynamic torque
     * has to push the nose back toward the airflow.
     */
    @Test
    fun `fins produce a restoring torque at angle of attack`() {
        fun torqueAtAngleOfAttack(withFins: Boolean): Double {
            val full = StockCraft.starterRocket(catalog)
            val design = if (withFins) full else full.copy(
                parts = full.parts.filter { it.partId != "fin-vane" },
                stages = emptyList(),
            )
            val vessel = Vessel(
                id = VesselId(1),
                design = design,
                defs = design.parts.map { catalog.require(it.partId) },
                referenceBodyId = "terra",
            )
            // Low enough for thick air, nose pitched away from the airflow.
            vessel.body.position.setTo(0.0, terra.radius + 3_000.0, 0.0)
            vessel.body.orientation.setTo(
                com.rm.apogee.core.math.Quat.fromAxisAngle(Vec3.unitX(), 0.25)
            )
            vessel.recomputeMass(shiftBodyPosition = false)
            // Flying straight up, so the angle of attack is the pitch offset.
            vessel.body.linearVelocity.setTo(0.0, 300.0, 0.0)
            vessel.body.clearAccumulators()

            Forces().applyDrag(vessel, terra)
            // Torque about X is what pitches the craft back into line.
            return vessel.body.torque.x
        }

        val finned = torqueAtAngleOfAttack(withFins = true)
        val finless = torqueAtAngleOfAttack(withFins = false)

        assertTrue(
            "fins should produce a restoring torque, got $finned",
            kotlin.math.abs(finned) > 1.0,
        )
        assertTrue(
            "fins should stabilise more than no fins ($finned vs $finless)",
            kotlin.math.abs(finned) > kotlin.math.abs(finless),
        )
        assertEquals(
            "and it must push back toward the airflow, not away from it",
            -kotlin.math.sign(0.25),
            kotlin.math.sign(finned),
            0.0,
        )
    }

    @Test
    fun `drag through the centre of mass produces no torque when aligned`() {
        val vessel = rocketInVacuum()
        vessel.body.position.setTo(0.0, terra.radius + 3_000.0, 0.0)
        vessel.body.orientation.setIdentity()
        vessel.recomputeMass(shiftBodyPosition = false)
        // Flying exactly along its own axis: no angle of attack, no torque.
        vessel.body.linearVelocity.setTo(0.0, 300.0, 0.0)
        vessel.body.clearAccumulators()

        Forces().applyDrag(vessel, terra)

        assertTrue("drag should act", vessel.body.force.length > 1.0)
        assertTrue(
            "a symmetric craft flying straight should feel no aerodynamic torque, " +
                "got ${vessel.body.torque}",
            vessel.body.torque.length < 1.0,
        )
    }

    @Test
    fun `an engine with no propellant produces no thrust`() {
        val vessel = rocketInVacuum()
        val mainEngine = vessel.design.parts.indexOfFirst { it.partId == "engine-ember" }
        vessel.drainFromGroupOf(mainEngine, com.rm.apogee.core.part.ResourceType.PROPELLANT, 99_999.0)
        vessel.control.throttle = 1.0
        vessel.body.clearAccumulators()

        Forces().applyThrust(vessel, terra, 1.0 / 60.0)

        assertEquals(0.0, vessel.body.force.length, 1e-9)
    }
}
