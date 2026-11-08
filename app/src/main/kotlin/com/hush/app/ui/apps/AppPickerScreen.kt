package com.hush.app.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hush.app.R
import com.hush.app.ui.HushTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Route `apps/{list}`; [list] is `"hold"` or `"excluded"` (see [com.hush.app.ui.Routes.appPicker]).
 * The ViewModel reads [list] from its `SavedStateHandle`; the parameter stays for the shell's call site.
 * [onBack] defaults to a no-op so the foundation shell compiles without the wiring.
 */
@Composable
fun AppPickerScreen(
    list: String,
    onBack: () -> Unit = {},
    viewModel: AppPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppPickerContent(
        state = state,
        onQueryChange = viewModel::setQuery,
        onCheckedChange = viewModel::setChecked,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerContent(
    state: AppPickerUiState,
    onQueryChange: (String) -> Unit,
    onCheckedChange: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val title = when (state.list) {
        PickerList.HOLD -> stringResource(R.string.apps_title_hold)
        PickerList.EXCLUDED -> stringResource(R.string.apps_title_excluded)
    }
    val subtitle = when (state.list) {
        PickerList.HOLD -> stringResource(R.string.apps_subtitle_hold)
        PickerList.EXCLUDED -> stringResource(R.string.apps_subtitle_excluded)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            "$subtitle · ${stringResource(R.string.apps_selected_count, state.selectedCount)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.apps_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.apps_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.apps_clear_search))
                        }
                    }
                },
                singleLine = true,
            )
            state.loadError?.let {
                Text(
                    stringResource(R.string.apps_load_error, it),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            when {
                state.loading -> CenteredMessage {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.apps_loading), style = MaterialTheme.typography.bodyMedium)
                }
                state.apps.isEmpty() -> CenteredMessage {
                    Text(
                        stringResource(if (state.query.isBlank()) R.string.apps_empty else R.string.apps_no_matches),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.apps, key = { it.packageName }) { row ->
                        AppRowItem(row) { onCheckedChange(row.packageName, !row.checked) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun AppRowItem(row: AppRow, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppIcon(packageName = row.packageName, label = row.label)
        Column(modifier = Modifier.weight(1f)) {
            Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.label != row.packageName) {
                Text(
                    row.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Checkbox(checked = row.checked, onCheckedChange = { onToggle() })
    }
}

/**
 * Launcher icon loaded lazily off the main thread; falls back to a letter avatar when the package is
 * gone (seen in history, since uninstalled) or in previews. Private to the picker on purpose: the
 * shared components package belongs to Screens A.
 */
@Composable
private fun AppIcon(packageName: String, label: String) {
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        if (inspection) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
        }
    }
    val icon = bitmap
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private const val ICON_PX = 96

// ---- previews (compile-only) ----

@Preview(showBackground = true)
@Composable
private fun AppPickerPreview() {
    HushTheme {
        AppPickerContent(
            state = AppPickerUiState(
                list = PickerList.HOLD,
                loading = false,
                apps = listOf(
                    AppRow("com.example.photos", "Photos", checked = true),
                    AppRow("com.example.alpha", "Alpha", checked = false),
                    AppRow("com.example.seenonly", "com.example.seenonly", checked = false),
                ),
                selectedCount = 1,
            ),
            onQueryChange = {},
            onCheckedChange = { _, _ -> },
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun AppPickerLoadingPreview() {
    HushTheme {
        AppPickerContent(
            state = AppPickerUiState(list = PickerList.EXCLUDED, loading = true),
            onQueryChange = {},
            onCheckedChange = { _, _ -> },
            onBack = {},
        )
    }
}
