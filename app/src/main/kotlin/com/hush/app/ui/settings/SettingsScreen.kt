package com.hush.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.ui.HushTheme
import com.hush.app.ui.Routes
import java.time.ZoneId

/**
 * Settings tab. [onOpenPicker] receives a route from [Routes.appPicker]; it defaults to a no-op so the
 * foundation shell compiles without the wiring in `MainActivity`.
 */
@Composable
fun SettingsScreen(
    onOpenPicker: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check listener/battery state when the user comes back from the system settings screens.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun open(action: String) {
        runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    SettingsContent(
        state = state,
        callbacks = SettingsCallbacks(
            onUrlChange = viewModel::onUrlChange,
            onTokenChange = viewModel::onTokenChange,
            onToggleTokenVisible = viewModel::toggleTokenVisible,
            onTestConnection = viewModel::testConnection,
            onSyncNow = viewModel::syncNow,
            onOpenListenerSettings = { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            onOpenBatterySettings = { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
            onOpenHoldList = { onOpenPicker(Routes.appPicker("hold")) },
            onOpenExcludedList = { onOpenPicker(Routes.appPicker("excluded")) },
            onLogOngoingChange = viewModel::setLogOngoing,
        ),
    )
}

/** Every user action on the screen; no-op defaults keep previews short. */
data class SettingsCallbacks(
    val onUrlChange: (String) -> Unit = {},
    val onTokenChange: (String) -> Unit = {},
    val onToggleTokenVisible: () -> Unit = {},
    val onTestConnection: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onOpenListenerSettings: () -> Unit = {},
    val onOpenBatterySettings: () -> Unit = {},
    val onOpenHoldList: () -> Unit = {},
    val onOpenExcludedList: () -> Unit = {},
    val onLogOngoingChange: (Boolean) -> Unit = {},
)

@Composable
fun SettingsContent(state: SettingsUiState, callbacks: SettingsCallbacks, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        ServerCard(state, callbacks)
        ListenerCard(state, callbacks)
        BatteryCard(state, callbacks)
        AppsCard(state, callbacks)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ServerCard(state: SettingsUiState, cb: SettingsCallbacks) {
    SectionCard(stringResource(R.string.settings_server_title)) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = cb.onUrlChange,
            enabled = state.loaded,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_server_url_label)) },
            placeholder = { Text(stringResource(R.string.settings_server_url_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = state.token,
            onValueChange = cb.onTokenChange,
            enabled = state.loaded,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_server_token_label)) },
            singleLine = true,
            visualTransformation = if (state.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = cb.onToggleTokenVisible) {
                    Icon(
                        imageVector = if (state.tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (state.tokenVisible) R.string.settings_server_token_hide else R.string.settings_server_token_show,
                        ),
                    )
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = cb.onTestConnection, enabled = state.loaded && !state.testing) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_server_test))
            }
            FilledTonalButton(onClick = cb.onSyncNow, enabled = state.serverStatus !is ServerStatus.NotConfigured) {
                Text(stringResource(R.string.settings_server_sync_now))
            }
        }
        state.testResult?.let { TestResultLine(it) }
        StatusLine(state.serverStatus)
        if (state.unsyncedCount > 0) {
            Text(
                stringResource(R.string.settings_server_unsynced, state.unsyncedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TestResultLine(result: TestResult) {
    val (text, color) = when (result) {
        TestResult.Ok -> stringResource(R.string.settings_server_test_ok) to okColor()
        TestResult.WrongToken -> stringResource(R.string.settings_server_test_wrong_token) to MaterialTheme.colorScheme.error
        is TestResult.Unreachable ->
            stringResource(R.string.settings_server_test_unreachable, result.cause) to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun StatusLine(status: ServerStatus) {
    val zone = ZoneId.systemDefault()
    val (text, color) = when (status) {
        ServerStatus.NotConfigured ->
            stringResource(R.string.settings_server_status_not_configured) to MaterialTheme.colorScheme.onSurfaceVariant
        ServerStatus.NeverSynced ->
            stringResource(R.string.settings_server_status_never) to MaterialTheme.colorScheme.onSurfaceVariant
        is ServerStatus.Ok ->
            stringResource(R.string.settings_server_status_ok, formatClockTime(status.lastSyncAt, zone)) to okColor()
        is ServerStatus.Error -> {
            val text = if (status.lastSyncAt != null) {
                stringResource(R.string.settings_server_status_error_with_time, formatClockTime(status.lastSyncAt, zone), status.message)
            } else {
                stringResource(R.string.settings_server_status_error, status.message)
            }
            text to MaterialTheme.colorScheme.error
        }
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun ListenerCard(state: SettingsUiState, cb: SettingsCallbacks) {
    SectionCard(stringResource(R.string.settings_listener_title)) {
        val (text, color) = when (val l = state.listener) {
            is ListenerState.Connected ->
                stringResource(R.string.settings_listener_connected, ageText(l.at, state.nowMillis)) to okColor()
            is ListenerState.Disconnected ->
                stringResource(R.string.settings_listener_disconnected, ageText(l.at, state.nowMillis)) to MaterialTheme.colorScheme.error
            ListenerState.NotEnabled ->
                stringResource(R.string.settings_listener_not_enabled) to MaterialTheme.colorScheme.error
            ListenerState.EnabledNeverConnected ->
                stringResource(R.string.settings_listener_enabled_waiting) to MaterialTheme.colorScheme.error
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
        Button(onClick = cb.onOpenListenerSettings) { Text(stringResource(R.string.settings_listener_open)) }
        if (state.listener == ListenerState.NotEnabled) {
            Text(
                stringResource(R.string.settings_listener_sideload_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BatteryCard(state: SettingsUiState, cb: SettingsCallbacks) {
    SectionCard(stringResource(R.string.settings_battery_title)) {
        if (state.batteryExempt) {
            Text(stringResource(R.string.settings_battery_exempt), style = MaterialTheme.typography.bodyLarge, color = okColor())
        } else {
            Text(
                stringResource(R.string.settings_battery_not_exempt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = cb.onOpenBatterySettings) { Text(stringResource(R.string.settings_battery_open)) }
        Text(
            stringResource(R.string.settings_battery_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppsCard(state: SettingsUiState, cb: SettingsCallbacks) {
    SectionCard(stringResource(R.string.settings_apps_title)) {
        NavRow(
            title = stringResource(R.string.settings_apps_hold, state.holdCount),
            subtitle = stringResource(R.string.settings_apps_hold_desc),
            onClick = cb.onOpenHoldList,
        )
        NavRow(
            title = stringResource(R.string.settings_apps_excluded, state.excludedCount),
            subtitle = stringResource(R.string.settings_apps_excluded_desc),
            onClick = cb.onOpenExcludedList,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_apps_log_ongoing)) },
            supportingContent = { Text(stringResource(R.string.settings_apps_log_ongoing_desc)) },
            trailingContent = { Switch(checked = state.logOngoing, onCheckedChange = cb.onLogOngoingChange) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun ageText(at: Long, now: Long): String = when (val age = relativeAge(at, now)) {
    Age.JustNow -> stringResource(R.string.settings_ago_just_now)
    is Age.Minutes -> stringResource(R.string.settings_ago_minutes, age.n)
    is Age.Hours -> stringResource(R.string.settings_ago_hours, age.n)
    is Age.Days -> stringResource(R.string.settings_ago_days, age.n)
}

@Composable
private fun okColor(): Color = if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

// ---- previews (compile-only) ----

@Preview(showBackground = true)
@Composable
private fun SettingsPreviewConfigured() {
    HushTheme {
        SettingsContent(
            state = SettingsUiState(
                loaded = true,
                serverUrl = "https://example.invalid:8443",
                token = "synthetic-token",
                serverStatus = ServerStatus.Ok(lastSyncAt = 1_700_000_000_000L),
                unsyncedCount = 3,
                testResult = TestResult.Ok,
                listener = ListenerState.Connected(at = 1_700_000_000_000L - 120_000L),
                batteryExempt = true,
                holdCount = 2,
                excludedCount = 1,
                nowMillis = 1_700_000_000_000L,
            ),
            callbacks = SettingsCallbacks(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreviewUnconfigured() {
    HushTheme {
        SettingsContent(
            state = SettingsUiState(loaded = true, listener = ListenerState.NotEnabled, batteryExempt = false),
            callbacks = SettingsCallbacks(),
        )
    }
}
