package com.papershift.core

import kotlinx.coroutines.flow.first
import kotlin.random.Random

/** What the worker should do after a run. */
data class RunResult(val outcome: ShiftOutcome?, val retry: Boolean) {
    val stopsRotation: Boolean get() = outcome?.stopsRotation == true
}

/**
 * One fetch → decode → apply → release cycle (PRD §2.2). Android-free so every branch can
 * be unit-tested; the worker only adapts its result to WorkManager.
 */
class ShiftEngine(
    private val store: RotationStore,
    private val gallery: GalleryPort,
    private val bing: BingPort,
    private val decoder: ImageDecoderPort,
    private val wallpaper: WallpaperPort,
    private val device: DevicePort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    /**
     * @param manual true for Start and Shift Now, which ignore the Battery Saver pause.
     * @param attempt WorkManager's runAttemptCount (0 for the first try).
     */
    suspend fun run(manual: Boolean, attempt: Int): RunResult {
        if (!wallpaper.isAllowed()) return finish(ShiftOutcome.WALLPAPER_NOT_ALLOWED)

        val settings = store.settings.first()
        if (!manual && settings.pauseOnBatterySaver && device.isPowerSaveMode()) {
            return finish(ShiftOutcome.SKIPPED_BATTERY_SAVER)
        }

        val target = device.targetSize()
        return when (settings.source) {
            Source.Gallery -> runGallery(settings, target, attempt)
            Source.Bing -> runBing(target, attempt)
        }
    }

    private suspend fun runGallery(settings: RotationSettings, target: PixelSize, attempt: Int): RunResult {
        val folder = settings.folderUri ?: return finish(ShiftOutcome.FOLDER_MISSING)
        val images = try {
            gallery.list(folder)
        } catch (e: FolderMissing) {
            return finish(ShiftOutcome.FOLDER_MISSING)
        } catch (e: AccessLost) {
            return finish(ShiftOutcome.ACCESS_LOST)
        }
        if (images.isEmpty()) return finish(ShiftOutcome.NO_IMAGES)

        var shuffle = store.state.first().shuffle
        repeat(minOf(MAX_DECODE_ATTEMPTS, images.size)) {
            val (image, next) = Shuffle.next(images, shuffle, random) ?: return finish(ShiftOutcome.NO_IMAGES)
            shuffle = next
            val decoded = try {
                decoder.decode(ImageInput.Content(image.uri), target)
            } catch (e: DecodeFailure) {
                return@repeat // EC-03: try the next image.
            }
            try {
                wallpaper.apply(decoded)
            } catch (e: TransientFailure) {
                store.updateState { it.copy(shuffle = next) }
                return retryOrGiveUp(attempt)
            } finally {
                decoded.release()
            }
            val applied = next.copy(lastAppliedId = image.id)
            store.updateState { it.copy(shuffle = applied, credit = null, creditLink = null, currentBingDate = null) }
            return finish(ShiftOutcome.SUCCESS)
        }
        store.updateState { it.copy(shuffle = shuffle) }
        return finish(ShiftOutcome.DECODE_FAILED)
    }

    private suspend fun runBing(target: PixelSize, attempt: Int): RunResult {
        try {
            val state = store.state.first()
            val archive = bing.fetchArchive(device.bingMarket())
            if (archive.isEmpty()) throw TransientFailure("Empty Bing archive")
            val choice = BingRules.choose(archive, state.bingAppliedDates, state.currentBingDate, random)
            val bytes = bing.download(choice)
            val decoded = decoder.decode(ImageInput.Bytes(bytes), target)
            try {
                wallpaper.apply(decoded)
            } finally {
                decoded.release()
            }
            store.updateState {
                it.copy(
                    bingAppliedDates = BingRules.recordApplied(it.bingAppliedDates, choice.startDate),
                    currentBingDate = choice.startDate,
                    credit = choice.copyright,
                    creditLink = choice.copyrightLink,
                )
            }
            return finish(ShiftOutcome.SUCCESS)
        } catch (e: TransientFailure) {
            return retryOrGiveUp(attempt)
        } catch (e: DecodeFailure) {
            // A truncated or corrupt download (EC-06) is retried like a network error.
            return retryOrGiveUp(attempt)
        }
    }

    private suspend fun retryOrGiveUp(attempt: Int): RunResult =
        if (attempt < MAX_RETRIES) RunResult(outcome = null, retry = true) else finish(ShiftOutcome.FAILED_TRANSIENT)

    private suspend fun finish(outcome: ShiftOutcome): RunResult {
        val now = clock()
        store.updateState { it.copy(lastOutcome = outcome, lastOutcomeAt = now) }
        if (outcome.stopsRotation) store.updateSettings { it.copy(rotationEnabled = false) }
        return RunResult(outcome = outcome, retry = false)
    }

    companion object {
        /** EC-03: images tried per gallery run before giving up. */
        const val MAX_DECODE_ATTEMPTS = 3

        /** PRD §4.3: Result.retry() only while runAttemptCount < 3. */
        const val MAX_RETRIES = 3
    }
}
