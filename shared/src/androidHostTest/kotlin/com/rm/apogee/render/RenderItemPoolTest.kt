package com.rm.apogee.render

import com.rm.apogee.core.math.Quat
import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.part.MeshSpec
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RenderItemPoolTest {
    private val shape = MeshSpec.Sphere(1.0)

    private fun build(pool: RenderItemPool, bus: FrameBus, count: Int, mark: Long): List<RenderItem> {
        pool.begin()
        val items = pool.list()
        repeat(count) { i -> items += pool.take().also { it.position.setTo(mark.toDouble(), i.toDouble(), 0.0); it.refill(shape, FloatArray(4), key = mark) } }
        pool.published(bus.publish(RenderFrame(mark, mark, Vec3(), Quat(), 1.0, items)))
        return items
    }

    @Test fun itemsAreOnlyFilledAgainOnceTheGlThreadIsPastTheirFrame() {
        val bus = FrameBus()
        val pool = RenderItemPool(bus)
        val first = build(pool, bus, 3, 1)
        val second = build(pool, bus, 3, 2)
        // Nothing drawn yet: all new.
        assertTrue(second.none { it in first })
        bus.drawn(bus.latest()!!)
        // Drawn (1, 2), so 1 is still held.
        val third = build(pool, bus, 3, 3)
        assertTrue(third.none { it in first || it in second })
        bus.drawn(bus.latest()!!)
        // Drawn (2, 3): 1 is free.
        val fourth = build(pool, bus, 3, 4)
        assertEquals(first.toSet(), fourth.toSet())
        assertTrue(second.all { it.key == 2L } && third.all { it.key == 3L })
    }

    @Test fun aBuildThatNeverWentOutIsLetGo() {
        val bus = FrameBus()
        val pool = RenderItemPool(bus)
        build(pool, bus, 2, 1)
        build(pool, bus, 2, 2)
        build(pool, bus, 2, 3)
        bus.drawn(bus.latest()!!)
        pool.begin()
        val unpublished = List(2) { pool.take() }
        // The next build starts afresh rather than refill what that one took.
        val next = build(pool, bus, 2, 4)
        assertTrue(next.none { it in unpublished })
    }

    @Test fun withoutDrawingItWaitsOnOnlyAFewBuilds() {
        val bus = FrameBus()
        val pool = RenderItemPool(bus)
        val kept = (1L..50L).map { build(pool, bus, 2, it) }
        // Every build's items are its own and unchanged.
        for ((i, items) in kept.withIndex()) assertTrue(items.all { it.key == i + 1L })
        assertEquals(100, kept.flatten().toSet().size)
        assertTrue(kept.all { it.size == 2 })
    }

    @Test fun numbersKeepRisingAcrossAClear() {
        val bus = FrameBus()
        val frame = RenderFrame(0, 0, Vec3(), Quat(), 1.0, emptyList())
        assertEquals(1L, bus.publish(frame))
        assertEquals(2L, bus.publish(frame))
        bus.clear()
        assertEquals(3L, bus.publish(frame))
    }

    @Test fun aBuildMuchBiggerThanLatelyIsLetGo() {
        val bus = FrameBus()
        val pool = RenderItemPool(bus)
        repeat(40) { build(pool, bus, 10, it + 1L); bus.drawn(bus.latest()!!) }
        val big = build(pool, bus, 1_000, 41)
        bus.drawn(bus.latest()!!)
        val later = (42L..50L).flatMap { mark -> build(pool, bus, 10, mark).also { bus.drawn(bus.latest()!!) } }
        assertTrue(later.none { it in big })
    }

    /** Publishers racing each other and a clear: what the drawer sees only ever goes forward. */
    @Test fun numbersOnlyGoForwardWhilePublishesRaceAClear() {
        val bus = FrameBus()
        val frame = RenderFrame(0, 0, Vec3(), Quat(), 1.0, emptyList())
        val failure = AtomicReference<String?>(null)
        val running = AtomicBoolean(true)
        val threads = List(2) { Thread { while (running.get()) bus.publish(frame) } } +
            Thread { while (running.get()) { bus.clear(); Thread.yield() } } +
            Thread {
                var highest = 0L
                while (running.get()) {
                    val pair = bus.latest() ?: continue
                    if (pair.latestSequence < highest) failure.compareAndSet(null, "went back from $highest to ${pair.latestSequence}")
                    if (pair.previous != null && pair.previousSequence >= pair.latestSequence) failure.compareAndSet(null, "pair out of order")
                    highest = maxOf(highest, pair.latestSequence)
                }
            }
        threads.forEach { it.start() }
        Thread.sleep(300)
        running.set(false)
        threads.forEach { it.join() }
        assertNull(failure.get())
    }

    /** A builder and a drawer on two threads: what's drawn never changes under the drawer. */
    @Test fun aFrameBeingDrawnIsNeverFilledAgain() {
        val bus = FrameBus()
        val pool = RenderItemPool(bus)
        val failure = AtomicReference<String?>(null)
        val running = AtomicBoolean(true)
        val builder = Thread {
            var mark = 1L
            while (running.get()) build(pool, bus, 200, mark++)
        }
        var drawn = 0
        val drawer = Thread {
            while (running.get()) try {
                val pair = bus.latest() ?: continue
                repeat(3) {
                    for (frame in listOfNotNull(pair.previous, pair.latest)) for ((i, item) in frame.items.withIndex()) {
                        if (frame.items.size != 200) failure.compareAndSet(null, "frame ${frame.simTick} list changed")
                        if (item.key != frame.simTick || item.position.x != frame.simTick.toDouble() || item.position.y != i.toDouble()) {
                            failure.compareAndSet(null, "frame ${frame.simTick} item $i changed to ${item.key}")
                        }
                    }
                }
                bus.drawn(pair)
                drawn++
            } catch (e: Exception) {
                failure.compareAndSet(null, e.toString())
            }
        }
        builder.start(); drawer.start()
        Thread.sleep(500)
        running.set(false)
        builder.join(); drawer.join()
        assertNull(failure.get())
        assertTrue(drawn > 10)
    }
}
