package com.rm.apogee.settings

import java.io.File
import java.util.Properties
import java.util.concurrent.Executors

/** The game's settings, kept in settings.properties in [folder]. */
fun GameSettings(folder: File): GameSettings = GameSettings(FilePreferences(File(folder, "settings.properties")))

/**
 * Settings held in memory and read from there, so reads are quick on the first frame. Each change
 * is written out on one background thread, through a temporary file so a crash never leaves half.
 */
private class FilePreferences(private val file: File) : Preferences {
    private val held = Properties().apply { if (file.exists()) runCatching { file.inputStream().use { load(it) } } }
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "settings").apply { isDaemon = true } }

    override fun getString(key: String, default: String?): String? = held.getProperty(key) ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = held.getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun getFloat(key: String, default: Float): Float = held.getProperty(key)?.toFloatOrNull() ?: default
    override fun getInt(key: String, default: Int): Int = held.getProperty(key)?.toIntOrNull() ?: default

    override fun putString(key: String, value: String?) {
        if (value == null) held.remove(key) else held.setProperty(key, value)
        save()
    }

    override fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
    override fun putFloat(key: String, value: Float) = putString(key, value.toString())
    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    private fun save() {
        val copy = Properties().apply { putAll(held) }
        writer.execute {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.outputStream().use { copy.store(it, null) }
                tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}
