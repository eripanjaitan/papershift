package com.papershift.core

import kotlinx.coroutines.flow.Flow

/** Persistent settings and worker state (DataStore in the app, a fake in tests). */
interface RotationStore {
    val settings: Flow<RotationSettings>
    val state: Flow<RotationState>

    suspend fun updateSettings(transform: (RotationSettings) -> RotationSettings): RotationSettings
    suspend fun updateState(transform: (RotationState) -> RotationState): RotationState
}

/** Lists the images in a SAF folder. Throws [FolderMissing] or [AccessLost]. */
interface GalleryPort {
    fun list(treeUri: String): List<GalleryImage>
}

/** Bing archive and image download. Throws [TransientFailure]. */
interface BingPort {
    suspend fun fetchArchive(market: String): List<BingImage>
    suspend fun download(image: BingImage): ByteArray
}

sealed interface ImageInput {
    data class Content(val uri: String) : ImageInput
    class Bytes(val bytes: ByteArray) : ImageInput
}

/** A decoded, cropped wallpaper. [release] frees it as soon as it has been applied. */
interface DecodedImage {
    fun release()
}

/** Decodes and crops to exactly [target]. Throws [DecodeFailure]. */
interface ImageDecoderPort {
    fun decode(input: ImageInput, target: PixelSize): DecodedImage
}

interface WallpaperPort {
    fun isAllowed(): Boolean
    fun hasLiveWallpaper(): Boolean

    /** Sets home and lock screen together (decision #4). Throws [TransientFailure]. */
    fun apply(image: DecodedImage)

    /** Current wallpaper IDs and live state, or null if the system won't say (EC-18). */
    fun currentStamp(): WallpaperStamp?
}

interface DevicePort {
    fun isPowerSaveMode(): Boolean
    fun targetSize(): PixelSize
    fun bingMarket(): String
}
