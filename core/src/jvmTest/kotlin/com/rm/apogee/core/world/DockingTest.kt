package com.rm.apogee.core.world

import com.rm.apogee.core.craft.CraftDesign
import com.rm.apogee.core.craft.StockCraft
import com.rm.apogee.core.craft.Vessel
import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.Orbit
import com.rm.apogee.core.part.DockingPort
import com.rm.apogee.core.part.StockParts
import com.rm.apogee.core.physics.PortRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Docking, flown with the player's controls: thrusters slide the craft in, and the rings, clamps
 * and hitches do the rest. In orbit, on the ground and on the water.
 */
class DockingTest {

    private val catalog = StockParts.catalog
    private val dt = 1.0 / 60.0

    private fun portOf(v: Vessel, kind: com.rm.apogee.core.part.DockKind, size: Int? = null): Int =
        v.defs.indices.first { i ->
            v.defs[i].module<DockingPort>()?.let { it.kind == kind && (size == null || it.size == size) } == true &&
                v.design.parts[i].dockedTo < 0
        }

    private fun ref(v: Vessel, part: Int) = PortRef(v, part, v.defs[part].module<DockingPort>()!!).update()

    /** Places [design] with [part] squarely facing [target]'s [targetPart], [gap] m off, at its velocity. */
    private fun placeFacing(world: World, design: CraftDesign, part: Int, target: Vessel, targetPart: Int, gap: Double, turnAbout: Vec3 = Vec3.unitY()): Vessel {
        val t = ref(target, targetPart)
        val v = world.spawnAt(design, target.referenceBodyId, target.body.position.copy(), target.body.linearVelocity.copy(), target.body.orientation.copy())
        // Turn it so its part faces back along the target's.
        val own = ref(v, part)
        val turn = com.rm.apogee.core.math.quatFromTo(own.axis, Vec3().setTo(t.axis).mulInPlace(-1.0))
        // A half turn has no single answer, so turn about the given axis.
        val q = if ((own.axis dot t.axis) > 0.999) Quat.fromAxisAngle(target.body.orientation.rotate(turnAbout, Vec3()), Math.PI, Quat()) else turn
        v.body.orientation.setTo(q * v.body.orientation).normalizeInPlace()
        val placed = ref(v, part)
        v.body.position.addInPlace(Vec3().setTo(t.face).addScaledInPlace(t.axis, gap).subInPlace(placed.face))
        return v
    }

    /**
     * Slides [mover]'s [part] toward [target]'s [targetPart] on RCS, slowing as it closes, as a
     * player does in SLIDE. True once the two are one craft.
     */
    private fun approach(world: World, mover: Vessel, part: Int, target: Vessel, targetPart: Int, seconds: Double, speed: Double = 0.3): Boolean {
        world.apply(Command.SetRcs(mover.id.raw, true))
        repeat((seconds / dt).toInt()) { tick ->
            if (mover.id !in world.vessels.map { it.id } || target.id !in world.vessels.map { it.id } ||
                mover.design.parts.size != moverParts || target.design.parts.size != targetParts
            ) {
                println("docked after ${"%.1f".format(tick * dt)} s")
                return true
            }
            if (tick % 300 == 0) {
                val a = ref(mover, part); val b = ref(target, targetPart)
                println("t ${tick * dt}: gap ${"%.2f".format(a.face.distanceTo(b.face))} m, capturing ${world.capturing(mover.id.raw, target.id.raw)}")
            }
            val a = ref(mover, part); val b = ref(target, targetPart)
            val to = Vec3().setTo(b.face).subInPlace(a.face)
            val va = mover.body.velocityAtOffset(a.offset, Vec3()); val vb = target.body.velocityAtOffset(b.offset, Vec3())
            val rel = va.subInPlace(vb)
            val want = if (to.length > 1e-6) Vec3().setTo(to).normalizeInPlace().mulInPlace(minOf(speed, to.length * 0.3)) else Vec3()
            val command = want.subInPlace(rel).mulInPlace(3.0)
            if (world.capturing(mover.id.raw, target.id.raw)) command.setZero()
            val local = mover.body.orientation.inverseRotate(command, Vec3())
            world.apply(Command.SetTranslation(mover.id.raw, local.x.coerceIn(-1.0, 1.0), local.y.coerceIn(-1.0, 1.0), local.z.coerceIn(-1.0, 1.0)))
            world.step(dt)
        }
        return mover.design.parts.size != moverParts || mover.id !in world.vessels.map { it.id }
    }
    private var moverParts = 0
    private var targetParts = 0

    private fun orbiting(world: World, design: CraftDesign): Vessel {
        val v = world.spawnInOrbit(design, "terra", Orbit.circular(700_000.0, 3.5316000e12))
        v.body.angularVelocity.setZero()
        return v
    }

    private fun dockInOrbit(): Triple<World, Vessel, Vessel> {
        val world = World.default(catalog)
        val tug = StockCraft.portTug(catalog)
        val a = orbiting(world, tug)
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val b = placeFacing(world, tug, port, a, port, gap = 8.0)
        moverParts = b.design.parts.size; targetParts = a.design.parts.size
        return Triple(world, a, b)
    }

    @Test
    fun `two tugs in orbit, flown together slowly, dock into one craft`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val parts = a.design.parts.size + b.design.parts.size
        assertTrue("docked within two minutes", approach(world, b, port, a, port, seconds = 120.0))
        val station = world.vessels.single()
        assertEquals("one craft of both", parts, station.design.parts.size)
        val rings = station.defs.indices.filter { station.defs[it].id == "dock-port" }
        assertEquals("both rings", 2, rings.size)
        assertEquals("latched to each other", rings[1], station.design.parts[rings[0]].dockedTo)
        assertEquals(rings[0], station.design.parts[rings[1]].dockedTo)
        assertNotNull("the one that came knows what it was", rings.firstNotNullOfOrNull { station.design.parts[it].dockedFrom })
        // And it holds together under thrust.
        world.apply(Command.SetThrottle(station.id.raw, 1.0))
        world.stage(station)
        repeat(120) { world.step(dt) }
        assertEquals(1, world.vessels.size)
    }

    @Test
    fun `undocked, each goes its own way as itself, and they can dock again`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        assertTrue(approach(world, b, port, a, port, seconds = 120.0))
        val station = world.vessels.single()
        station.owner = "keeper"
        val ring = station.defs.indices.first { station.defs[it].id == "dock-port" }
        assertTrue("undocks", world.undock(station, ring))
        assertEquals(2, world.vessels.size)
        val other = world.vessels.first { it.id != station.id }
        assertEquals("given its name back", "Port Tug", other.name)
        assertEquals(b.design.parts.size, other.design.parts.size)
        assertEquals(a.design.parts.size, station.design.parts.size)
        assertTrue("the rings are free", station.design.parts.none { it.dockedTo >= 0 } && other.design.parts.none { it.dockedTo >= 0 })
        repeat(600) { world.step(dt) }
        val apart = ref(station, portOf(station, com.rm.apogee.core.part.DockKind.PORT, 1)).face
            .distanceTo(ref(other, portOf(other, com.rm.apogee.core.part.DockKind.PORT, 1)).face)
        assertTrue("pushed apart and drifting ($apart m)", apart > 1.0)
        // Past the grace period, fly it back in.
        repeat((World.UNDOCK_GRACE / dt).toInt()) { world.step(dt) }
        moverParts = other.design.parts.size; targetParts = station.design.parts.size
        val p2 = portOf(other, com.rm.apogee.core.part.DockKind.PORT, 1)
        val s2 = portOf(station, com.rm.apogee.core.part.DockKind.PORT, 1)
        assertTrue("docks again", approach(world, other, p2, station, s2, seconds = 180.0))
        assertEquals(1, world.vessels.size)
    }

    /** A station of three craft, assembled and taken apart, each given back as itself. */
    @Test
    fun `a station of three, assembled and taken apart`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        assertTrue(approach(world, b, port, a, port, seconds = 120.0))
        val station = world.vessels.single()
        // A probe to the small ring on top of one of the tugs.
        val top = portOf(station, com.rm.apogee.core.part.DockKind.PORT, 0)
        val probeDesign = StockCraft.dockProbe(catalog)
        val probeRing = probeDesign.parts.indices.first { probeDesign.parts[it].partId == "dock-port-small" }
        val probe = placeFacing(world, probeDesign, probeRing, station, top, gap = 5.0, turnAbout = Vec3.unitX())
        probe.name = "Dock Probe"
        moverParts = probe.design.parts.size; targetParts = station.design.parts.size
        val total = station.design.parts.size + probe.design.parts.size
        assertTrue("the probe docks", approach(world, probe, probeRing, station, top, seconds = 120.0))
        assertEquals(1, world.vessels.size)
        assertEquals(total, world.vessels.single().design.parts.size)

        // Apart again, last on first off.
        val whole = world.vessels.single()
        val probeSide = whole.defs.indices.first { whole.defs[it].id == "dock-port-small" && whole.design.parts[it].dockedFrom != null }
        assertTrue(world.undock(whole, probeSide))
        assertTrue("the probe is itself again", world.vessels.any { it.name == "Dock Probe" && it.design.parts.size == probeDesign.parts.size })
        val ring = whole.defs.indices.first { whole.defs[it].id == "dock-port" && whole.design.parts[it].dockedTo >= 0 }
        assertTrue(world.undock(whole, ring))
        assertEquals(3, world.vessels.size)
    }

    @Test
    fun `come in too fast and it doesn't latch`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val t = ref(a, port)
        // Two metres a second, straight at it.
        b.body.linearVelocity.addScaledInPlace(t.axis, -2.0)
        repeat(240) { world.step(dt) }
        assertEquals("never latched", 2, world.vessels.size)
    }

    @Test
    fun `ports that aren't lined up don't capture`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        // Turned 40 degrees off, half a metre away.
        val t = ref(a, port)
        val side = Vec3().setTo(t.axis).crossInPlace(a.body.orientation.rotate(Vec3.unitY(), Vec3())).normalizeInPlace()
        b.body.orientation.setTo(Quat.fromAxisAngle(side, Math.toRadians(40.0), Quat()) * b.body.orientation).normalizeInPlace()
        val placed = ref(b, port)
        b.body.position.addInPlace(Vec3().setTo(t.face).addScaledInPlace(t.axis, 0.5).subInPlace(placed.face))
        repeat(180) { world.step(dt) }
        assertTrue("no capture", !world.capturing(a.id.raw, b.id.raw))
        assertEquals(2, world.vessels.size)
    }

    @Test
    fun `a small ring won't take a large one`() {
        val small = DockingPort(size = 0)
        val large = DockingPort(size = 1)
        val ball = DockingPort(kind = com.rm.apogee.core.part.DockKind.HITCH_BALL)
        val coupling = DockingPort(kind = com.rm.apogee.core.part.DockKind.HITCH_COUPLING)
        assertTrue(!small.matesWith(large))
        assertTrue(!ball.matesWith(large))
        assertTrue(ball.matesWith(coupling) && coupling.matesWith(ball))
        assertTrue(!ball.matesWith(ball))
    }

    @Test
    fun `a docked station survives a save and a reload as the same craft`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        assertTrue(approach(world, b, port, a, port, seconds = 120.0))
        val saved = world.save()
        val again = World.default(catalog)
        assertTrue(again.restore(saved).isEmpty())
        val station = again.vessels.single()
        assertEquals(world.vessels.single().design, station.design)
        val ring = station.defs.indices.first { station.defs[it].id == "dock-port" }
        assertTrue("and it still undocks", again.undock(station, ring))
        assertNull(null)
    }

    @Test
    fun `a little off line and a few degrees off, the magnets square it up`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val t = ref(a, port)
        val up = a.body.orientation.rotate(Vec3.unitY(), Vec3())
        b.body.orientation.setTo(Quat.fromAxisAngle(up, Math.toRadians(8.0), Quat()) * b.body.orientation).normalizeInPlace()
        b.body.position.addScaledInPlace(up, 0.3)
        world.apply(Command.SetSas(b.id.raw, true))
        assertTrue("docks all the same", approach(world, b, port, a, port, seconds = 120.0))
        assertEquals(1, world.vessels.size)
    }

    // --- on the ground ------------------------------------------------------

    /** Base building: two tugs landed on Luna, walked together on their thrusters, ring to ring. */
    @Test
    fun `two tugs landed on Luna are walked together and dock`() {
        val world = World.default(catalog)
        val mare = World.launchSites.first { it.id == "luna-mare" }
        val tug = StockCraft.portTug(catalog)
        val a = world.spawnOnSurface(tug, mare)
        world.stage(a)
        world.apply(Command.SetGear(a.id.raw, true))
        repeat(600) { world.step(dt) }
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val b = placeFacing(world, tug, port, a, port, gap = 3.0, turnAbout = Vec3.unitY())
        world.stage(b)
        world.apply(Command.SetGear(b.id.raw, true))
        repeat(600) { world.step(dt) }
        moverParts = b.design.parts.size; targetParts = a.design.parts.size
        val gap0 = ref(a, port).face.distanceTo(ref(b, port).face)
        println("landed, ${"%.2f".format(gap0)} m apart")
        assertTrue("walked in and docked", approach(world, b, port, a, port, seconds = 180.0, speed = 0.25))
        assertEquals(1, world.vessels.size)
        val base = world.vessels.single()
        repeat(600) { world.step(dt) }
        val up = base.body.position.normalized()
        val tilt = Math.toDegrees(kotlin.math.acos((base.body.orientation.rotate(Vec3.unitY(), Vec3()) dot up).coerceIn(-1.0, 1.0)))
        assertTrue("and stands, upright ($tilt deg)", tilt < 10.0)
    }

    /** A rover backs up to a cart, the hitch couples, and the cart is towed, swinging behind. */
    @Test
    fun `a buggy hitches up a cart and tows it`() {
        val world = World.default(catalog)
        val cape = World.launchSites.first { it.id == "cape" }
        val buggy = world.spawnOnSurface(StockCraft.towBuggy(catalog), cape)
        repeat(300) { world.step(dt) }
        val ball = buggy.defs.indices.first { buggy.defs[it].id == "hitch-ball" }
        val cartDesign = StockCraft.cart(catalog)
        val coupling = cartDesign.parts.indices.first { cartDesign.parts[it].partId == "hitch-coupling" }
        val cart = placeFacing(world, cartDesign, coupling, buggy, ball, gap = 0.35, turnAbout = buggy.design.orientation.up)
        repeat(300) {
            if (it % 30 == 0) {
                val a = ref(buggy, ball); val b = ref(cart, coupling)
                println("hitch t ${it * dt}: gap ${"%.3f".format(a.face.distanceTo(b.face))} facing ${"%.3f".format(-(a.axis dot b.axis))} capturing ${world.capturing(buggy.id.raw, cart.id.raw)} links ${world.hitches.size}")
            }
            world.step(dt)
        }
        assertEquals("coupled", 1, world.hitches.size)
        assertEquals("still two craft", 2, world.vessels.size)

        val terra = world.attractorFor(cart)
        fun ground(v: Vessel) = terra.toBodyFixed(v.body.position, terra.rotationAt(world.time), Vec3())
        val start = ground(cart)
        world.apply(Command.SetThrottle(buggy.id.raw, 0.6))
        repeat(60 * 25) { world.step(dt) }
        val moved = ground(cart).distanceTo(start)
        val joint = ref(buggy, ball).face.distanceTo(ref(cart, coupling).face)
        val up = cart.body.position.normalized()
        val deck = cart.body.orientation.rotate(cart.design.orientation.up, Vec3())
        val tilt = Math.toDegrees(kotlin.math.acos((deck dot up).coerceIn(-1.0, 1.0)))
        println("towed ${"%.1f".format(moved)} m, hitch gap ${"%.3f".format(joint)} m, cart tilt ${"%.1f".format(tilt)} deg")
        assertTrue("the cart came along ($moved m)", moved > 30.0)
        assertTrue("held at the hitch ($joint m)", joint < 0.15)
        assertTrue("on its wheels ($tilt deg)", tilt < 25.0)

        // Hard one way, then the other. The cart swings about the ball, coupled and upright.
        var worstTilt = 0.0
        for (yaw in listOf(1.0, -1.0)) {
            world.apply(Command.SetAttitude(buggy.id.raw, 0.0, yaw, 0.0))
            repeat(60 * 8) {
                world.step(dt)
                val d = cart.body.orientation.rotate(cart.design.orientation.up, Vec3())
                worstTilt = maxOf(worstTilt, Math.toDegrees(kotlin.math.acos((d dot cart.body.position.normalized()).coerceIn(-1.0, 1.0))))
            }
        }
        world.apply(Command.SetAttitude(buggy.id.raw, 0.0, 0.0, 0.0))
        println("through the bends: coupled ${world.hitches.size}, worst cart tilt ${"%.1f".format(worstTilt)} deg")
        assertEquals("still coupled after the bends", 1, world.hitches.size)
        assertTrue("never rolled ($worstTilt deg)", worstTilt < 35.0)

        // Let go.
        world.apply(Command.SetThrottle(buggy.id.raw, 0.0))
        repeat(120) { world.step(dt) }
        assertTrue(world.undock(buggy, ball))
        assertEquals(0, world.hitches.size)
    }

    // --- on the water ---------------------------------------------------------

    /** Two skiffs drift alongside, clamp and raft up, and the raft goes as one under power. */
    @Test
    fun `two skiffs come alongside and raft up`() {
        val world = World.default(catalog)
        val skiff = StockCraft.skiff(catalog)
        val harbour = World.launchSites.first { it.id == "harbour" }
        val a = world.spawnOnSurface(skiff, harbour)
        repeat(600) { world.step(dt) }
        val right = a.defs.indices.first { a.defs[it].id == "mooring-clamp" && a.design.parts[it].position.x > 0 }
        val left = skiff.parts.indices.first { skiff.parts[it].partId == "mooring-clamp" && skiff.parts[it].position.x < 0 }
        val b = placeFacing(world, skiff, left, a, right, gap = 1.2)
        // B turned side-on the same way as A, with its left clamp to A's right.
        val parts = a.design.parts.size + b.design.parts.size
        repeat(60 * 30) {
            if (it % 60 == 0) {
                val deckA = a.body.orientation.rotate(a.design.orientation.up, Vec3())
                val tiltA = Math.toDegrees(kotlin.math.acos((deckA dot a.body.position.normalized()).coerceIn(-1.0, 1.0)))
                val deckB = b.body.orientation.rotate(b.design.orientation.up, Vec3())
                val tiltB = Math.toDegrees(kotlin.math.acos((deckB dot b.body.position.normalized()).coerceIn(-1.0, 1.0)))
                println("raft t ${it * dt}: craft ${world.vessels.size} tiltA ${"%.1f".format(tiltA)} tiltB ${"%.1f".format(tiltB)} capturing ${world.capturing(a.id.raw, b.id.raw)}")
            }
            world.step(dt)
        }
        println("after 30 s: ${world.vessels.size} craft")
        if (world.vessels.size == 2) {
            moverParts = b.design.parts.size; targetParts = a.design.parts.size
            assertTrue("rafted", approach(world, b, left, a, right, seconds = 90.0, speed = 0.3))
        }
        assertEquals("one raft", 1, world.vessels.size)
        val raft = world.vessels.single()
        assertEquals(parts, raft.design.parts.size)
        repeat(300) { world.step(dt) }
        val deck = raft.body.orientation.rotate(raft.design.orientation.up, Vec3())
        val tilt = Math.toDegrees(kotlin.math.acos((deck dot raft.body.position.normalized()).coerceIn(-1.0, 1.0)))
        assertTrue("floating level ($tilt deg)", tilt < 12.0)
        val earth = world.attractorFor(raft)
        fun ground() = earth.toBodyFixed(raft.body.position, earth.rotationAt(world.time), Vec3())
        val start = ground()
        repeat(2) { world.apply(Command.Stage(raft.id.raw)) }
        world.apply(Command.SetThrottle(raft.id.raw, 0.8))
        repeat(60 * 20) { world.step(dt) }
        val went = ground().distanceTo(start)
        println("raft went ${"%.1f".format(went)} m")
        assertTrue("under way as one ($went m)", went > 10.0 && world.vessels.size == 1)
        val clamp = raft.defs.indices.first { raft.defs[it].id == "mooring-clamp" && raft.design.parts[it].dockedTo >= 0 }
        assertTrue("unclamps", world.undock(raft, clamp))
        assertEquals(2, world.vessels.size)
    }

    /** Rings resting face on face a little askew. The guides draw them in. */
    @Test
    fun `rings resting together twenty degrees askew still draw in and latch`() {
        val (world, a, b) = dockInOrbit()
        val port = a.defs.indices.first { a.defs[it].id == "dock-port" }
        val t = ref(a, port)
        val side = Vec3().setTo(t.axis).crossInPlace(a.body.orientation.rotate(Vec3.unitY(), Vec3())).normalizeInPlace()
        b.body.orientation.setTo(Quat.fromAxisAngle(side, Math.toRadians(20.0), Quat()) * b.body.orientation).normalizeInPlace()
        b.body.position.addInPlace(Vec3().setTo(t.face).addScaledInPlace(t.axis, 0.08).subInPlace(ref(b, port).face))
        repeat(60 * 15) { if (world.vessels.size > 1) world.step(dt) }
        assertEquals("latched", 1, world.vessels.size)
    }

    /** Back the buggy slowly onto the cart until the coupling takes. */
    @Test
    fun `a buggy reverses onto a cart and couples`() {
        val world = World.default(catalog)
        val buggy = world.spawnOnSurface(StockCraft.towBuggy(catalog), World.launchSites.first { it.id == "cape" })
        repeat(300) { world.step(dt) }
        val ball = buggy.defs.indices.first { buggy.defs[it].id == "hitch-ball" }
        val cartDesign = StockCraft.cart(catalog)
        val coupling = cartDesign.parts.indices.first { cartDesign.parts[it].partId == "hitch-coupling" }
        placeFacing(world, cartDesign, coupling, buggy, ball, gap = 3.0, turnAbout = buggy.design.orientation.up)
        repeat(300) { world.step(dt) }
        world.apply(Command.SetReverse(buggy.id.raw, true))
        world.apply(Command.SetThrottle(buggy.id.raw, 0.3))
        var t = 0.0
        while (world.hitches.isEmpty() && t < 40.0) { world.step(dt); t += dt }
        world.apply(Command.SetThrottle(buggy.id.raw, 0.0))
        println("coupled after ${"%.1f".format(t)} s reversing")
        assertEquals("coupled", 1, world.hitches.size)
        assertEquals("both still whole", 2, world.vessels.size)
    }
}
