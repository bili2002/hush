package com.hush.app.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hush.app.db.AppSummary
import com.hush.app.db.EventType
import com.hush.app.db.NotificationEvent
import com.hush.app.ui.HushTheme
import java.time.LocalDate
import java.time.ZoneOffset

private val zone = ZoneOffset.UTC
private val today: LocalDate = LocalDate.of(2024, 1, 10)
private const val DAY = 86_400_000L
private val todayStart = today.toEpochDay() * DAY

private fun sample(id: String, pkg: String, label: String, at: Long, held: Boolean = false, deliveredAt: Long? = null) = NotificationEvent(
    id = id, key = "0|$pkg|$id|null|10000", packageName = pkg, appLabel = label, postTime = at,
    title = "Sample title $id", text = "Sample text for $id that is long enough to wrap onto a second line in the preview.",
    bigText = "Sample big text $id with the whole message body.", subText = null, channelId = "default", category = null,
    flags = 0, importance = 3, event = EventType.POSTED, removedReason = null, held = held, deliveredAt = deliveredAt,
)

private val apps = listOf(
    AppSummary("com.example.alpha", "Alpha", 3, todayStart + 10 * 3_600_000L),
    AppSummary("com.example.beta", "Beta", 1, todayStart - DAY + 9 * 3_600_000L),
)

@Preview(showBackground = true, name = "History — list")
@Composable
private fun HistoryListPreview() {
    HushTheme {
        HistoryContent(
            state = HistoryUiState(
                loading = false,
                days = listOf(
                    DayGroup(
                        today,
                        listOf(
                            sample("a3", "com.example.alpha", "Alpha", todayStart + 10 * 3_600_000L, held = true, deliveredAt = todayStart + 14 * 3_600_000L),
                            sample("a2", "com.example.alpha", "Alpha", todayStart + 9 * 3_600_000L, held = true),
                        ),
                    ),
                    DayGroup(today.minusDays(1), listOf(sample("b1", "com.example.beta", "Beta", todayStart - DAY + 9 * 3_600_000L))),
                ),
                apps = apps,
                expandedId = "a3",
            ),
            query = "",
            zone = zone,
            today = today,
            callbacks = HistoryCallbacks(),
        )
    }
}

@Preview(showBackground = true, name = "History — digest batch")
@Composable
private fun HistoryBatchPreview() {
    HushTheme {
        HistoryContent(
            state = HistoryUiState(
                loading = false,
                mode = HistoryMode.HeldBatch(deliveredAt = todayStart + 14 * 3_600_000L),
                days = listOf(
                    DayGroup(today, listOf(sample("a3", "com.example.alpha", "Alpha", todayStart + 10 * 3_600_000L, held = true, deliveredAt = todayStart + 14 * 3_600_000L))),
                ),
                apps = apps,
            ),
            query = "",
            zone = zone,
            today = today,
            callbacks = HistoryCallbacks(),
        )
    }
}

@Preview(showBackground = true, name = "History — listener off")
@Composable
private fun HistoryEmptyPreview() {
    HushTheme {
        HistoryContent(
            state = HistoryUiState(loading = false, empty = EmptyKind.LISTENER_OFF, listenerEnabled = false),
            query = "",
            zone = zone,
            today = today,
            callbacks = HistoryCallbacks(),
        )
    }
}

@Preview(showBackground = true, name = "History — no matches")
@Composable
private fun HistoryNoMatchesPreview() {
    HushTheme {
        HistoryContent(
            state = HistoryUiState(loading = false, mode = HistoryMode.Browse(query = "zzz"), apps = apps, empty = EmptyKind.NO_MATCHES),
            query = "zzz",
            zone = zone,
            today = today,
            callbacks = HistoryCallbacks(),
        )
    }
}
