package com.hush.app.ui.schedule

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.focus.ScheduleEntry
import com.hush.app.ui.HushTheme
import com.hush.app.ui.components.HushCard
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class ScheduleCallbacks(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onProfile: (String, String) -> Unit = { _, _ -> },
    val onToggleDay: (String, Int) -> Unit = { _, _ -> },
    val onStart: (String, Int) -> Unit = { _, _ -> },
    val onEnd: (String, Int) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

@Composable
fun ScheduleScreen(onBack: () -> Unit, vm: ScheduleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    ScheduleContent(
        state = state,
        callbacks = ScheduleCallbacks(
            onBack = onBack,
            onAdd = vm::add,
            onProfile = vm::setProfile,
            onToggleDay = vm::toggleDay,
            onStart = vm::setStart,
            onEnd = vm::setEnd,
            onRemove = vm::remove,
            onDismissError = vm::dismissError,
        ),
    )
}

/** Which time of which entry the open time picker edits; saved as "<s|e><entry id>". */
private data class TimeTarget(val entryId: String, val start: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleContent(state: ScheduleUiState, callbacks: ScheduleCallbacks) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val target = picking?.let { TimeTarget(it.drop(1), it.startsWith("s")) }
    // The shell's Scaffold already consumed the status-bar inset; zero both so the bar is not padded twice.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(stringResource(R.string.schedule_title)) },
                navigationIcon = {
                    IconButton(onClick = callbacks.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.apps_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { message ->
                item(key = "error") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.rules_error, message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = callbacks.onDismissError) { Text(stringResource(R.string.focus_dismiss)) }
                    }
                }
            }
            items(state.entries, key = { it.id }) { entry ->
                EntryCard(
                    entry = entry,
                    profiles = state.profiles,
                    callbacks = callbacks,
                    onPickStart = { picking = "s${entry.id}" },
                    onPickEnd = { picking = "e${entry.id}" },
                )
            }
            if (!state.loading) {
                item(key = "add") { Button(onClick = callbacks.onAdd) { Text(stringResource(R.string.schedule_add)) } }
            }
        }
    }

    val entry = target?.let { t -> state.entries.firstOrNull { it.id == t.entryId } }
    if (target != null && entry != null) {
        TimeDialog(
            initial = if (target.start) entry.start else entry.end,
            onConfirm = { minutes ->
                if (target.start) callbacks.onStart(entry.id, minutes) else callbacks.onEnd(entry.id, minutes)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryCard(
    entry: ScheduleEntry,
    profiles: List<ProfileChoice>,
    callbacks: ScheduleCallbacks,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
) {
    HushCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    ProfileChip(
                        current = profiles.firstOrNull { it.id == entry.profileId }?.name.orEmpty(),
                        profiles = profiles,
                        selectedId = entry.profileId,
                        onPick = { callbacks.onProfile(entry.id, it) },
                    )
                }
                IconButton(onClick = { callbacks.onRemove(entry.id) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.schedule_remove))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val on = day.value in entry.days
                    val name = day.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    FilterChip(
                        selected = on,
                        onClick = { callbacks.onToggleDay(entry.id, day.value) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        // "Mon" is read as the full day name.
                        modifier = Modifier.semantics { contentDescription = name },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Read as "Start 09:00" / "End 17:00": the bare times do not say which is which.
                val startLabel = stringResource(R.string.schedule_start, formatMinutes(entry.start))
                val endLabel = stringResource(R.string.schedule_end, formatMinutes(entry.end))
                AssistChip(onClick = onPickStart, label = { Text(formatMinutes(entry.start)) }, modifier = Modifier.semantics { contentDescription = startLabel })
                Text("–")
                AssistChip(onClick = onPickEnd, label = { Text(formatMinutes(entry.end)) }, modifier = Modifier.semantics { contentDescription = endLabel })
                if (entry.end <= entry.start) {
                    Text(
                        stringResource(R.string.schedule_next_day),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileChip(current: String, profiles: List<ProfileChoice>, selectedId: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    trailingIcon = { if (profile.id == selectedId) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = {
                        open = false
                        onPick(profile.id)
                    },
                    modifier = Modifier.semantics { selected = profile.id == selectedId },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val picker = rememberTimePickerState(
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = picker) },
        confirmButton = { TextButton(onClick = { onConfirm(picker.hour * 60 + picker.minute) }) { Text(stringResource(R.string.rules_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rules_cancel)) } },
    )
}

private val shortTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Minutes of the day as "09:00". */
internal fun formatMinutes(minutes: Int): String = LocalTime.of(minutes / 60 % 24, minutes % 60).format(shortTime)

@Preview(showBackground = true)
@Composable
private fun SchedulePreview() {
    HushTheme {
        ScheduleContent(
            state = ScheduleUiState(
                loading = false,
                entries = listOf(
                    ScheduleEntry("a", "work", setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60),
                    ScheduleEntry("b", "night", setOf(1, 2, 3, 4, 5, 6, 7), 22 * 60, 7 * 60),
                ),
                profiles = listOf(ProfileChoice("work", "Work"), ProfileChoice("night", "Night")),
            ),
            callbacks = ScheduleCallbacks(),
        )
    }
}
