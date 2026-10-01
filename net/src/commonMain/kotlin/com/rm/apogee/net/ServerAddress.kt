package com.rm.apogee.net

/**
 * A host and port typed in by a player. Needed wherever UDP broadcast can't reach: a VPN, a guest
 * network, another subnet, or the internet.
 */
data class ServerAddress(val host: String, val port: Int) {

    /** What to show while connecting. The port only shows when it isn't the usual one. */
    fun label(defaultPort: Int): String =
        if (port == defaultPort) host else "$host:$port"

    companion object {

        /**
         * Parses `host`, `host:port`, `[v6]` or `[v6]:port`. Returns null on bad input; it runs on
         * every keystroke to enable the connect button.
         */
        fun parse(text: String, defaultPort: Int): ServerAddress? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            // Brackets tell an IPv6 address from host:port.
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

        /** Permissive on purpose. Rejects only what can't be a host; the connection decides the rest. */
        private fun validHost(host: String): Boolean =
            host.isNotEmpty() && host.none { it.isWhitespace() || it == '/' || it == '@' }
    }
}
