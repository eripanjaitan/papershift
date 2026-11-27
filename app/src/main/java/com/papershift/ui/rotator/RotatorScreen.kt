package com.papershift.ui.rotator

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.papershift.R
import com.papershift.core.RotationSettings
import com.papershift.core.RotationStatus
import com.papershift.core.ShiftOutcome
import com.papershift.core.Source
import com.papershift.core.StatusRules
import com.papershift.core.WaitReason
import com.papershift.ui.UiState
import com.papershift.ui.components.SectionCard
import com.papershift.ui.theme.PaperShiftTheme

/** PRD §5.1. Stateless: all state comes from [state], all actions go through the callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotatorScreen(
    state: UiState,
    onOpenSettings: () -> Unit,
    onSelectSource: (Source) -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onShiftNow: () -> Unit,
    hasLiveWallpaper: () -> Boolean,
) {
    val settings = state.settings
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderPicked(uri)
    }
    var confirmLiveWallpaper by rememberSaveable { mutableStateOf(false) }

    val needsFolder = settings.source == Source.Gallery && settings.folderUri == null
    val canStart = state.wallpaperAllowed && !needsFolder

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            val attention = (state.status as? RotationStatus.NeedsAttention)?.outcome
                ?: ShiftOutcome.WALLPAPER_NOT_ALLOWED.takeUnless { state.wallpaperAllowed }
            if (attention != null) {
                AttentionBanner(
                    outcome = attention,
                    onClick = if (attention == ShiftOutcome.WALLPAPER_NOT_ALLOWED) null else ({ pickFolder.launch(null) }),
                )
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Source.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = settings.source == option,
                        onClick = { onSelectSource(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, Source.entries.size),
                    ) {
                        Text(stringResource(if (option == Source.Gallery) R.string.source_gallery else R.string.source_bing))
                    }
                }
            }

            if (settings.source == Source.Gallery) {
                FolderRow(
                    name = settings.folderName,
                    imageCount = state.folderImageCount,
                    onChoose = { pickFolder.launch(null) },
                )
            }

            Button(
                onClick = {
                    when {
                        settings.rotationEnabled -> onPause()
                        hasLiveWallpaper() -> confirmLiveWallpaper = true
                        else -> onStart()
                    }
                },
                enabled = settings.rotationEnabled || canStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (settings.rotationEnabled) R.string.pause_rotation else R.string.start_rotation))
            }
            Text(
                text = stringResource(if (needsFolder) R.string.choose_folder_first else R.string.start_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onShiftNow,
                enabled = settings.rotationEnabled && state.status != RotationStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.shift_now))
            }

            StatusLine(state.status, state.now, settings)

            if (settings.source == Source.Bing && state.credit != null) {
                CreditLine(state.credit, state.creditLink)
            }
        }
    }

    if (confirmLiveWallpaper) {
        AlertDialog(
            onDismissRequest = { confirmLiveWallpaper = false },
            title = { Text(stringResource(R.string.live_wallpaper_title)) },
            text = { Text(stringResource(R.string.live_wallpaper_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLiveWallpaper = false
                    onStart()
                }) { Text(stringResource(R.string.live_wallpaper_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLiveWallpaper = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FolderRow(name: String?, imageCount: Int?, onChoose: () -> Unit) {
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name?.let { "📁 $it" } ?: stringResource(R.string.folder_none),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (name != null && imageCount != null) {
                    Text(
                        text = pluralStringResource(R.plurals.folder_photo_count, imageCount, imageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onChoose) {
                Text(stringResource(if (name == null) R.string.folder_choose else R.string.folder_change))
            }
        }
    }
}

@Composable
private fun AttentionBanner(outcome: ShiftOutcome, onClick: (() -> Unit)?) {
    val message = when (outcome) {
        ShiftOutcome.FOLDER_MISSING -> R.string.attention_folder_missing
        ShiftOutcome.ACCESS_LOST -> R.string.attention_access_lost
        ShiftOutcome.NO_IMAGES -> R.string.attention_no_images
        ShiftOutcome.DECODE_FAILED -> R.string.attention_decode_failed
        else -> R.string.attention_not_allowed
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun StatusLine(status: RotationStatus, now: Long, settings: RotationSettings) {
    val text = when (status) {
        RotationStatus.Off -> stringResource(R.string.status_off)
        RotationStatus.Running -> stringResource(R.string.status_running)
        is RotationStatus.Scheduled -> when (val nextAt = status.nextAt) {
            null -> stringResource(R.string.status_scheduled)
            else -> StatusRules.formatRemaining(nextAt - now)
                ?.let { stringResource(R.string.status_next_in, it) }
                ?: stringResource(R.string.status_due)
        }
        is RotationStatus.Skipped -> status.nextAt
            ?.let { StatusRules.formatRemaining(it - now) }
            ?.let { stringResource(R.string.status_skipped, it) }
            ?: stringResource(R.string.status_skipped_no_time)
        is RotationStatus.Waiting -> stringResource(
            when (status.reason) {
                WaitReason.Network -> R.string.status_waiting_network
                WaitReason.UnmeteredNetwork -> R.string.status_waiting_unmetered
                WaitReason.Battery -> R.string.status_waiting_battery
            },
        )
        // The banner explains it; keep the line consistent with whether rotation is on.
        is RotationStatus.NeedsAttention ->
            stringResource(if (settings.rotationEnabled) R.string.status_scheduled else R.string.status_off)
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
}

/** Bing photo credit (PRD §5.1 item 6, R-1), tappable when Bing gives a link. */
@Composable
private fun CreditLine(credit: String, link: String?) {
    val uriHandler = LocalUriHandler.current
    Text(
        text = credit,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (link != null) {
            Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(link) } }
        } else {
            Modifier
        },
    )
}

@Preview
@Composable
private fun RotatorScreenPreview() {
    PaperShiftTheme {
        RotatorScreen(
            state = UiState(
                settings = RotationSettings(folderUri = "content://preview", folderName = "Nature", rotationEnabled = true),
                status = RotationStatus.Scheduled(nextAt = 5L * 3_600_000 + 20 * 60_000),
                folderImageCount = 42,
            ),
            onOpenSettings = {},
            onSelectSource = {},
            onFolderPicked = {},
            onStart = {},
            onPause = {},
            onShiftNow = {},
            hasLiveWallpaper = { false },
        )
    }
}
