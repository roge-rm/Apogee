@file:OptIn(ExperimentalWasmJsInterop::class)

package com.rm.apogee.web

import com.rm.apogee.core.Folder
import com.rm.apogee.settings.Preferences
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.localStorage

/*
 * Everything is kept in the browser's local storage: settings, craft and worlds, a key per file
 * with its write time beside it.
 */

private const val FILES = "apogee:"
private const val TIMES = "apogee-time:"

private fun dateNow(): Double = js("Date.now()")

/** A folder of files in local storage, under [name]. */
class StorageFolder(private val name: String) : Folder {
    private fun key(file: String) = "$FILES$name/$file"

    override fun names(): List<String> {
        val prefix = "$FILES$name/"
        return (0 until localStorage.length).mapNotNull { localStorage.key(it) }
            .filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
            .map { it.removePrefix(prefix) }
    }

    override fun read(name: String): String? = localStorage.getItem(key(name))

    /** One call, so it's all written or none of it: the old text stays if it won't fit. */
    override fun write(name: String, text: String) {
        localStorage.setItem(key(name), text)
        localStorage.setItem(TIMES + key(name).removePrefix(FILES), dateNow().toLong().toString())
    }

    override fun delete(name: String): Boolean {
        val had = localStorage.getItem(key(name)) != null
        localStorage.removeItem(key(name))
        localStorage.removeItem(TIMES + key(name).removePrefix(FILES))
        return had
    }

    override fun exists(name: String): Boolean = localStorage.getItem(key(name)) != null

    override fun lastModified(name: String): Long =
        localStorage.getItem(TIMES + key(name).removePrefix(FILES))?.toLongOrNull() ?: 0L

    override fun size(name: String): Long = (read(name)?.length ?: 0).toLong()

    override fun path(name: String): String = "browser storage: ${this.name}/$name"
}

/** Settings in local storage, one key each. */
class StoragePreferences : Preferences {
    private fun key(k: String) = "apogee-setting:$k"

    override fun getString(key: String, default: String?): String? = localStorage.getItem(key(key)) ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = localStorage.getItem(key(key))?.toBooleanStrictOrNull() ?: default
    override fun getFloat(key: String, default: Float): Float = localStorage.getItem(key(key))?.toFloatOrNull() ?: default
    override fun getInt(key: String, default: Int): Int = localStorage.getItem(key(key))?.toIntOrNull() ?: default

    override fun putString(key: String, value: String?) {
        if (value == null) localStorage.removeItem(key(key)) else localStorage.setItem(key(key), value)
    }

    override fun putBoolean(key: String, value: Boolean) = localStorage.setItem(key(key), value.toString())
    override fun putFloat(key: String, value: Float) = localStorage.setItem(key(key), value.toString())
    override fun putInt(key: String, value: Int) = localStorage.setItem(key(key), value.toString())
}
