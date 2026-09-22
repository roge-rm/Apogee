package com.rm.apogee.net

/**
 * A host and port typed by a player.
 *
 * Discovery is the normal way to find a game, but it only works where UDP
 * broadcast does. A VPN, a guest network that blocks client isolation, a
 * subnet boundary or a server reached over the internet all leave the list
 * empty while the game itself is perfectly reachable, so typing the address
 * has to be a first-class path rather than a fallback nobody built.
 */
data class ServerAddress(val host: String, val port: Int) {

    /** What to show while connecting: the port only when it is not the usual one. */
    fun label(defaultPort: Int): String =
        if (port == defaultPort) host else "$host:$port"

    companion object {

        /**
         * Parses `host`, `host:port`, `[v6]` or `[v6]:port`.
         *
         * Returns null rather than throwing - this runs on every keystroke to
         * decide whether the connect button is live, so a half-typed address
         * is an ordinary state, not an error.
         */
        fun parse(text: String, defaultPort: Int): ServerAddress? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            // A bracketed host is the only way to tell an IPv6 address from a
            // host:port pair, since both are full of colons.
            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close < 2) return null
                val host = trimmed.substring(1, close)
                val rest = trimmed.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> defaultPort
                    rest.startsWith(":") -> portOf(rest.substring(1)) ?: return null
                    else -> return null
                }
                return if (validHost(host)) ServerAddress(host, port) else null
            }

            // More than one colon and no brackets means a bare IPv6 address.
            if (trimmed.count { it == ':' } > 1) {
                return if (validHost(trimmed)) ServerAddress(trimmed, defaultPort) else null
            }

            val colon = trimmed.indexOf(':')
            if (colon < 0) {
                return if (validHost(trimmed)) ServerAddress(trimmed, defaultPort) else null
            }
            val host = trimmed.substring(0, colon)
            val port = portOf(trimmed.substring(colon + 1)) ?: return null
            return if (validHost(host)) ServerAddress(host, port) else null
        }

        private fun portOf(text: String): Int? =
            text.toIntOrNull()?.takeIf { it in 1..65_535 }

        /**
         * Deliberately permissive: this rejects what cannot possibly be a host,
         * and leaves anything else for the connection attempt to answer. A
         * parser that tries to be the authority on valid hostnames ends up
         * refusing addresses that would have worked.
         */
        private fun validHost(host: String): Boolean =
            host.isNotEmpty() && host.none { it.isWhitespace() || it == '/' || it == '@' }
    }
}
