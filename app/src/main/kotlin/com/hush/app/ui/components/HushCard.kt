package com.hush.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's one card: the page colour with a visible outline. The Material default (a pale grey
 * fill and no edge) was hard to tell from the page on the phone.
 */
@Composable
fun HushCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    if (onClick == null) {
        OutlinedCard(modifier = modifier, colors = colors, border = border, content = content)
    } else {
        OutlinedCard(onClick = onClick, modifier = modifier, colors = colors, border = border, content = content)
    }
}
