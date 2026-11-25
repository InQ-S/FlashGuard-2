package com.flashguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.flashguard.app.model.FilterConfig
import com.flashguard.app.model.FilterPresetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConfigStore {
    private const val PREFS_NAME = "flashguard_prefs"
    private const val CURRENT_CONFIG_VERSION = 2

    private const val KEY_VERSION = "config_version"
    private const val KEY_PRESET = "preset_id"
    private const val KEY_OPACITY = "overlay_opacity_pct"
    private const val KEY_MIN_RGB = "min_rgb_threshold"
    private const val KEY_SPREAD = "max_channel_spread"
    private const val KEY_RATIO = "trigger_ratio_pct"
    private const val KEY_CROP_TOP = "crop_top_pct"
    private const val KEY_CROP_BOTTOM = "crop_bottom_pct"
    private const val KEY_CROP_LEFT = "crop_left_pct"
    private const val KEY_CROP_RIGHT = "crop_right_pct"
    private const val KEY_HOLD_MS = "min_hold_ms"
    private const val KEY_FADE_MS = "fade_out_ms"
    private const val KEY_FLOATING = "show_floating_control"

    private var prefs: SharedPreferences? = null
    private val _config = MutableStateFlow(FilterConfig())
    val config: StateFlow<FilterConfig> = _config.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val savedVersion = sp.getInt(KEY_VERSION, 1)
        if (savedVersion < CURRENT_CONFIG_VERSION) {
            val freshDefaults = FilterConfig()
            update(freshDefaults)
        } else {
            _config.value = loadFromPrefs(sp)
        }
    }

    fun update(newConfig: FilterConfig) {
        _config.value = newConfig
        val sp = prefs ?: return
        sp.edit()
            .putInt(KEY_VERSION, CURRENT_CONFIG_VERSION)
            .putString(KEY_PRESET, newConfig.presetId.name)
            .putInt(KEY_OPACITY, newConfig.overlayOpacityPct)
            .putInt(KEY_MIN_RGB, newConfig.minRgbThreshold)
            .putInt(KEY_SPREAD, newConfig.maxChannelSpread)
            .putInt(KEY_RATIO, newConfig.triggerRatioPct)
            .putInt(KEY_CROP_TOP, newConfig.cropTopPct)
            .putInt(KEY_CROP_BOTTOM, newConfig.cropBottomPct)
            .putInt(KEY_CROP_LEFT, newConfig.cropLeftPct)
            .putInt(KEY_CROP_RIGHT, newConfig.cropRightPct)
            .putInt(KEY_HOLD_MS, newConfig.minHoldMs)
            .putInt(KEY_FADE_MS, newConfig.fadeOutMs)
            .putBoolean(KEY_FLOATING, newConfig.showFloatingControl)
            .apply()
    }

    private fun loadFromPrefs(sp: SharedPreferences): FilterConfig {
        val defaults = FilterConfig()
        val presetName = sp.getString(KEY_PRESET, defaults.presetId.name)
        val presetId = runCatching { FilterPresetId.valueOf(presetName ?: "") }
            .getOrDefault(defaults.presetId)

        return FilterConfig(
            presetId = presetId,
            overlayOpacityPct = sp.getInt(KEY_OPACITY, defaults.overlayOpacityPct),
            minRgbThreshold = sp.getInt(KEY_MIN_RGB, defaults.minRgbThreshold),
            maxChannelSpread = sp.getInt(KEY_SPREAD, defaults.maxChannelSpread),
            triggerRatioPct = sp.getInt(KEY_RATIO, defaults.triggerRatioPct),
            cropTopPct = sp.getInt(KEY_CROP_TOP, defaults.cropTopPct),
            cropBottomPct = sp.getInt(KEY_CROP_BOTTOM, defaults.cropBottomPct),
            cropLeftPct = sp.getInt(KEY_CROP_LEFT, defaults.cropLeftPct),
            cropRightPct = sp.getInt(KEY_CROP_RIGHT, defaults.cropRightPct),
            minHoldMs = sp.getInt(KEY_HOLD_MS, defaults.minHoldMs),
            fadeOutMs = sp.getInt(KEY_FADE_MS, defaults.fadeOutMs),
            showFloatingControl = sp.getBoolean(KEY_FLOATING, defaults.showFloatingControl)
        )
    }
}
