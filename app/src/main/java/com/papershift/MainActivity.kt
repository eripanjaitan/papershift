package com.papershift

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papershift.ui.PaperShiftViewModel
import com.papershift.ui.rotator.RotatorScreen
import com.papershift.ui.settings.SettingsScreen
import com.papershift.ui.theme.PaperShiftTheme

class MainActivity : ComponentActivity() {

    /** Set when launched from the notification's "Choose folder" action. */
    private var chooseFolderRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark-only app (PRD §8): light system-bar icons even when the system is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Only on a fresh launch: a configuration change must not reopen the picker.
        if (savedInstanceState == null) consumeChooseFolder(intent)

        setContent {
            PaperShiftTheme {
                val vm: PaperShiftViewModel = viewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                // Folder contents, battery-optimization status, alerts and network may have changed.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

                // Two screens only, so a single flag replaces a navigation library.
                var showSettings by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(chooseFolderRequested) { if (chooseFolderRequested) showSettings = false }
                BackHandler(enabled = showSettings) { showSettings = false }
                if (showSettings) {
                    SettingsScreen(
                        state = state,
                        onBack = { showSettings = false },
                        onPauseOnBatterySaver = { vm.setPauseOnBatterySaver(it) },
                        onPauseOnLowBattery = { vm.setPauseOnLowBattery(it) },
                        onUnmeteredOnly = { vm.setUnmeteredOnly(it) },
                    )
                } else {
                    RotatorScreen(
                        state = state,
                        onOpenSettings = { showSettings = true },
                        onSelectSource = { vm.selectSource(it) },
                        onFolderPicked = { vm.onFolderPicked(it) },
                        onStart = { vm.start() },
                        onPause = { vm.pause() },
                        onShiftNow = { vm.shiftNow() },
                        hasLiveWallpaper = vm::hasLiveWallpaper,
                        onNotificationPermissionAsked = { vm.markNotificationPermissionAsked() },
                        chooseFolderRequested = chooseFolderRequested,
                        onChooseFolderHandled = { chooseFolderRequested = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeChooseFolder(intent)
    }

    private fun consumeChooseFolder(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CHOOSE_FOLDER, false) == true) {
            intent.removeExtra(EXTRA_CHOOSE_FOLDER)
            chooseFolderRequested = true
        }
    }

    companion object {
        const val EXTRA_CHOOSE_FOLDER = "com.papershift.extra.CHOOSE_FOLDER"
    }
}
