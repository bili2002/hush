package com.hush.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hush.app.R

data class SheetItem(val key: String, val label: String, val count: Long, val iconPackage: String?)

/** Sheet search: case-insensitive substring over the label and the key (package name / conversation id). */
fun filterSheetItems(items: List<SheetItem>, query: String): List<SheetItem> {
    val q = query.trim()
    if (q.isEmpty()) return items
    return items.filter { it.label.contains(q, ignoreCase = true) || it.key.contains(q, ignoreCase = true) }
}

/**
 * A searchable bottom sheet to pick one of [items]. With an [allLabel] it is a filter: an
 * "everything" row on top and the current choice marked. Without one it is a step in a flow: rows
 * are plain buttons. [emptyLabel] shows when there is nothing to list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    title: String,
    allLabel: String?,
    searchHint: String,
    items: List<SheetItem>,
    selectedKey: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    emptyLabel: String = stringResource(R.string.history_sheet_no_matches),
) {
    val choosesOne = allLabel != null
    var search by rememberSaveable { mutableStateOf("") }
    val shown = filterSheetItems(items, search)
    // Fully expanded from the start: half height plus the keyboard would leave no room for the list.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            singleLine = true,
            placeholder = { Text(searchHint) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotEmpty()) {
                    IconButton(onClick = { search = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.history_clear_search))
                    }
                }
            },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (allLabel != null && search.isBlank()) {
                item(key = "all") {
                    SheetRow(label = allLabel, count = null, selected = selectedKey == null, iconPackage = null, onClick = { onPick(null) })
                }
            }
            items(shown, key = { "item-${it.key}" }) { item ->
                SheetRow(
                    label = item.label,
                    count = item.count,
                    selected = item.key == selectedKey,
                    iconPackage = item.iconPackage,
                    onClick = { onPick(item.key) },
                    choosesOne = choosesOne,
                )
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        if (items.isEmpty()) emptyLabel else stringResource(R.string.history_sheet_no_matches),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, count: Long?, selected: Boolean, iconPackage: String?, onClick: () -> Unit, choosesOne: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A filter is one-of-many with a current choice; a step in a flow is just a button.
            .then(
                if (choosesOne) Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                else Modifier.clickable(role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPackage != null) {
            AppIcon(packageName = iconPackage, size = 32.dp)
            Spacer(Modifier.width(16.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Spacer(Modifier.width(12.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
