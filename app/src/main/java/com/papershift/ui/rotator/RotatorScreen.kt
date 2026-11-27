package com.papershift.ui.rotator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.papershift.R
import com.papershift.ui.components.SectionCard
import com.papershift.ui.theme.PaperShiftTheme

enum class Source { Gallery, Bing }

/**
 * PRD §5.1. UI skeleton only: state is local and not persisted, and nothing is
 * scheduled yet. Wiring to DataStore and WorkManager comes with the rotation feature.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RotatorScreen(onOpenSettings: () -> Unit) {
    var source by rememberSaveable { mutableStateOf(Source.Gallery) }
    var rotating by rememberSaveable { mutableStateOf(false) }

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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Source.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = source == option,
                        onClick = { source = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Source.entries.size),
                    ) {
                        Text(
                            stringResource(
                                if (option == Source.Gallery) R.string.source_gallery else R.string.source_bing,
                            ),
                        )
                    }
                }
            }

            if (source == Source.Gallery) {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.folder_none),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { /* SAF picker comes with the gallery feature */ }) {
                            Text(stringResource(R.string.folder_choose))
                        }
                    }
                }
            }

            Button(onClick = { rotating = !rotating }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (rotating) R.string.pause_rotation else R.string.start_rotation))
            }
            Text(
                text = stringResource(R.string.start_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { /* comes with the rotation feature */ },
                enabled = rotating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.shift_now))
            }
            Text(
                text = stringResource(if (rotating) R.string.status_scheduled else R.string.status_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Preview
@Composable
private fun RotatorScreenPreview() {
    PaperShiftTheme { RotatorScreen(onOpenSettings = {}) }
}
