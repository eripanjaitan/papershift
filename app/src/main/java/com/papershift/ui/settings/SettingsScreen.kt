package com.papershift.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.papershift.R
import com.papershift.core.Source
import com.papershift.ui.UiState
import com.papershift.ui.components.SectionCard

/** PRD §5.2. Stateless: values come from [state], changes go through the callbacks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onPauseOnBatterySaver: (Boolean) -> Unit,
    onPauseOnLowBattery: (Boolean) -> Unit,
    onUnmeteredOnly: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionCard(title = stringResource(R.string.section_battery)) {
                InfoRow(
                    stringResource(
                        if (state.batteryUnrestricted) R.string.battery_usage_unrestricted else R.string.battery_usage_optimized,
                    ),
                )
                Text(
                    text = stringResource(R.string.manage_in_system_settings),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                InfoRow(stringResource(R.string.oem_helper), secondary = true)
                SwitchRow(
                    title = stringResource(R.string.pause_battery_saver),
                    subtitle = null,
                    checked = settings.pauseOnBatterySaver,
                    onChange = onPauseOnBatterySaver,
                )
                SwitchRow(
                    title = stringResource(R.string.pause_low_battery),
                    subtitle = stringResource(R.string.pause_low_battery_sub),
                    checked = settings.pauseOnLowBattery,
                    onChange = onPauseOnLowBattery,
                )
            }

            SectionCard(title = stringResource(R.string.section_network)) {
                val isBing = settings.source == Source.Bing
                SwitchRow(
                    title = stringResource(R.string.unmetered_only),
                    subtitle = stringResource(if (isBing) R.string.unmetered_only_sub else R.string.unmetered_only_gallery),
                    checked = settings.unmeteredOnly,
                    onChange = onUnmeteredOnly,
                    enabled = isBing,
                )
                InfoRow(stringResource(R.string.storage_statement), secondary = true)
            }

            SectionCard(title = stringResource(R.string.section_display)) {
                InfoRow(stringResource(R.string.display_note))
            }

            SectionCard(title = stringResource(R.string.section_about)) {
                InfoRow(stringResource(R.string.version_label, versionName))
            }
        }
    }
}

@Composable
private fun InfoRow(text: String, secondary: Boolean = false) {
    Text(
        text = text,
        style = if (secondary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // The row handles clicks, so the switch itself is not separately focusable.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
