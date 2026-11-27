package com.papershift.core

import java.util.Locale
import kotlin.random.Random

/** Bing source rules (PRD §5.3). */
object BingRules {
    const val ARCHIVE_SIZE = 8

    /** Markets Bing publishes a daily image for; anything else falls back to en-US (TC-BING-05). */
    private val SUPPORTED_MARKETS = setOf(
        "en-US", "en-GB", "en-CA", "en-AU", "en-IN", "en-NZ",
        "de-DE", "fr-FR", "fr-CA", "es-ES", "it-IT", "pt-BR", "ja-JP", "zh-CN",
    )

    fun market(locale: Locale): String {
        val tag = "${locale.language}-${locale.country}"
        return if (tag in SUPPORTED_MARKETS) tag else "en-US"
    }

    fun archiveUrl(market: String): String =
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=$ARCHIVE_SIZE&mkt=$market"

    /** Preferred image URLs, best first. */
    fun imageUrls(image: BingImage): List<String> = listOf(
        "https://www.bing.com${image.urlBase}_UHD.jpg",
        "https://www.bing.com${image.urlBase}_1920x1080.jpg",
    )

    /**
     * Bing publishes one image a day but PaperShift rotates twice: use today's image if it
     * hasn't been applied yet, otherwise a random archive image other than the current one.
     * [images] is newest first, as returned by the archive endpoint.
     */
    fun choose(images: List<BingImage>, appliedDates: List<String>, currentDate: String?, random: Random): BingImage {
        require(images.isNotEmpty()) { "Empty Bing archive" }
        val today = images.first()
        if (today.startDate !in appliedDates) return today
        val others = images.filter { it.startDate != currentDate }
        return if (others.isEmpty()) today else others[random.nextInt(others.size)]
    }

    /** Remembers the last [ARCHIVE_SIZE] distinct applied dates, newest last. */
    fun recordApplied(appliedDates: List<String>, date: String): List<String> =
        (appliedDates - date + date).takeLast(ARCHIVE_SIZE)
}
