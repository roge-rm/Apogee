package com.rm.apogee.game

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.apogee.core.world.Protocol
import com.rm.apogee.net.LanDiscovery
import com.rm.apogee.net.ServerBeacon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


/**
 * Watches the network for games and keeps a live list.
 *
 * It removes duplicates by address and port, and drops hosts that stop announcing. A game that has
 * ended should disappear from the list, not sit there waiting to fail when you tap it.
 *
 * It holds a [WifiManager.MulticastLock] while running. Android drops broadcast and multicast
 * frames that aren't addressed to the device to save power, so without the lock the listener is
 * simply deaf, with no error to explain it.
 */
class AndroidServerBrowser(
    private val context: Context,
    private val localCatalogHash: String,
) : ServerBrowser {
    override var servers: List<DiscoveredServer> by mutableStateOf(emptyList())
        private set

    override var scanning: Boolean by mutableStateOf(false)
        private set

    private val found = LinkedHashMap<String, DiscoveredServer>()
    private var listenJob: Job? = null
    private var expiryJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun start(scope: CoroutineScope) {
        if (listenJob != null) return
        scanning = true
        acquireMulticastLock()

        listenJob = LanDiscovery.listen(scope) { beacon ->
            val entry = DiscoveredServer(beacon, System.currentTimeMillis())
            synchronized(found) {
                found[entry.key] = entry
                servers = found.values.sortedBy { it.beacon.serverName }
            }
        }

        expiryJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(EXPIRY_CHECK_MILLIS)
                val cutoff = System.currentTimeMillis() - STALE_AFTER_MILLIS
                synchronized(found) {
                    val before = found.size
                    found.entries.removeAll { it.value.lastSeenMillis < cutoff }
                    if (found.size != before) {
                        servers = found.values.sortedBy { it.beacon.serverName }
                    }
                }
            }
        }
    }

    override fun stop() {
        scanning = false
        listenJob?.cancel(); listenJob = null
        expiryJob?.cancel(); expiryJob = null
        releaseMulticastLock()
        synchronized(found) { found.clear() }
        servers = emptyList()
    }

    override fun reasonFor(server: DiscoveredServer): String? =
        server.incompatibilityReason(localCatalogHash)

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifi?.createMulticastLock("apogee-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    private companion object {
        const val EXPIRY_CHECK_MILLIS = 1_000L

        /**
         * Hosts announce once a second, so three missed rounds is a host that has gone, not a
         * dropped packet.
         */
        const val STALE_AFTER_MILLIS = 4_000L
    }
}
