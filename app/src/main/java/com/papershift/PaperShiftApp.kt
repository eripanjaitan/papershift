package com.papershift

import android.app.Application
import android.content.Context
import com.papershift.core.ShiftEngine
import com.papershift.platform.AndroidDevice
import com.papershift.platform.BingClient
import com.papershift.platform.DataStoreRotationStore
import com.papershift.platform.SafGallery
import com.papershift.platform.SystemWallpaper
import com.papershift.platform.WallpaperDecoder
import com.papershift.work.RotationScheduler

class PaperShiftApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

/** Manual dependency wiring; small enough that a DI framework isn't worth it. */
class AppGraph(context: Context) {
    val store = DataStoreRotationStore(context)
    val gallery = SafGallery(context)
    val wallpaper = SystemWallpaper(context)
    val device = AndroidDevice(context)
    val scheduler = RotationScheduler(context)
    val engine by lazy {
        ShiftEngine(
            store = store,
            gallery = gallery,
            bing = BingClient(),
            decoder = WallpaperDecoder(context),
            wallpaper = wallpaper,
            device = device,
        )
    }
}

val Context.graph: AppGraph get() = (applicationContext as PaperShiftApp).graph
