package com.papershift.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.papershift.R
import com.papershift.ui.components.SectionCard

/** PRD §5.2. Toggles are local placeholders until settings are stored in DataStore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val unrestricted = remember {
        context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val versionName = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    var pauseOnSaver by rememberSaveable { mutableStateOf(true) }
    var pauseOnLow by rememberSaveable { mutableStateOf(true) }
    var unmeteredOnly by rememberSaveable { mutableStateOf(true) }

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
                        if (unrestricted) R.string.battery_usage_unrestricted else R.string.battery_usage_optimized,
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
                SwitchRow(stringResource(R.string.pause_battery_saver), null, pauseOnSaver) { pauseOnSaver = it }
                SwitchRow(
                    stringResource(R.string.pause_low_battery),
                    stringResource(R.string.pause_low_battery_sub),
                    pauseOnLow,
                ) { pauseOnLow = it }
            }

            SectionCard(title = stringResource(R.string.section_network)) {
                SwitchRow(
                    stringResource(R.string.unmetered_only),
                    stringResource(R.string.unmetered_only_sub),
                    unmeteredOnly,
                ) { unmeteredOnly = it }
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
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
        Switch(checked = checked, onCheckedChange = null)
    }
}
