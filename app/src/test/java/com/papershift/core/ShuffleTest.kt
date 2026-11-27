package com.papershift.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** TC-SAF-02 and TC-SAF-03. */
class ShuffleTest {
    private fun images(n: Int) = (1..n).map { GalleryImage(id = "img$it", uri = "content://img$it", lastModified = 1000L + it) }

    /** Runs [count] picks, marking each pick as applied like the engine does. */
    private fun pick(images: List<GalleryImage>, start: ShuffleState?, count: Int, random: Random): Pair<List<String>, ShuffleState?> {
        var state = start
        val picked = mutableListOf<String>()
        repeat(count) {
            val (image, next) = Shuffle.next(images, state, random)!!
            picked += image.id
            state = next.copy(lastAppliedId = image.id)
        }
        return picked to state
    }

    @Test
    fun `every image is shown once per cycle`() {
        val folder = images(25)
        val (picked, _) = pick(folder, null, 25, Random(1))
        assertThat(picked).containsExactlyElementsIn(folder.map { it.id })
    }

    @Test
    fun `a new cycle never starts with the image that ended the previous one`() {
        val folder = images(4)
        repeat(200) { seed ->
            val (picked, _) = pick(folder, null, 12, Random(seed))
            for (i in 1 until picked.size) {
                assertThat(picked[i]).isNotEqualTo(picked[i - 1])
            }
        }
    }

    @Test
    fun `order is stable for a stored seed`() {
        val ids = images(10).map { it.id }
        assertThat(Shuffle.order(ids, 42L, null)).isEqualTo(Shuffle.order(ids.reversed(), 42L, null))
    }

    @Test
    fun `adding a file starts a new cycle without repeating the current image`() {
        val folder = images(5)
        val (picked, state) = pick(folder, null, 3, Random(7))
        val grown = folder + GalleryImage("img99", "content://img99", 5000L)
        val (image, next) = Shuffle.next(grown, state, Random(8))!!
        assertThat(next.position).isEqualTo(1)
        assertThat(next.fingerprint).isNotEqualTo(state!!.fingerprint)
        assertThat(image.id).isNotEqualTo(picked.last())
    }

    @Test
    fun `removing the current image still works`() {
        val folder = images(3)
        val (picked, state) = pick(folder, null, 1, Random(3))
        val shrunk = folder.filterNot { it.id == picked.single() }
        val (image, _) = Shuffle.next(shrunk, state, Random(4))!!
        assertThat(image.id).isIn(shrunk.map { it.id })
    }

    @Test
    fun `single image folder keeps returning it`() {
        val (picked, _) = pick(images(1), null, 3, Random(0))
        assertThat(picked).containsExactly("img1", "img1", "img1")
    }

    @Test
    fun `empty folder returns null`() {
        assertThat(Shuffle.next(emptyList(), null, Random(0))).isNull()
    }
}
