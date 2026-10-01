package com.rm.apogee.settings

import android.content.Context
import android.content.SharedPreferences

/** The game's settings, kept in its SharedPreferences. */
fun GameSettings(context: Context): GameSettings =
    GameSettings(AndroidPreferences(context.getSharedPreferences("apogee.settings", Context.MODE_PRIVATE)))

/** SharedPreferences, since the values are read synchronously while building the first frame. */
private class AndroidPreferences(private val prefs: SharedPreferences) : Preferences {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
}
