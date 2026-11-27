package com.papershift.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/** TC-BING-04 and TC-BING-05. */
class BingRulesTest {
    private val archive = (26 downTo 19).map { day ->
        BingImage(startDate = "202611$day", urlBase = "/th?id=OHR.Img$day", copyright = "Photo $day", copyrightLink = null)
    }

    @Test
    fun `today's image is used first`() {
        val choice = BingRules.choose(archive, appliedDates = listOf("20261125"), currentDate = "20261125", random = Random(0))
        assertThat(choice.startDate).isEqualTo("20261126")
    }

    @Test
    fun `second run of the day picks a different archive image`() {
        repeat(100) { seed ->
            val choice = BingRules.choose(archive, appliedDates = listOf("20261126"), currentDate = "20261126", random = Random(seed))
            assertThat(choice.startDate).isNotEqualTo("20261126")
        }
    }

    @Test
    fun `archive of one already-applied image returns it rather than failing`() {
        val single = archive.take(1)
        val choice = BingRules.choose(single, appliedDates = listOf("20261126"), currentDate = "20261126", random = Random(0))
        assertThat(choice).isEqualTo(single.single())
    }

    @Test
    fun `applied dates keep the last eight distinct values`() {
        var dates = emptyList<String>()
        (1..10).forEach { dates = BingRules.recordApplied(dates, "d$it") }
        dates = BingRules.recordApplied(dates, "d5")
        // d1..d10 keeps d3..d10; re-applying d5 moves it to the end.
        assertThat(dates).containsExactly("d3", "d4", "d6", "d7", "d8", "d9", "d10", "d5").inOrder()
    }

    @Test
    fun `supported market is kept and others fall back to en-US`() {
        assertThat(BingRules.market(Locale.UK)).isEqualTo("en-GB")
        assertThat(BingRules.market(Locale.JAPAN)).isEqualTo("ja-JP")
        assertThat(BingRules.market(Locale.forLanguageTag("id-ID"))).isEqualTo("en-US")
        assertThat(BingRules.market(Locale.ENGLISH)).isEqualTo("en-US")
    }

    @Test
    fun `image URLs prefer UHD then 1920x1080`() {
        assertThat(BingRules.imageUrls(archive.first())).containsExactly(
            "https://www.bing.com/th?id=OHR.Img26_UHD.jpg",
            "https://www.bing.com/th?id=OHR.Img26_1920x1080.jpg",
        ).inOrder()
    }
}
