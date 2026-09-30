package com.rm.apogee.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rm.apogee.render.QualityTier

/**
 * Per-device, per-player preferences.
 *
 * Note the split this sets up. Anything that's a *rule of the game world* (difficulty, which parts
 * you have, whether re-entry heating is on) belongs to the server and arrives over the wire. Only
 * things about this device and this person live here. Getting that line wrong is how settings
 * screens end up quietly disagreeing with the server a player has joined.
 *
 * They're kept in [Preferences]: SharedPreferences on Android and the browser's storage on the web.
 * On Android I use SharedPreferences instead of DataStore on purpose. These values are read while
 * building the first frame, and DataStore's reads are asynchronous. Swapping a synchronous read on a
 * tiny preference file for a suspending one gains nothing here and makes every call site more
 * complicated.
 *
 * Each property is Compose state with a setter that writes straight through, so there's no save
 * button and no way for the UI and the stored value to disagree.
 */
class GameSettings(private val prefs: Preferences) {

    /** What other players see. It's only cosmetic. See [clientId]. */
    var playerName: String by stringPref(KEY_PLAYER_NAME, "Pilot")

    /**
     * The stripe on your crew's suits, as [com.rm.apogee.core.crew.Crew] numbers them, or -1 for
     * one picked from who you are.
     */
    var suitStripe: Int by intPref(KEY_SUIT_STRIPE, -1, -1 until com.rm.apogee.core.crew.Crew.STRIPES)

    /**
     * This install's identity, made once and never shown as something to edit.
     *
     * Ownership of craft and bases hangs off this. It's deliberately not the player's name, because
     * names aren't unique or stable, and two devices that never set one both arrive as "Pilot" and
     * end up sharing a craft. That's exactly what happened the first time two clients met on a
     * server.
     *
     * It's made the first time it's read instead of in the constructor, so a fresh install doesn't
     * write to disk before anyone has played.
     */
    val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: newClientId()
            .also { prefs.putString(KEY_CLIENT_ID, it) }

    /**
     * The last address typed into the join screen.
     *
     * It's remembered because the case that needs it is the case where discovery can't help (a VPN,
     * a different subnet, a server on the internet), and that doesn't just happen once. Typing an
     * address again every session is the kind of hassle that makes people stop joining.
     */
    var lastServerAddress: String by stringPref(KEY_LAST_SERVER, "")

    /**
     * Opacity of the flight control overlay. The floor is well above zero, because a fully
     * transparent HUD looks exactly like a broken one.
     */
    var controlOpacity: Float by floatPref(KEY_CONTROL_OPACITY, 1.0f, 0.3f..1.0f)

    /** Mirrors the flight controls for left-handed play. */
    var leftHandMode: Boolean by booleanPref(KEY_LEFT_HAND, false)

    /** Whether the Play screen is on the career world instead of the sandbox. */
    var careerMode: Boolean by booleanPref(KEY_CAREER_MODE, false)

    /** Lets the flight controls fade back when nothing has touched them for a few seconds. */
    var fadeWhenIdle: Boolean by booleanPref(KEY_FADE_IDLE, true)

    /** Which craft you pull back on to climb. See [PitchStyle]. */
    var pitchStyle: PitchStyle by enumPref(KEY_PITCH_STYLE, PitchStyle.AIRCRAFT)

    /** Which craft read the stick by the screen and which by their nose. See [SteeringStyle]. */
    var steeringStyle: SteeringStyle by enumPref(KEY_STEERING_STYLE, SteeringStyle.ROTORCRAFT_AND_ROCKETS)

    /** Quick Launch's last craft, by file name, and its site (blank for Automatic). */
    var quickCraft: String by stringPref(KEY_QUICK_CRAFT, "")
    var quickSite: String by stringPref(KEY_QUICK_SITE, "")

    /** How the flight camera follows the craft. See [com.rm.apogee.game.CameraMode]. */
    var cameraMode: com.rm.apogee.game.CameraMode by enumPref(KEY_CAMERA_MODE, com.rm.apogee.game.CameraMode.FREE)

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

    /**
     * The craft's own sounds: engines, wheels, the air rushing past, and the hull. Crashes stay.
     */
    var vehicleSoundEnabled: Boolean by booleanPref(KEY_VEHICLE_SOUND, true)

    /** The world's sounds: wind, rain, thunder, surf and fires. */
    var ambientSoundEnabled: Boolean by booleanPref(KEY_AMBIENT_SOUND, true)

    /**
     * Everything, then each part of the mix: craft and crashes, the world around, and the
     * interface.
     */
    var masterVolume: Float by floatPref(KEY_MASTER_VOLUME, 0.8f, 0f..1f)
    var effectsVolume: Float by floatPref(KEY_EFFECTS_VOLUME, 1.0f, 0f..1f)
    var ambienceVolume: Float by floatPref(KEY_AMBIENCE_VOLUME, 0.8f, 0f..1f)
    var interfaceVolume: Float by floatPref(KEY_INTERFACE_VOLUME, 0.6f, 0f..1f)
    /** For when there's music. */
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

    /**
     * A controller: which button does what (see [com.rm.apogee.input.PadBindings], saved as its
     * line of text, blank for the default), the sticks' dead zone, how fast the look stick turns
     * the camera, whether it's upside down, whether the touch stick gets out of the way while a
     * controller's in use, and whether staging needs A held for a moment.
     */
    var padBindings: String by stringPref(KEY_PAD_BINDINGS, "")
    var padDeadZone: Float by floatPref(KEY_PAD_DEAD_ZONE, 0.15f, 0.05f..0.4f)
    var padLookSpeed: Float by floatPref(KEY_PAD_LOOK_SPEED, 1.0f, 0.3f..2.5f)
    var padInvertLook: Boolean by booleanPref(KEY_PAD_INVERT_LOOK, false)
    var padHideTouch: Boolean by booleanPref(KEY_PAD_HIDE_TOUCH, true)
    var padHoldToStage: Boolean by booleanPref(KEY_PAD_HOLD_STAGE, true)

    /** The assembly building's panels as the player last left them. */
    var builderPartsOpen: Boolean by booleanPref(KEY_BUILDER_PARTS, true)
    var builderStagesOpen: Boolean by booleanPref(KEY_BUILDER_STAGES, true)
    /**
     * The full stats card, or just the one-line chip. It's out by default only where there's room.
     */
    var builderStatsOpenPortrait: Boolean by booleanPref(KEY_BUILDER_STATS_PORTRAIT, false)
    var builderStatsOpenLandscape: Boolean by booleanPref(KEY_BUILDER_STATS_LANDSCAPE, true)
    /** The drawer tab last used, for a craft standing up and one lying down. */
    var builderTabVertical: String by stringPref(KEY_BUILDER_TAB_VERTICAL, "ALL")
    var builderTabHorizontal: String by stringPref(KEY_BUILDER_TAB_HORIZONTAL, "ALL")

    var showDebugOverlay: Boolean by booleanPref(KEY_DEBUG_OVERLAY, false)

    /**
     * Null means "use whatever [QualityTier.detect] decided". A value set here overrides detection.
     * That's needed both for players whose device gets misjudged and for the low-tier acceptance
     * pass.
     */
    var qualityOverride: QualityTier? by nullableEnumPref<QualityTier>(KEY_QUALITY)

    /**
     * What detection decided last time, remembered across runs.
     *
     * Detection needs a current GL context, so it can't run until the player has gone into the
     * world at least once. Without saving it, the Settings screen shows a placeholder on first
     * launch, which is worse than useless, because that's the screen someone goes to to find out
     * what their device was judged to be.
     */
    var lastDetectedTier: QualityTier? by nullableEnumPref<QualityTier>(KEY_DETECTED_QUALITY)

    /** The tier actually in use: an override if there is one, otherwise detection. */
    val effectiveTier: QualityTier?
        get() = qualityOverride ?: lastDetectedTier

    /** What resolution the 3D view is drawn at. Automatic follows the frame rate. */
    var resolution: com.rm.apogee.render.Resolution by enumPref(KEY_RESOLUTION, com.rm.apogee.render.Resolution.AUTO)

    /** Shadows as chosen. Null goes by the tier in use. */
    var shadowQualityOverride: com.rm.apogee.render.ShadowQuality? by nullableEnumPref<com.rm.apogee.render.ShadowQuality>(KEY_SHADOWS)

    /** The shadows actually drawn: the choice, otherwise what the tier in use gets. */
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
                prefs.putString(key, value)
            }
        }

    private fun booleanPref(key: String, default: Boolean) =
        object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
            private var state by mutableStateOf(prefs.getBoolean(key, default))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
                state = value
                prefs.putBoolean(key, value)
            }
        }

    private fun floatPref(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        object : kotlin.properties.ReadWriteProperty<Any?, Float> {
            private var state by mutableFloatStateOf(prefs.getFloat(key, default).coerceIn(range))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Float) {
                val clamped = value.coerceIn(range)
                state = clamped
                prefs.putFloat(key, clamped)
            }
        }

    private fun intPref(key: String, default: Int, range: IntRange) =
        object : kotlin.properties.ReadWriteProperty<Any?, Int> {
            private var state by mutableIntStateOf(prefs.getInt(key, default).coerceIn(range))
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) {
                val clamped = value.coerceIn(range)
                state = clamped
                prefs.putInt(key, clamped)
            }
        }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T) =
        object : kotlin.properties.ReadWriteProperty<Any?, T> {
            // An unknown stored name (a value from a later version, or one that's been removed
            // since) falls back to the default instead of failing.
            private var state by mutableStateOf(
                prefs.getString(key, null)?.let { stored ->
                    enumValues<T>().firstOrNull { it.name == stored }
                } ?: default
            )
            override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = state
            override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
                state = value
                prefs.putString(key, value.name)
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
                prefs.putString(key, value?.name)
            }
        }

    private companion object {
        const val KEY_PLAYER_NAME = "player_name"
        const val KEY_PAD_BINDINGS = "pad_bindings"
        const val KEY_PAD_DEAD_ZONE = "pad_dead_zone"
        const val KEY_PAD_LOOK_SPEED = "pad_look_speed"
        const val KEY_PAD_INVERT_LOOK = "pad_invert_look"
        const val KEY_PAD_HIDE_TOUCH = "pad_hide_touch"
        const val KEY_PAD_HOLD_STAGE = "pad_hold_stage"
        const val KEY_SUIT_STRIPE = "suit_stripe"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_LAST_SERVER = "last_server_address"
        const val KEY_CONTROL_OPACITY = "control_opacity"
        const val KEY_LEFT_HAND = "left_hand_mode"
        const val KEY_FADE_IDLE = "fade_when_idle"
        const val KEY_CAREER_MODE = "career_mode"
        const val KEY_PITCH_STYLE = "pitch_style"
        const val KEY_STEERING_STYLE = "steering_style"
        const val KEY_CAMERA_MODE = "camera_mode"
        const val KEY_QUICK_CRAFT = "quick_craft"
        const val KEY_QUICK_SITE = "quick_site"
        const val KEY_WEATHER = "weather_intensity"
        const val KEY_CLOUDS = "cloud_cover"
        const val KEY_LAUNCH_TIME = "launch_time"
        const val KEY_SHADOWS = "shadow_quality"
        const val KEY_RESOLUTION = "resolution"
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

/** A new install's id: random, and the same form as `java.util.UUID`'s. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun newClientId(): String = kotlin.uuid.Uuid.random().toString()

/** Where settings are kept: SharedPreferences on Android, the browser's storage on the web. */
interface Preferences {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun getInt(key: String, default: Int): Int

    /** Stores [value], or with null takes the setting away. */
    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun putFloat(key: String, value: Float)
    fun putInt(key: String, value: Int)
}
