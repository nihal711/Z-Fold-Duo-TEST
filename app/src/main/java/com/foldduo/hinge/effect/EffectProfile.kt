package com.foldduo.hinge.effect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EffectProfile(
    val intensity: Float = 1f,
    val blurSpread: Float = 0.12f,
    val eyeDistanceMm: Float = 450f,
    val foldSplitsLong: Boolean = false,
    val movingSide: Int = -1,
    val coverFrostFromRight: Boolean = true,
    val panelSwitchAngle: Float = 20f,
)

object EffectPreferences {
    private lateinit var prefs: android.content.SharedPreferences
    private val _config = MutableStateFlow(EffectProfile())
    val config = _config.asStateFlow()

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("fold_effect", Context.MODE_PRIVATE)
        val d = EffectProfile()
        val storedSwitchAngle = prefs.getFloat("panelSwitchAngle", d.panelSwitchAngle)
        val switchAngle = storedSwitchAngle.takeIf { it >= MIN_VALID_SWITCH_ANGLE }
            ?: d.panelSwitchAngle.also {
                prefs.edit().putFloat("panelSwitchAngle", it).apply()
            }
        _config.value = EffectProfile(
            intensity = prefs.getFloat("intensity", d.intensity),
            blurSpread = prefs.getFloat("blurSpread", d.blurSpread),
            eyeDistanceMm = prefs.getFloat("eyeDistanceMm", d.eyeDistanceMm),
            foldSplitsLong = prefs.getBoolean("foldSplitsLong", d.foldSplitsLong),
            movingSide = prefs.getInt("movingSide", d.movingSide),
            coverFrostFromRight = prefs.getBoolean("coverFrostFromRight", d.coverFrostFromRight),
            panelSwitchAngle = switchAngle,
        )
    }

    fun learnPanelSwitch(angle: Float) {
        if (!angle.isFinite() || angle !in 5f..45f) return
        val old = _config.value.panelSwitchAngle
        val learned = if (old == 20f) angle else old * 0.7f + angle * 0.3f
        _config.value = _config.value.copy(panelSwitchAngle = learned)
        prefs.edit().putFloat("panelSwitchAngle", learned).apply()
    }

    private const val MIN_VALID_SWITCH_ANGLE = 10f
}
