package com.papershift.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How to turn a source image into exactly [target]: scale it (aspect preserved) until it
 * covers the target, then take the centered [target]-sized rectangle (PRD §4.2).
 * Coordinates of the crop are in the scaled image.
 */
data class CropPlan(
    val scaledWidth: Int,
    val scaledHeight: Int,
    val left: Int,
    val top: Int,
    val target: PixelSize,
) {
    val right: Int get() = left + target.width
    val bottom: Int get() = top + target.height
}

object CropMath {
    /** Images whose shorter side is below this are skipped (PRD §4.2, decision #9). */
    const val MIN_SHORT_SIDE_PX = 720

    fun plan(source: PixelSize, target: PixelSize): CropPlan {
        require(source.width > 0 && source.height > 0) { "Empty source $source" }
        require(target.width > 0 && target.height > 0) { "Empty target $target" }
        val scale = max(
            target.width.toDouble() / source.width,
            target.height.toDouble() / source.height,
        )
        // Rounding can land one pixel short of the target; never go below it.
        val scaledWidth = max(target.width, (source.width * scale).roundToInt())
        val scaledHeight = max(target.height, (source.height * scale).roundToInt())
        return CropPlan(
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight,
            left = (scaledWidth - target.width) / 2,
            top = (scaledHeight - target.height) / 2,
            target = target,
        )
    }

    fun isLargeEnough(source: PixelSize): Boolean = source.shortSide >= MIN_SHORT_SIDE_PX

    /**
     * Target wallpaper size: the largest reported display size, in natural portrait
     * orientation (PRD §4.2 step 1, EC-08).
     */
    fun portraitTarget(displaySizes: List<PixelSize>): PixelSize {
        val largest = displaySizes
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: FALLBACK_TARGET
        return PixelSize(width = minOf(largest.width, largest.height), height = maxOf(largest.width, largest.height))
    }

    private val FALLBACK_TARGET = PixelSize(1080, 2400)
}
