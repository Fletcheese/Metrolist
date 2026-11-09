/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.DEFAULT_QUEUE_SYNC_URL
import com.metrolist.music.constants.QueueSyncDeviceNameKey
import com.metrolist.music.constants.QueueSyncEnabledKey
import com.metrolist.music.constants.QueueSyncLastSuccessKey
import com.metrolist.music.constants.QueueSyncStatusKey
import com.metrolist.music.constants.QueueSyncTokenKey
import com.metrolist.music.constants.QueueSyncUrlKey
import com.metrolist.music.playback.queue.sync.QueueSyncRepository
import com.metrolist.music.playback.queue.sync.systemDeviceName
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class QueueSyncSettingsViewModel @Inject constructor(
    val repository: QueueSyncRepository,
) : ViewModel()

private enum class SyncField { URL, TOKEN, DEVICE }

/** FletchTunes: Settings > Queue sync. The server, its token, this device's name and how the last sync went. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSyncSettings(
    navController: NavController,
    viewModel: QueueSyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val (enabled, onEnabledChange) = rememberPreference(QueueSyncEnabledKey, defaultValue = true)
    val (url, onUrlChange) = rememberPreference(QueueSyncUrlKey, defaultValue = DEFAULT_QUEUE_SYNC_URL)
    val (token, onTokenChange) = rememberPreference(QueueSyncTokenKey, defaultValue = "")
    val (deviceName, onDeviceNameChange) = rememberPreference(QueueSyncDeviceNameKey, defaultValue = "")
    val status by rememberPreference(QueueSyncStatusKey, defaultValue = "")
    val lastSuccess by rememberPreference(QueueSyncLastSuccessKey, defaultValue = 0L)
    val systemName = remember { systemDeviceName(context) }
    var editing by remember { mutableStateOf<SyncField?>(null) }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Material3SettingsGroup(
            title = stringResource(R.string.queue_sync),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.sync),
                    title = { Text(stringResource(R.string.queue_sync_enable)) },
                    description = { Text(stringResource(R.string.queue_sync_enable_desc)) },
                    trailingContent = {
                        Switch(checked = enabled, onCheckedChange = {
                            onEnabledChange(it)
                            viewModel.repository.syncNow()
                        })
                    },
                    onClick = {
                        onEnabledChange(!enabled)
                        viewModel.repository.syncNow()
                    },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.cloud),
                    title = { Text(stringResource(R.string.queue_sync_server)) },
                    description = { Text(url) },
                    onClick = { editing = SyncField.URL },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.key),
                    title = { Text(stringResource(R.string.queue_sync_token)) },
                    description = {
                        Text(
                            if (token.isBlank()) stringResource(R.string.queue_sync_token_missing)
                            else stringResource(R.string.queue_sync_token_set, token.takeLast(4)),
                        )
                    },
                    onClick = { editing = SyncField.TOKEN },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.edit),
                    title = { Text(stringResource(R.string.queue_sync_device_name)) },
                    description = { Text(deviceName.ifBlank { systemName }) },
                    onClick = { editing = SyncField.DEVICE },
                ),
            ),
        )

        Spacer(Modifier.height(16.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.queue_sync_status),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.refresh),
                    title = { Text(stringResource(R.string.queue_sync_now)) },
                    description = {
                        Text(
                            buildString {
                                append(status.ifBlank { stringResource(R.string.queue_sync_never) })
                                if (lastSuccess > 0) {
                                    append("\n")
                                    append(
                                        stringResource(
                                            R.string.queue_sync_last_success,
                                            DateUtils.getRelativeTimeSpanString(lastSuccess).toString(),
                                        ),
                                    )
                                }
                            },
                        )
                    },
                    onClick = { viewModel.repository.syncNow() },
                ),
            ),
        )
    }

    editing?.let { field ->
        val initial = when (field) {
            SyncField.URL -> url
            SyncField.TOKEN -> token
            SyncField.DEVICE -> deviceName.ifBlank { systemName }
        }
        TextFieldDialog(
            title = {
                Text(
                    stringResource(
                        when (field) {
                            SyncField.URL -> R.string.queue_sync_server
                            SyncField.TOKEN -> R.string.queue_sync_token
                            SyncField.DEVICE -> R.string.queue_sync_device_name
                        },
                    ),
                )
            },
            initialTextFieldValue = TextFieldValue(initial, TextRange(initial.length)),
            isInputValid = { field != SyncField.URL || it.trim().startsWith("https://") },
            onDone = { value ->
                when (field) {
                    SyncField.URL -> onUrlChange(value.trim())
                    SyncField.TOKEN -> onTokenChange(value.trim())
                    SyncField.DEVICE -> onDeviceNameChange(value.trim().takeUnless { it == systemName }.orEmpty())
                }
                viewModel.repository.syncNow()
            },
            onDismiss = { editing = null },
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.queue_sync)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
