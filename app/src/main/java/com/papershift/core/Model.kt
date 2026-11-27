package com.papershift.core

/** Plain size type so the core logic can be unit-tested without android.util.Size. */
data class PixelSize(val width: Int, val height: Int) {
    val shortSide: Int get() = minOf(width, height)
}

enum class Source { Gallery, Bing }

/** User-facing settings (PRD §5.1, §5.2). */
data class RotationSettings(
    val source: Source = Source.Gallery,
    val folderUri: String? = null,
    val folderName: String? = null,
    val rotationEnabled: Boolean = false,
    val pauseOnBatterySaver: Boolean = true,
    val pauseOnLowBattery: Boolean = true,
    val unmeteredOnly: Boolean = true,
    /** The Android 13+ notification prompt is shown at most once (EC-11 proposal). */
    val askedNotificationPermission: Boolean = false,
    /** EC-18: pause instead of overwriting when something else changed the wallpaper. */
    val pauseOnOutsideChange: Boolean = true,
)

/**
 * The system's view of the current wallpaper (EC-18). Android gives every newly set
 * wallpaper a new ID, so a different stamp means something replaced the wallpaper.
 */
data class WallpaperStamp(val systemId: Int, val lockId: Int, val live: Boolean)

/**
 * Result of one shift attempt (PRD §2.3).
 * [needsAttention]: show a banner. [stopsRotation]: the schedule is cancelled because
 * retrying later can't succeed without the user (EC-01, EC-11, EC-13, EC-18).
 */
enum class ShiftOutcome(val needsAttention: Boolean, val stopsRotation: Boolean) {
    SUCCESS(false, false),
    SKIPPED_BATTERY_SAVER(false, false),
    FAILED_TRANSIENT(false, false),
    NO_IMAGES(true, false),
    DECODE_FAILED(true, false),
    FOLDER_MISSING(true, true),
    ACCESS_LOST(true, true),
    WALLPAPER_NOT_ALLOWED(true, true),
    CHANGED_ELSEWHERE(true, true),
    ;

    /** Problems the user fixes by picking a folder. */
    val isFolderProblem: Boolean get() = this in setOf(FOLDER_MISSING, ACCESS_LOST, NO_IMAGES, DECODE_FAILED)
}

/** Internal state the worker keeps between runs (PRD §5.3). */
data class RotationState(
    val shuffle: ShuffleState? = null,
    val bingAppliedDates: List<String> = emptyList(),
    val currentBingDate: String? = null,
    val credit: String? = null,
    val creditLink: String? = null,
    val lastOutcome: ShiftOutcome? = null,
    val lastOutcomeAt: Long = 0L,
    /** The wallpaper as PaperShift last left it, or as the user accepted it on Start (EC-18). */
    val baseline: WallpaperStamp? = null,
)

data class GalleryImage(val id: String, val uri: String, val lastModified: Long)

data class BingImage(
    val startDate: String,
    val urlBase: String,
    val copyright: String,
    val copyrightLink: String?,
)

/** Failures that should be retried with backoff (EC-04, EC-05, EC-06). */
class TransientFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A single image could not be decoded or is too small (EC-03). */
class DecodeFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The selected folder no longer exists (EC-01). */
class FolderMissing(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The persisted folder permission is gone (EC-11). */
class AccessLost(message: String, cause: Throwable? = null) : Exception(message, cause)
