package com.flashguard.app.vision

import android.media.Image
import com.flashguard.app.model.FilterConfig

data class DetectionResult(
    val whiteRatioPct: Float,
    val isTriggered: Boolean
)

class WhiteFlashDetector {

    fun analyze(
        image: Image,
        config: FilterConfig,
        currentOverlayAlpha: Float,
        wasTriggered: Boolean
    ): DetectionResult {
        val plane = image.planes.firstOrNull() ?: return DetectionResult(0f, false)
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        if (width <= 0 || height <= 0) return DetectionResult(0f, false)

        val left = ((width * config.cropLeftPct) / 100f).toInt().coerceIn(0, width - 1)
        val right = (width - (width * config.cropRightPct) / 100f).toInt().coerceIn(left + 1, width)
        val top = ((height * config.cropTopPct) / 100f).toInt().coerceIn(0, height - 1)
        val bottom = (height - (height * config.cropBottomPct) / 100f).toInt().coerceIn(top + 1, height)

        // 검은 오버레이가 화면에 떠 있는 동안 캡처 밝기가 어두워지므로 역보정하여 깜빡임 방지
        val clampedAlpha = currentOverlayAlpha.coerceIn(0f, 0.90f)
        val visibleFraction = (1f - clampedAlpha).coerceAtLeast(0.10f)
        val compensate = clampedAlpha > 0.01f

        // 90% 진한 필터 아래에서는 8비트 정수 반올림 오차가 커지므로 보정 여유치를 소폭 부여
        val effectiveMinRgb = if (compensate) (config.minRgbThreshold - 12).coerceAtLeast(120) else config.minRgbThreshold
        val effectiveSpread = if (compensate) config.maxChannelSpread + 14 else config.maxChannelSpread

        var whiteCount = 0
        var totalCount = 0

        for (y in top until bottom) {
            val rowOffset = y * rowStride
            for (x in left until right) {
                val offset = rowOffset + x * pixelStride
                if (offset + 2 >= buffer.capacity()) continue

                val obsR = buffer.get(offset).toInt() and 0xFF
                val obsG = buffer.get(offset + 1).toInt() and 0xFF
                val obsB = buffer.get(offset + 2).toInt() and 0xFF

                val r = if (compensate) (obsR / visibleFraction).toInt().coerceAtMost(255) else obsR
                val g = if (compensate) (obsG / visibleFraction).toInt().coerceAtMost(255) else obsG
                val b = if (compensate) (obsB / visibleFraction).toInt().coerceAtMost(255) else obsB

                val minC = minOf(r, g, b)
                val maxC = maxOf(r, g, b)
                val spread = maxC - minC

                if (minC >= effectiveMinRgb && spread <= effectiveSpread) {
                    whiteCount++
                }
                totalCount++
            }
        }

        val whiteRatioPct = if (totalCount > 0) (whiteCount * 100f) / totalCount else 0f

        // 히스테리시스: 이미 켜진 상태에서는 10%p 더 내려가야 해제되어 경계선 깜빡임 방지
        val releaseThreshold = (config.triggerRatioPct - 10f).coerceAtLeast(15f)
        val isTriggered = if (wasTriggered) {
            whiteRatioPct >= releaseThreshold
        } else {
            whiteRatioPct >= config.triggerRatioPct
        }

        return DetectionResult(
            whiteRatioPct = whiteRatioPct,
            isTriggered = isTriggered
        )
    }
}
