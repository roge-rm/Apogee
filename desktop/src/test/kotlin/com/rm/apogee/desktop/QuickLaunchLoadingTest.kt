package com.rm.apogee.desktop

import com.rm.apogee.ApogeeApp
import com.rm.apogee.AppHost
import com.rm.apogee.WorldGestures
import com.rm.apogee.core.Folder
import com.rm.apogee.game.DiscoveredServer
import com.rm.apogee.game.ServerBrowser
import com.rm.apogee.render.GlRenderer
import com.rm.apogee.render.PictureStore
import com.rm.apogee.render.QualityTier
import com.rm.apogee.settings.GameSettings
import com.rm.apogee.settings.Preferences
import com.rm.apogee.ui.screens.AppScreen
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QuickLaunchLoadingTest {
    @Test fun menuRespondsWhileUniverseLoadsAndLaunchWaitsForItsSites() = runBlocking {
        val host = Host(this)
        val app = ApogeeApp(host)
        val mainThread = Thread.currentThread()
        app.navigateTo(AppScreen.QUICK_LAUNCH)
        val load = field(app, "quickLoad") as Job
        try {
            assertEquals(AppScreen.QUICK_LAUNCH, field(app, "appScreen"))
            withContext(Dispatchers.IO) { assertTrue(host.world.reading.await(10, TimeUnit.SECONDS)) }
            assertNotEquals(mainThread, host.world.reader)
            assertFalse(load.isCompleted)
            assertNull(field(app, "soloWorld"))
            assertTrue((field(app, "quickEntries") as List<*>).isEmpty())
            host.world.release.countDown()
            withTimeout(10_000) { load.join() }
            assertNotNull(field(app, "soloWorld"))
            assertTrue((field(app, "quickEntries") as List<*>).isNotEmpty())
            assertTrue((field(app, "quickBases") as List<*>).isNotEmpty())
            assertTrue(host.messages.isEmpty())
        } finally {
            host.world.release.countDown()
            load.cancelAndJoin()
        }
    }

    @Test fun leavingDuringLoadDiscardsItAndReopeningLoadsTheSelectedUniverse() = runBlocking {
        val host = Host(this)
        val app = ApogeeApp(host)
        app.navigateTo(AppScreen.QUICK_LAUNCH)
        val oldLoad = field(app, "quickLoad") as Job
        try {
            withContext(Dispatchers.IO) { assertTrue(host.world.reading.await(10, TimeUnit.SECONDS)) }
            app.navigateTo(AppScreen.WORLDS)
            assertTrue(oldLoad.isCancelled)
            host.settings.sandboxWorld = "solo-2"
            host.world.write("worlds.json", """{"names":{"solo-2":"Other universe"}}""")
            host.world.release.countDown()
            withTimeout(10_000) { oldLoad.join() }
            assertNull(field(app, "soloWorld"))
            assertTrue((field(app, "quickEntries") as List<*>).isEmpty())
            app.navigateTo(AppScreen.QUICK_LAUNCH)
            withTimeout(10_000) { (field(app, "quickLoad") as Job).join() }
            assertEquals("solo-2.json.bak", host.world.lastSaveRead)
            assertNotNull(field(app, "soloWorld"))
            assertTrue((field(app, "quickEntries") as List<*>).isNotEmpty())
            assertTrue(host.messages.isEmpty())
        } finally {
            host.world.release.countDown()
            oldLoad.cancelAndJoin()
            (field(app, "quickLoad") as? Job)?.cancelAndJoin()
        }
    }

    @Test fun playMenuPreparesQuickLaunchAndOpeningItReusesTheReadyList() = runBlocking {
        val host = Host(this)
        val app = ApogeeApp(host)
        app.navigateTo(AppScreen.PLAY)
        val preparation = field(app, "quickLoad") as Job
        try {
            withContext(Dispatchers.IO) { assertTrue(host.world.reading.await(10, TimeUnit.SECONDS)) }
            // Tapping Quick Launch during preparation shares the same work.
            app.navigateTo(AppScreen.QUICK_LAUNCH)
            assertSame(preparation, field(app, "quickLoad"))
            host.world.release.countDown()
            withTimeout(10_000) { preparation.join() }
            val entries = field(app, "quickEntries")
            val world = field(app, "soloWorld")
            assertTrue((entries as List<*>).isNotEmpty())
            app.navigateTo(AppScreen.PLAY)
            app.navigateTo(AppScreen.QUICK_LAUNCH)
            // Ready synchronously on navigation, with no new coroutine or loading state.
            assertSame(preparation, field(app, "quickLoad"))
            assertSame(entries, field(app, "quickEntries"))
            assertSame(world, field(app, "soloWorld"))
            assertTrue(host.messages.isEmpty())
        } finally {
            host.world.release.countDown()
            preparation.cancelAndJoin()
        }
    }

    private fun field(app: ApogeeApp, name: String): Any? {
        val field = ApogeeApp::class.java.declaredFields.first { it.name == name || it.name == name + "\$delegate" }
        field.isAccessible = true
        val value = field.get(app)
        return if (value is androidx.compose.runtime.State<*>) value.value else value
    }

    private class Files(private val blockSave: Boolean = false) : Folder {
        private val files = ConcurrentHashMap<String, String>()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var reader: Thread? = null
        @Volatile var lastSaveRead: String? = null
        override fun read(name: String): String? {
            if (blockSave && name.endsWith(".json.bak")) {
                reader = Thread.currentThread()
                lastSaveRead = name
                reading.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            return files[name]
        }
        override fun names() = files.keys.toList()
        override fun write(name: String, text: String) { files[name] = text }
        override fun delete(name: String) = files.remove(name) != null
        override fun exists(name: String) = files.containsKey(name)
        override fun lastModified(name: String) = 0L
        override fun size(name: String) = files[name]?.length?.toLong() ?: 0L
        override fun path(name: String) = name
    }

    private class Host(override val scope: CoroutineScope) : AppHost {
        val world = Files(true)
        val messages = ArrayList<String>()
        private val folders = HashMap<String, Folder>()
        override val settings = GameSettings(object : Preferences {
            private val values = HashMap<String, Any>()
            override fun getString(key: String, default: String?) = values[key] as? String ?: default
            override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
            override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
            override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun putBoolean(key: String, value: Boolean) { values[key] = value }
            override fun putFloat(key: String, value: Float) { values[key] = value }
            override fun putInt(key: String, value: Int) { values[key] = value }
        })
        override fun folder(name: String) = if (name == "world") world else folders.getOrPut(name) { Files() }
        override val pictures = object : PictureStore {
            override fun load(folder: String, key: String): androidx.compose.ui.graphics.ImageBitmap? = null
            override fun save(folder: String, key: String, argb: IntArray, size: Int) {}
        }
        override val serverBrowser = object : ServerBrowser {
            override val servers = emptyList<DiscoveredServer>()
            override val scanning = false
            override fun start(scope: CoroutineScope) {}
            override fun stop() {}
            override fun reasonFor(server: DiscoveredServer): String? = null
        }
        override fun showSurface(renderer: GlRenderer, gestures: WorldGestures) {}
        override fun hideSurface() {}
        override fun detectTier() = QualityTier.LOW
        override fun perfHints(): com.rm.apogee.platform.PerfHints? = null
        override fun fullscreen(on: Boolean) {}
        override fun shareCraft(name: String, text: String) {}
        override fun pickSharedCraft() {}
        override fun toast(message: String) { messages += message }
        override fun debugSwitch(name: String) = false
        override fun timeNow() = ""
        override fun runOnMain(block: () -> Unit) = block()
    }
}
