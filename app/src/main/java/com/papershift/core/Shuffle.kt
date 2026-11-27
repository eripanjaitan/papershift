package com.papershift.core

import kotlin.random.Random

/**
 * Non-repeating shuffle over a folder (PRD §5.3, TC-SAF-02/03). Only the seed and a
 * position are stored, not the list, so state stays small for large folders.
 *
 * [excludeFirst] is the image that was on screen when this cycle started; it is kept out
 * of the first slot so a new cycle never repeats the previous wallpaper.
 */
data class ShuffleState(
    val seed: Long,
    val position: Int,
    val fingerprint: String,
    val excludeFirst: String?,
    val lastAppliedId: String?,
)

object Shuffle {
    /** Changes whenever files are added, removed or modified. */
    fun fingerprint(images: List<GalleryImage>): String {
        val ids = images.map { it.id }.sorted()
        val newest = images.maxOfOrNull { it.lastModified } ?: 0L
        return "${images.size}:$newest:${ids.hashCode()}"
    }

    /** The order for one cycle. Deterministic for a given seed, ids and excludeFirst. */
    fun order(ids: List<String>, seed: Long, excludeFirst: String?): List<String> {
        val shuffled = ids.sorted().shuffled(Random(seed)).toMutableList()
        if (shuffled.size > 1 && shuffled[0] == excludeFirst) {
            shuffled[0] = shuffled[1].also { shuffled[1] = shuffled[0] }
        }
        return shuffled
    }

    /**
     * Picks the next image. Starts a new cycle when the folder changed or every image in
     * the current cycle has been shown. Returns null for an empty folder.
     */
    fun next(images: List<GalleryImage>, state: ShuffleState?, random: Random): Pair<GalleryImage, ShuffleState>? {
        if (images.isEmpty()) return null
        val fingerprint = fingerprint(images)
        val cycle = if (state == null || state.fingerprint != fingerprint || state.position >= images.size) {
            ShuffleState(
                seed = random.nextLong(),
                position = 0,
                fingerprint = fingerprint,
                excludeFirst = state?.lastAppliedId,
                lastAppliedId = state?.lastAppliedId,
            )
        } else {
            state
        }
        val id = order(images.map { it.id }, cycle.seed, cycle.excludeFirst)[cycle.position]
        val image = images.first { it.id == id }
        return image to cycle.copy(position = cycle.position + 1)
    }
}
