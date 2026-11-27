package com.papershift.ui

import android.app.Application
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.papershift.core.RotationSettings
import com.papershift.core.RotationStatus
import com.papershift.core.Source
import com.papershift.core.StatusRules
import com.papershift.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val settings: RotationSettings = RotationSettings(),
    val status: RotationStatus = RotationStatus.Off,
    val now: Long = 0L,
    val credit: String? = null,
    val creditLink: String? = null,
    /** Usable images in the chosen folder; null while unknown or unreadable. */
    val folderImageCount: Int? = null,
    val wallpaperAllowed: Boolean = true,
    val batteryUnrestricted: Boolean = false,
    /** Problem alerts can be shown (permission granted and not blocked). */
    val notificationsEnabled: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PaperShiftViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val store = graph.store
    private val scheduler = graph.scheduler

    /** Bumped on resume so folder counts and device conditions are re-read. */
    private val refreshes = MutableStateFlow(0)

    private val ticks = combine(
        refreshes,
        flow {
            while (true) {
                emit(Unit)
                delay(TICK_MILLIS)
            }
        },
    ) { _, _ -> System.currentTimeMillis() }

    private val folderCount = combine(store.settings.map { it.folderUri }.distinctUntilChanged(), refreshes) { uri, _ -> uri }
        .mapLatest { uri ->
            uri?.let { withContext(Dispatchers.IO) { runCatching { graph.gallery.list(it).size }.getOrNull() } }
        }

    private val work = combine(scheduler.periodicWork(), scheduler.shiftNowWork(), ::Pair)

    val uiState: StateFlow<UiState> = combine(store.settings, store.state, work, ticks, folderCount) { settings, state, (periodic, shiftNow), now, count ->
        UiState(
            settings = settings,
            status = StatusRules.derive(settings, state, periodic, shiftNow, graph.device.conditions(), now),
            now = now,
            credit = state.credit,
            creditLink = state.creditLink,
            folderImageCount = count,
            wallpaperAllowed = graph.wallpaper.isAllowed(),
            batteryUnrestricted = app.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(app.packageName) == true,
            notificationsEnabled = graph.notifier.isEnabled(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun refresh() {
        refreshes.value++
    }

    fun hasLiveWallpaper(): Boolean = graph.wallpaper.hasLiveWallpaper()

    fun start() = viewModelScope.launch {
        val settings = store.updateSettings { it.copy(rotationEnabled = true) }
        // Starting means "take over from whatever is on screen now" (EC-18), so the current
        // wallpaper becomes the baseline even if the immediate shift is still waiting.
        val current = graph.wallpaper.currentStamp()
        store.updateState { it.copy(lastOutcome = null, baseline = current) }
        graph.notifier.clear()
        scheduler.start(settings)
    }

    /** Called right before the system prompt is shown, so it is never shown again. */
    fun markNotificationPermissionAsked() = viewModelScope.launch {
        store.updateSettings { it.copy(askedNotificationPermission = true) }
    }

    fun pause() = viewModelScope.launch {
        store.updateSettings { it.copy(rotationEnabled = false) }
        scheduler.cancelAll()
    }

    fun shiftNow() = viewModelScope.launch {
        scheduler.shiftNow(store.settings.first())
    }

    fun selectSource(source: Source) = updateSettings { it.copy(source = source) }

    fun setPauseOnBatterySaver(enabled: Boolean) = updateSettings { it.copy(pauseOnBatterySaver = enabled) }

    fun setPauseOnLowBattery(enabled: Boolean) = updateSettings { it.copy(pauseOnLowBattery = enabled) }

    fun setUnmeteredOnly(enabled: Boolean) = updateSettings { it.copy(unmeteredOnly = enabled) }

    /**
     * Switching modes changes which screens PaperShift owns, so the current wallpaper
     * becomes the new baseline (EC-18); otherwise a lock-screen change made while in
     * home-only mode would look like a conflict after switching back.
     */
    fun setHomeScreenOnly(enabled: Boolean) = viewModelScope.launch {
        val current = graph.wallpaper.currentStamp()
        store.updateState { it.copy(baseline = current) }
        store.updateSettings { it.copy(homeScreenOnly = enabled) }
    }

    /** Turning detection back on accepts the current wallpaper, so changes made while it was off don't count. */
    fun setPauseOnOutsideChange(enabled: Boolean) = viewModelScope.launch {
        if (enabled) {
            val current = graph.wallpaper.currentStamp()
            store.updateState { it.copy(baseline = current) }
        }
        store.updateSettings { it.copy(pauseOnOutsideChange = enabled) }
    }

    /** SAF picker result. Resumes rotation if a folder error had stopped it (PRD §2.3). */
    fun onFolderPicked(uri: Uri) = viewModelScope.launch {
        val before = store.settings.first()
        val name = withContext(Dispatchers.IO) {
            try {
                graph.gallery.takeFolder(uri, before.folderUri)
            } catch (e: SecurityException) {
                return@withContext null
            }
            graph.gallery.folderName(uri) ?: uri.lastPathSegment.orEmpty()
        } ?: return@launch

        val previous = store.state.first().lastOutcome
        graph.notifier.clear()
        store.updateState {
            it.copy(
                shuffle = null,
                lastOutcome = it.lastOutcome.takeUnless { o -> o?.isFolderProblem == true },
            )
        }
        val settings = store.updateSettings { it.copy(folderUri = uri.toString(), folderName = name) }
        if (!settings.rotationEnabled && previous != null && previous.isFolderProblem && previous.stopsRotation) {
            start()
        } else {
            scheduler.updateSchedule(settings)
        }
    }

    private fun updateSettings(transform: (RotationSettings) -> RotationSettings) = viewModelScope.launch {
        scheduler.updateSchedule(store.updateSettings(transform))
    }

    private companion object {
        const val TICK_MILLIS = 60_000L
    }
}
