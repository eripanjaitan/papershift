package com.papershift.platform

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.view.Display
import androidx.core.content.getSystemService
import androidx.window.layout.WindowMetricsCalculator
import com.papershift.core.BingRules
import com.papershift.core.CropMath
import com.papershift.core.DecodedImage
import com.papershift.core.DeviceConditions
import com.papershift.core.DevicePort
import com.papershift.core.PixelSize
import com.papershift.core.TransientFailure
import com.papershift.core.WallpaperPort
import com.papershift.core.WallpaperStamp
import java.io.IOException
import java.util.Locale

class SystemWallpaper(context: Context) : WallpaperPort {
    private val manager = WallpaperManager.getInstance(context.applicationContext)

    override fun isAllowed(): Boolean = manager.isWallpaperSupported && manager.isSetWallpaperAllowed

    override fun hasLiveWallpaper(): Boolean = manager.wallpaperInfo != null

    override fun apply(image: DecodedImage) {
        val bitmap = (image as BitmapImage).bitmap
        try {
            // One call so home and lock always match (PRD §4.2 step 4, decision #4).
            manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } catch (e: IOException) {
            throw TransientFailure("setBitmap failed", e)
        }
    }

    override fun currentStamp(): WallpaperStamp? = try {
        WallpaperStamp(
            systemId = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM),
            lockId = manager.getWallpaperId(WallpaperManager.FLAG_LOCK),
            live = manager.wallpaperInfo != null,
        )
    } catch (e: SecurityException) {
        null // Some OEM builds restrict wallpaper queries; detection is then simply off.
    }
}

class AndroidDevice(context: Context) : DevicePort {
    private val app = context.applicationContext

    override fun isPowerSaveMode(): Boolean = app.getSystemService<PowerManager>()?.isPowerSaveMode == true

    /**
     * Largest size the default display reports, in portrait (PRD §4.2 step 1, EC-08).
     * Combines the maximum window bounds with the display's physical modes, which on some
     * foldables include the unfolded size. External and virtual displays are ignored.
     */
    override fun targetSize(): PixelSize {
        val sizes = mutableListOf<PixelSize>()
        runCatching {
            val bounds = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(app).bounds
            sizes += PixelSize(bounds.width(), bounds.height())
        }
        app.getSystemService<DisplayManager>()?.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.forEach { mode ->
            sizes += PixelSize(mode.physicalWidth, mode.physicalHeight)
        }
        return CropMath.portraitTarget(sizes)
    }

    override fun bingMarket(): String = BingRules.market(Locale.getDefault())

    /** Read by the UI to explain a due run that hasn't happened (PRD §2.3 WAITING). */
    fun conditions(): DeviceConditions {
        val connectivity = app.getSystemService<ConnectivityManager>()
        val caps = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true ||
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryLow = battery?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) == true
        return DeviceConditions(connected = connected, unmetered = unmetered, batteryLow = batteryLow)
    }
}
