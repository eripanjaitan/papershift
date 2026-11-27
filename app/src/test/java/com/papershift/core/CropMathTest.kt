package com.papershift.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** TC-MEM-02: center crop without distortion for portrait, landscape and square sources. */
class CropMathTest {
    private val phone = PixelSize(1440, 3200)

    @Test
    fun `landscape source is scaled to target height and cropped horizontally`() {
        val plan = CropMath.plan(PixelSize(3840, 2160), phone)
        assertThat(plan.scaledHeight).isEqualTo(3200)
        assertThat(plan.scaledWidth).isEqualTo(5689) // 3840 * 3200 / 2160, rounded
        assertThat(plan.top).isEqualTo(0)
        assertThat(plan.left).isEqualTo((5689 - 1440) / 2)
        assertThat(plan.right - plan.left).isEqualTo(1440)
        assertThat(plan.bottom - plan.top).isEqualTo(3200)
    }

    @Test
    fun `tall portrait source is scaled to target width and cropped vertically`() {
        val plan = CropMath.plan(PixelSize(1000, 4000), phone)
        assertThat(plan.scaledWidth).isEqualTo(1440)
        assertThat(plan.scaledHeight).isEqualTo(5760)
        assertThat(plan.left).isEqualTo(0)
        assertThat(plan.top).isEqualTo((5760 - 3200) / 2)
    }

    @Test
    fun `square source covers the target`() {
        val plan = CropMath.plan(PixelSize(2000, 2000), phone)
        assertThat(plan.scaledWidth).isEqualTo(3200)
        assertThat(plan.scaledHeight).isEqualTo(3200)
        assertThat(plan.left).isEqualTo(880)
        assertThat(plan.top).isEqualTo(0)
    }

    @Test
    fun `aspect ratio is preserved within one pixel`() {
        val source = PixelSize(4032, 3024)
        val plan = CropMath.plan(source, PixelSize(1080, 2400))
        val sourceRatio = source.width.toDouble() / source.height
        val scaledRatio = plan.scaledWidth.toDouble() / plan.scaledHeight
        assertThat(scaledRatio).isWithin(1.0 / plan.scaledHeight).of(sourceRatio)
    }

    @Test
    fun `crop always lies inside the scaled image for many shapes`() {
        val targets = listOf(PixelSize(1080, 1920), PixelSize(1080, 2400), PixelSize(1440, 3200), PixelSize(1600, 2560), PixelSize(2208, 1840))
        for (w in listOf(720, 999, 1080, 3000, 4032, 12000)) {
            for (h in listOf(720, 1001, 1920, 3024, 9000)) {
                for (t in targets) {
                    val plan = CropMath.plan(PixelSize(w, h), t)
                    assertThat(plan.left).isAtLeast(0)
                    assertThat(plan.top).isAtLeast(0)
                    assertThat(plan.right).isAtMost(plan.scaledWidth)
                    assertThat(plan.bottom).isAtMost(plan.scaledHeight)
                }
            }
        }
    }

    @Test
    fun `minimum resolution is 720 px on the shorter side`() {
        assertThat(CropMath.isLargeEnough(PixelSize(720, 1280))).isTrue()
        assertThat(CropMath.isLargeEnough(PixelSize(1280, 719))).isFalse()
    }

    @Test
    fun `portrait target picks the largest display and orients it portrait`() {
        val folded = PixelSize(1080, 2316)
        val unfoldedLandscape = PixelSize(2208, 1840)
        assertThat(CropMath.portraitTarget(listOf(folded, unfoldedLandscape))).isEqualTo(PixelSize(1840, 2208))
        assertThat(CropMath.portraitTarget(listOf(PixelSize(3200, 1440)))).isEqualTo(PixelSize(1440, 3200))
    }

    @Test
    fun `portrait target falls back when no display size is known`() {
        assertThat(CropMath.portraitTarget(emptyList())).isEqualTo(PixelSize(1080, 2400))
    }
}
