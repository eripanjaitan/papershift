package com.papershift.core

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.random.Random

class ShiftEngineTest {

    // ---- Fakes -------------------------------------------------------------------------

    private class FakeStore(settings: RotationSettings) : RotationStore {
        override val settings = MutableStateFlow(settings)
        override val state = MutableStateFlow(RotationState())
        override suspend fun updateSettings(transform: (RotationSettings) -> RotationSettings) =
            transform(settings.value).also { settings.value = it }
        override suspend fun updateState(transform: (RotationState) -> RotationState) =
            transform(state.value).also { state.value = it }
    }

    private class FakeGallery(var images: List<GalleryImage> = emptyList(), var error: Exception? = null) : GalleryPort {
        override fun list(treeUri: String): List<GalleryImage> {
            error?.let { throw it }
            return images
        }
    }

    private class FakeBing(var archive: List<BingImage>, var failures: Int = 0) : BingPort {
        val downloaded = mutableListOf<String>()
        override suspend fun fetchArchive(market: String): List<BingImage> {
            if (failures > 0) {
                failures--
                throw TransientFailure("offline")
            }
            return archive
        }
        override suspend fun download(image: BingImage): ByteArray {
            downloaded += image.startDate
            return byteArrayOf(1, 2, 3)
        }
    }

    private class FakeImage(val source: String) : DecodedImage {
        var released = false
        override fun release() {
            released = true
        }
    }

    private class FakeDecoder(val badUris: Set<String> = emptySet()) : ImageDecoderPort {
        val decoded = mutableListOf<FakeImage>()
        override fun decode(input: ImageInput, target: PixelSize): DecodedImage {
            val source = when (input) {
                is ImageInput.Content -> input.uri
                is ImageInput.Bytes -> "bytes"
            }
            if (source in badUris) throw DecodeFailure("corrupt $source")
            return FakeImage(source).also { decoded += it }
        }
    }

    /** Behaves like WallpaperManager: every newly set wallpaper gets a new ID. */
    private class FakeWallpaper(var allowed: Boolean = true) : WallpaperPort {
        val applied = mutableListOf<String>()
        val homeOnlyFlags = mutableListOf<Boolean>()
        var stamp: WallpaperStamp? = WallpaperStamp(systemId = 1, lockId = 1, live = false)
        override fun isAllowed() = allowed
        override fun hasLiveWallpaper() = stamp?.live == true
        override fun apply(image: DecodedImage, homeScreenOnly: Boolean) {
            applied += (image as FakeImage).source
            homeOnlyFlags += homeScreenOnly
            stamp = stamp?.let {
                WallpaperStamp(it.systemId + 1, if (homeScreenOnly) it.lockId else it.lockId + 1, live = false)
            }
        }
        override fun currentStamp() = stamp

        /** Another app (or the user) sets a wallpaper. */
        fun changeOutside(live: Boolean = false) {
            stamp = stamp?.let { WallpaperStamp(it.systemId + 100, it.lockId + 100, live) }
        }

        /** The phone's lock-screen photos (or the user) change only the lock screen. */
        fun changeLockOutside() {
            stamp = stamp?.let { it.copy(lockId = it.lockId + 100) }
        }
    }

    private class FakeNotifier : NotifierPort {
        val events = mutableListOf<String>()
        override fun show(outcome: ShiftOutcome) {
            events += "show:$outcome"
        }
        override fun clear() {
            events += "clear"
        }
    }

    private class FakeDevice(var powerSave: Boolean = false) : DevicePort {
        override fun isPowerSaveMode() = powerSave
        override fun targetSize() = PixelSize(1080, 2400)
        override fun bingMarket() = "en-US"
    }

    private val folder = (1..5).map { GalleryImage("id$it", "content://img$it", it.toLong()) }
    private val bingArchive = (26 downTo 19).map { BingImage("202611$it", "/th?id=$it", "© Photographer $it", "https://www.bing.com/search?q=$it") }

    private val gallerySettings = RotationSettings(source = Source.Gallery, folderUri = "content://tree", rotationEnabled = true)
    private val bingSettings = RotationSettings(source = Source.Bing, rotationEnabled = true)

    private val store = FakeStore(gallerySettings)
    private val gallery = FakeGallery(folder)
    private val bing = FakeBing(bingArchive)
    private var decoder = FakeDecoder()
    private val wallpaper = FakeWallpaper()
    private val device = FakeDevice()
    private val notifier = FakeNotifier()

    private fun engine() = ShiftEngine(store, gallery, bing, decoder, wallpaper, device, notifier, clock = { 42L }, random = Random(1))

    private suspend fun lastOutcome() = store.state.first().lastOutcome

    // ---- Battery & policy ------------------------------------------------------------

    @Test
    fun `scheduled run is skipped under battery saver`() = runTest {
        device.powerSave = true
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result).isEqualTo(RunResult(ShiftOutcome.SKIPPED_BATTERY_SAVER, retry = false))
        assertThat(wallpaper.applied).isEmpty()
        assertThat(store.settings.value.rotationEnabled).isTrue()
    }

    @Test
    fun `manual shift ignores battery saver`() = runTest {
        device.powerSave = true
        assertThat(engine().run(manual = true, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
        assertThat(wallpaper.applied).hasSize(1)
    }

    @Test
    fun `battery saver pause can be turned off`() = runTest {
        device.powerSave = true
        store.settings.value = gallerySettings.copy(pauseOnBatterySaver = false)
        assertThat(engine().run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
    }

    @Test
    fun `blocked wallpaper stops rotation`() = runTest {
        wallpaper.allowed = false
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result.outcome).isEqualTo(ShiftOutcome.WALLPAPER_NOT_ALLOWED)
        assertThat(result.stopsRotation).isTrue()
        assertThat(store.settings.value.rotationEnabled).isFalse()
    }

    // ---- Gallery -----------------------------------------------------------------------

    @Test
    fun `gallery shift applies one image, releases it and records it`() = runTest {
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result.outcome).isEqualTo(ShiftOutcome.SUCCESS)
        assertThat(wallpaper.applied).hasSize(1)
        assertThat(decoder.decoded.single().released).isTrue()
        val shuffle = store.state.value.shuffle!!
        assertThat(shuffle.position).isEqualTo(1)
        assertThat(shuffle.lastAppliedId).isEqualTo(folder.first { it.uri == wallpaper.applied.single() }.id)
        assertThat(store.state.value.lastOutcomeAt).isEqualTo(42L)
    }

    @Test
    fun `five shifts over a five image folder show every image`() = runTest {
        val engine = engine()
        repeat(5) { engine.run(manual = false, attempt = 0) }
        assertThat(wallpaper.applied).containsExactlyElementsIn(folder.map { it.uri })
    }

    @Test
    fun `missing folder setting stops rotation`() = runTest {
        store.settings.value = gallerySettings.copy(folderUri = null)
        assertThat(engine().run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.FOLDER_MISSING)
        assertThat(store.settings.value.rotationEnabled).isFalse()
    }

    @Test
    fun `deleted folder stops rotation`() = runTest {
        gallery.error = FolderMissing("gone")
        assertThat(engine().run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.FOLDER_MISSING)
        assertThat(store.settings.value.rotationEnabled).isFalse()
    }

    @Test
    fun `revoked access stops rotation`() = runTest {
        gallery.error = AccessLost("revoked")
        assertThat(engine().run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.ACCESS_LOST)
        assertThat(store.settings.value.rotationEnabled).isFalse()
    }

    @Test
    fun `empty folder needs attention but keeps the schedule`() = runTest {
        gallery.images = emptyList()
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result.outcome).isEqualTo(ShiftOutcome.NO_IMAGES)
        assertThat(result.stopsRotation).isFalse()
        assertThat(store.settings.value.rotationEnabled).isTrue()
        assertThat(wallpaper.applied).isEmpty()
    }

    @Test
    fun `corrupt images are skipped until a good one is found`() = runTest {
        // Three images, two corrupt: three attempts in one cycle must reach the good one.
        gallery.images = folder.take(3)
        decoder = FakeDecoder(badUris = setOf("content://img1", "content://img2"))
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result.outcome).isEqualTo(ShiftOutcome.SUCCESS)
        assertThat(wallpaper.applied).containsExactly("content://img3")
    }

    @Test
    fun `all corrupt images give up after three attempts`() = runTest {
        decoder = FakeDecoder(badUris = folder.map { it.uri }.toSet())
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result.outcome).isEqualTo(ShiftOutcome.DECODE_FAILED)
        assertThat(result.stopsRotation).isFalse()
        assertThat(store.state.value.shuffle!!.position).isEqualTo(3)
        assertThat(wallpaper.applied).isEmpty()
    }

    // ---- Bing --------------------------------------------------------------------------

    @Test
    fun `bing applies today's image and records its credit`() = runTest {
        store.settings.value = bingSettings
        assertThat(engine().run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
        val state = store.state.value
        assertThat(bing.downloaded).containsExactly("20261126")
        assertThat(state.currentBingDate).isEqualTo("20261126")
        assertThat(state.bingAppliedDates).containsExactly("20261126")
        assertThat(state.credit).isEqualTo("© Photographer 26")
        assertThat(decoder.decoded.single().released).isTrue()
    }

    @Test
    fun `second bing run of the day uses a different image`() = runTest {
        store.settings.value = bingSettings
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        engine.run(manual = false, attempt = 0)
        assertThat(bing.downloaded).hasSize(2)
        assertThat(bing.downloaded[1]).isNotEqualTo(bing.downloaded[0])
    }

    @Test
    fun `network failure retries without recording an outcome`() = runTest {
        store.settings.value = bingSettings
        bing.failures = 1
        val result = engine().run(manual = false, attempt = 0)
        assertThat(result).isEqualTo(RunResult(outcome = null, retry = true))
        assertThat(lastOutcome()).isNull()
        assertThat(wallpaper.applied).isEmpty()
    }

    @Test
    fun `network failure gives up after three retries and keeps the schedule`() = runTest {
        store.settings.value = bingSettings
        bing.failures = 1
        val result = engine().run(manual = false, attempt = ShiftEngine.MAX_RETRIES)
        assertThat(result).isEqualTo(RunResult(ShiftOutcome.FAILED_TRANSIENT, retry = false))
        assertThat(store.settings.value.rotationEnabled).isTrue()
    }

    @Test
    fun `corrupt bing download is retried`() = runTest {
        store.settings.value = bingSettings
        decoder = FakeDecoder(badUris = setOf("bytes"))
        assertThat(engine().run(manual = false, attempt = 0).retry).isTrue()
    }

    // ---- Problem notifications (EC-11) -------------------------------------------------

    @Test
    fun `background folder loss notifies`() = runTest {
        gallery.error = FolderMissing("gone")
        engine().run(manual = false, attempt = 0)
        assertThat(notifier.events).containsExactly("show:FOLDER_MISSING")
    }

    @Test
    fun `manual run with a problem does not notify`() = runTest {
        gallery.error = AccessLost("revoked")
        engine().run(manual = true, attempt = 0)
        assertThat(notifier.events).isEmpty()
    }

    @Test
    fun `empty folder notifies once across runs`() = runTest {
        gallery.images = emptyList()
        val engine = engine()
        repeat(3) { engine.run(manual = false, attempt = 0) }
        assertThat(notifier.events).containsExactly("show:NO_IMAGES")
    }

    @Test
    fun `a successful shift clears the alert`() = runTest {
        gallery.images = emptyList()
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        gallery.images = folder
        engine.run(manual = false, attempt = 0)
        assertThat(notifier.events).containsExactly("show:NO_IMAGES", "clear").inOrder()
    }

    // ---- Outside changes (EC-18) -------------------------------------------------------

    @Test
    fun `first run has no baseline, applies and records one`() = runTest {
        assertThat(engine().run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
        assertThat(store.state.value.baseline).isEqualTo(wallpaper.stamp)
    }

    @Test
    fun `outside change pauses the next scheduled run`() = runTest {
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeOutside()
        val result = engine.run(manual = false, attempt = 0)
        assertThat(result.outcome).isEqualTo(ShiftOutcome.CHANGED_ELSEWHERE)
        assertThat(result.stopsRotation).isTrue()
        assertThat(store.settings.value.rotationEnabled).isFalse()
        assertThat(wallpaper.applied).hasSize(1)
        assertThat(notifier.events).containsExactly("clear", "show:CHANGED_ELSEWHERE").inOrder()
    }

    @Test
    fun `a live wallpaper switched on later counts as an outside change`() = runTest {
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeOutside(live = true)
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.CHANGED_ELSEWHERE)
    }

    @Test
    fun `consecutive own shifts never look like outside changes`() = runTest {
        val engine = engine()
        repeat(4) { assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS) }
    }

    @Test
    fun `shift now overrides an outside change and resets the baseline`() = runTest {
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeOutside()
        assertThat(engine.run(manual = true, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
        assertThat(store.state.value.baseline).isEqualTo(wallpaper.stamp)
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
    }

    @Test
    fun `detection can be turned off`() = runTest {
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        store.settings.value = store.settings.value.copy(pauseOnOutsideChange = false)
        wallpaper.changeOutside()
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
    }

    @Test
    fun `unknown wallpaper IDs disable detection instead of pausing`() = runTest {
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.stamp = null
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
    }

    @Test
    fun `bing shifts record the baseline too`() = runTest {
        store.settings.value = bingSettings
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeOutside()
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.CHANGED_ELSEWHERE)
    }

    // ---- Home screen only (decision #11) ----------------------------------------------

    @Test
    fun `home and lock are set together by default`() = runTest {
        engine().run(manual = false, attempt = 0)
        assertThat(wallpaper.homeOnlyFlags).containsExactly(false)
    }

    @Test
    fun `home screen only mode leaves the lock screen alone`() = runTest {
        store.settings.value = gallerySettings.copy(homeScreenOnly = true)
        engine().run(manual = false, attempt = 0)
        store.settings.value = bingSettings.copy(homeScreenOnly = true)
        engine().run(manual = false, attempt = 0)
        assertThat(wallpaper.homeOnlyFlags).containsExactly(true, true)
    }

    @Test
    fun `lock screen changes are ignored in home screen only mode`() = runTest {
        store.settings.value = gallerySettings.copy(homeScreenOnly = true)
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeLockOutside()
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.SUCCESS)
    }

    @Test
    fun `lock screen changes still pause when PaperShift owns both screens`() = runTest {
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeLockOutside()
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.CHANGED_ELSEWHERE)
    }

    @Test
    fun `home screen changes still pause in home screen only mode`() = runTest {
        store.settings.value = gallerySettings.copy(homeScreenOnly = true)
        val engine = engine()
        engine.run(manual = false, attempt = 0)
        wallpaper.changeOutside()
        assertThat(engine.run(manual = false, attempt = 0).outcome).isEqualTo(ShiftOutcome.CHANGED_ELSEWHERE)
    }

    @Test
    fun `retries and transient failures stay silent`() = runTest {
        store.settings.value = bingSettings
        bing.failures = 10
        engine().run(manual = false, attempt = 0)
        engine().run(manual = false, attempt = ShiftEngine.MAX_RETRIES)
        assertThat(notifier.events).isEmpty()
    }
}
