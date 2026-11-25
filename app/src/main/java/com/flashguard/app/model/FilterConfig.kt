package com.flashguard.app.model

enum class FilterPresetId(val displayName: String, val shortDesc: String) {
    PURE_WHITE_MEDIUM(
        "완전 백화만 차단 · 보통 어둡기",
        "쌩 흰색 섬광만 감지 · 농도 82%"
    ),
    PURE_WHITE_DARK(
        "완전 백화만 차단 · 더 진하게",
        "쌩 흰색 섬광만 감지 · 농도 90%"
    ),
    BRIGHT_MEDIUM(
        "밝은 화면도 차단 · 보통 어둡기",
        "밝은 연출까지 폭넓게 감지 · 농도 82%"
    ),
    BRIGHT_DARK(
        "밝은 화면도 차단 · 더 진하게",
        "밝은 연출까지 폭넓게 감지 · 농도 90%"
    ),
    CUSTOM(
        "사용자 직접 조절",
        "슬라이더로 직접 맞춘 설정"
    )
}

data class FilterConfig(
    val presetId: FilterPresetId = FilterPresetId.PURE_WHITE_MEDIUM,
    val overlayOpacityPct: Int = 82,
    val minRgbThreshold: Int = 238,
    val maxChannelSpread: Int = 22,
    val triggerRatioPct: Int = 82,
    val cropTopPct: Int = 10,
    val cropBottomPct: Int = 10,
    val cropLeftPct: Int = 15,
    val cropRightPct: Int = 15,
    val minHoldMs: Int = 450,
    val fadeOutMs: Int = 450,
    val showFloatingControl: Boolean = true
)

object FilterPresets {
    fun getPreset(id: FilterPresetId, keepFloating: Boolean = true): FilterConfig {
        return when (id) {
            FilterPresetId.PURE_WHITE_MEDIUM -> FilterConfig(
                presetId = FilterPresetId.PURE_WHITE_MEDIUM,
                overlayOpacityPct = 82,
                minRgbThreshold = 238,
                maxChannelSpread = 22,
                triggerRatioPct = 82,
                cropTopPct = 10,
                cropBottomPct = 10,
                cropLeftPct = 15,
                cropRightPct = 15,
                minHoldMs = 450,
                fadeOutMs = 450,
                showFloatingControl = keepFloating
            )
            FilterPresetId.PURE_WHITE_DARK -> FilterConfig(
                presetId = FilterPresetId.PURE_WHITE_DARK,
                overlayOpacityPct = 90,
                minRgbThreshold = 238,
                maxChannelSpread = 22,
                triggerRatioPct = 82,
                cropTopPct = 10,
                cropBottomPct = 10,
                cropLeftPct = 15,
                cropRightPct = 15,
                minHoldMs = 550,
                fadeOutMs = 550,
                showFloatingControl = keepFloating
            )
            FilterPresetId.BRIGHT_MEDIUM -> FilterConfig(
                presetId = FilterPresetId.BRIGHT_MEDIUM,
                overlayOpacityPct = 82,
                minRgbThreshold = 210,
                maxChannelSpread = 50,
                triggerRatioPct = 65,
                cropTopPct = 10,
                cropBottomPct = 10,
                cropLeftPct = 15,
                cropRightPct = 15,
                minHoldMs = 500,
                fadeOutMs = 500,
                showFloatingControl = keepFloating
            )
            FilterPresetId.BRIGHT_DARK -> FilterConfig(
                presetId = FilterPresetId.BRIGHT_DARK,
                overlayOpacityPct = 90,
                minRgbThreshold = 210,
                maxChannelSpread = 50,
                triggerRatioPct = 65,
                cropTopPct = 10,
                cropBottomPct = 10,
                cropLeftPct = 15,
                cropRightPct = 15,
                minHoldMs = 650,
                fadeOutMs = 650,
                showFloatingControl = keepFloating
            )
            FilterPresetId.CUSTOM -> FilterConfig(presetId = FilterPresetId.CUSTOM)
        }
    }
}
