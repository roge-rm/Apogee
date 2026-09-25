package com.rm.apogee.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.apogee.render.QualityTier

/**
 * Per-device, per-player preferences.
 *
 * Note the split this establishes: anything that is a *rule of the game world*
 * (difficulty, part availability, whether re-entry heating is on) belongs to
 * the server and arrives over the wire. Only things about this device and this
 * person live here. Getting that boundary wrong is how settings screens end up
 * silently disagreeing with the server a player has joined.
 *
 * SharedPreferences rather than DataStore, deliberately: these values are read
 * while building the first frame, and DataStore's reads are asynchronous.
 * Trading a synchronous read on a tiny preference file for a suspending one
 * buys nothing here and complicates every call site.
 *
 * Each property is Compose state with a write-through setter, so there is no
 * save button and no way for the UI and the stored value to disagree.
 */
class GameSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("apogee.settings", Context.MODE_PRIVATE)

    /** What other players see. Cosmetic - see [clientId]. */
    var playerName: String by stringPref(KEY_PLAYER_NAME, "Pilot")

    /**
     * This install's identity, generated once and never shown as something to
     * edit.
     *
     * Ownership of craft and bases hangs off this. It deliberately is not the
     * player's name: names are neither unique nor stable, and two devices that
     * never set one both arrive as "Pilot" and end up sharing a craft - which
     * is precisely what happened the first time two clients met on a server.
     *
     * Generated lazily on first read rather than in the constructor, so a
     * fresh install does not write to disk before anyone has played.
     */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_CLIENT_ID, it).apply() }

    /**
     * The last address typed into the join screen.
     *
     * Remembered because the case that needs it is the case where discovery
     * cannot help - a VPN, a different subnet, a server on the internet - and
     * that is not a one-off. Retyping an address every session is the kind of
     * friction that makes people stop joining.
     */
    var lastServerAddress: String by stringPref(KEY_LAST_SERVER, "")

    /**
     * Opacity of the flight control overlay. Floored well above zero - a
     * fully transparent HUD is indistinguishable from a broken one.
     */
    var controlOpacity: Float by floatPref(KEY_CONTROL_OPACITY, 1.0f, 0.3f..1.0f)

    /** Mirrors the flight controls for left-handed play. */
    var leftHandMode: Boolean by booleanPref(KEY_LEFT_HAND, false)

    /** Which craft pull back to climb. See [PitchStyle]. */
    var pitchStyle: PitchStyle by enumPref(KEY_PITCH_STYLE, PitchStyle.AIRCRAFT)

    /** How lively the weather is in the worlds this device hosts. */
    var weatherIntensity: com.rm.apogee.core.weather.WeatherIntensity by enumPref(
        KEY_WEATHER, com.rm.apogee.core.weather.WeatherIntensity.NORMAL,
    )

    /** When in the day a solo flight launches: now, or the next dawn, noon, dusk or midnight. */
    var launchTime: com.rm.apogee.core.world.LaunchTime by enumPref(
        KEY_LAUNCH_TIME, com.rm.apogee.core.world.LaunchTime.NOW,
    )

    /** How cloudy the worlds this device hosts are. */
    var cloudCover: com.rm.apogee.core.weather.CloudCover by enumPref(
        KEY_CLOUDS, com.rm.apogee.core.weather.CloudCover.NORMAL,
    )

    var uiSoundEnabled: Boolean by booleanPref(KEY_UI_SOUND, true)

    /** Craft's own sounds: engines, wheels, the air rushing past, the hull. Crashes stay. */
    var vehicleSoundEnabled: Boolean by booleanPref(KEY_VEHICLE_SOUND, true)

    /** The world's: wind, rain, thunder, surf, fires. */
    var ambientSoundEnabled: Boolean by booleanPref(KEY_AMBIENT_SOUND, true)

    /** Everything, then each part of the mix: craft and crashes, the world around, the interface. */
    var masterVolume: Float by floatPref(KEY_MASTER_VOLUME, 0.8f, 0f..1f)
    var effectsVolume: Float by floatPref(KEY_EFFECTS_VOLUME, 1.0f, 0f..1f)
    var ambienceVolume: Float by floatPref(KEY_AMBIENCE_VOLUME, 0.8f, 0f..1f)
    var interfaceVolume: Float by floatPref(KEY_INTERFACE_VOLUME, 0.6f, 0f..1f)
    /** For when there is music. */
    var musicVolume: Float by floatPref(KEY_MUSIC_VOLUME, 0.6f, 0f..1f)

    /** The mix, per [com.rm.apogee.audio.Buses] entry. */
    fun busGains(): FloatArray = floatArrayOf(
        if (vehicleSoundEnabled) masterVolume * effectsVolume else 0f,
        if (ambientSoundEnabled) masterVolume * ambienceVolume else 0f,
        masterVolume * effectsVolume,
        if (uiSoundEnabled) masterVolume * interfaceVolume else 0f,
        masterVolume * musicVolume,
    )

    var hapticsEnabled: Boolean by booleanPref(KEY_HAPTICS, true)

    /** The assembly building's panels as the player last left them. */
    var builderPartsOpen: Boolean by booleanPref(KEY_BUILDER_PARTS, true)
    var builderStagesOpen: Boolean by booleanPref(KEY_BUILDER_STAGES, true)
    /** The full stats card, or just the one-line chip: out by default only where there is room. */
    var builderStatsOpenPortrait: Boolean by booleanPref(KEY_BUILDER_STATS_PORTRAIT, false)
    var builderStatsOpenLandscape: Boolean by booleanPref(KEY_BUILDER_STATS_LANDSCAPE, true)
    /** The drawer tab last used, for a craft standing up and one lying down. */
    var builderTabVertical: String by stringPref(KEY_BUILDER_TAB_VERTICAL, "ALL")
    var builderTabHorizontal: String by stringPref(KEY_BUILDER_TAB_HORIZONTAL, "ALL")

    var showDebugOverlay: Boolean by booleanPref(KEY_DEBUG_OVERLAY, false)

    /**
     * Null means "use whatever [QualityTier.detect] decided". An explicit value
     * overrides detection - needed both for players whose device is misjudged
     * and for the low-tier acceptance pass.
     */
    var qualityOverride: QualityTier? by nullableEnumPref<QualityTier>(KEY_QUALITY)

    /**
     * What detection last concluded, remembered across runs.
     *
     * Detection needs a current GL context, so it cannot run until the player
     * has entered the world at least once. Without persisting it the Settings
     * screen reports a placeholder on first launch - which is worse than
     * useless, because it is the screen where someone goes to find out what
     * their device was judged to be.
     */
    var lastDetectedTier: QualityTier? by nullableEnumPref<QualityTier>(KEY_DETECTED_QUALITY)

    /** The tier actually in force: an explicit override, else detection. */
    val effectiveTier: QualityTier?
        get() = qualityOverride ?: lastDetectedTier

    /** Shadows as chosen; null to go by the tier in force. */
    var shadowQualityOverride: com.rm.apogee.render.ShadowQuality? by nullableEnumPref<com.rm.apogee.render.ShadowQuality>(KEY_SHADOWS)

    /** The shadows actually drawn: the choice, else what the tier in force gets. */
    val shadowQuality: com.rm.apogee.render.ShadowQuality
        get() = shadowQualityOverride
            ?: com.rm.apogee.render.ShadowQuality.defaultFor(effectiveTier ?: QualityTier.MEDIUM)

    // --- delegate plumbing --------------------------------------------------

    private fun stringPref(key: String, default: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, String> {
            private var state by mutableStateOf(prefs.getString(key, default) ?: default)
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String) {
                state = value
                prefs.edit().putString(key, value).apply()
            }
        }

    private fun booleanPref(key: String, default: Boolean) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            private var state by mutableStateOf(prefs.getBoolean(key, default))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
                state = value
                prefs.edit().putBoolean(key, value).apply()
            }
        }

    private fun floatPref(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        object : kotlin.properties.ReadWriteProperty<Any?, Float> {
            private var state by mutableFloatStateOf(prefs.getFloat(key, default).coerceIn(range))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) {
                val clamped = value.coerceIn(range)
                state = clamped
                prefs.edit().putFloat(key, clamped).apply()
            }
        }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            // An unknown stored name - a value from a later version, or one
            // since removed - falls back to the default rather than failing.
            private var state by mutableStateOf(
                prefs.getString(key, null)?.let { stored ->
                    enumValues<T>().firstOrNull { it.name == stored }
                } ?: default
            )
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
                state = value
                prefs.edit().putString(key, value.name).apply()
            }
        }

    private inline fun <reified T : Enum<T>> nullableEnumPref(key: String) =
        object : kotlin.properties.ReadWriteProperty<Any?, T?> {
            private var state by mutableStateOf(
                prefs.getString(key, null)?.let { stored ->
                    enumValues<T>().firstOrNull { it.name == stored }
                }
            )
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(
                thisRef: Any?,
                property: kotlin.reflect.KProperty<*>,
                value: T?,
            ) {
                state = value
                prefs.edit().apply {
                    if (value == null) remove(key) else putString(key, value.name)
                }.apply()
            }
        }

    private companion object {
        const val KEY_PLAYER_NAME = "player_name"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_LAST_SERVER = "last_server_address"
        const val KEY_CONTROL_OPACITY = "control_opacity"
        const val KEY_LEFT_HAND = "left_hand_mode"
        const val KEY_PITCH_STYLE = "pitch_style"
        const val KEY_WEATHER = "weather_intensity"
        const val KEY_CLOUDS = "cloud_cover"
        const val KEY_LAUNCH_TIME = "launch_time"
        const val KEY_SHADOWS = "shadow_quality"
        const val KEY_UI_SOUND = "ui_sound"
        const val KEY_VEHICLE_SOUND = "vehicle_sound"
        const val KEY_AMBIENT_SOUND = "ambient_sound"
        const val KEY_MASTER_VOLUME = "volume_master"
        const val KEY_EFFECTS_VOLUME = "volume_effects"
        const val KEY_AMBIENCE_VOLUME = "volume_ambience"
        const val KEY_INTERFACE_VOLUME = "volume_interface"
        const val KEY_MUSIC_VOLUME = "volume_music"
        const val KEY_HAPTICS = "haptics"
        const val KEY_BUILDER_PARTS = "builder_parts_open"
        const val KEY_BUILDER_STAGES = "builder_stages_open"
        const val KEY_BUILDER_STATS_PORTRAIT = "builder_stats_open_portrait"
        const val KEY_BUILDER_STATS_LANDSCAPE = "builder_stats_open_landscape"
        const val KEY_BUILDER_TAB_VERTICAL = "builder_tab_vertical"
        const val KEY_BUILDER_TAB_HORIZONTAL = "builder_tab_horizontal"
        const val KEY_DEBUG_OVERLAY = "debug_overlay"
        const val KEY_QUALITY = "quality_tier"
        const val KEY_DETECTED_QUALITY = "detected_quality_tier"
    }
}
