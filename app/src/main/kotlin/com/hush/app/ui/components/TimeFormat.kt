package com.hush.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hush.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
private val dayWithYearFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")

/** "14:00" for [millis] in [zone]. */
fun formatClock(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().format(clockFormat)

/** Calendar day of [millis] in [zone]; what History groups rows by. */
fun dayOf(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** "Today" / "Yesterday" / "Tue, 5 Nov" (year appended when it differs from [today]'s). */
@Composable
fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.history_today)
    today.minusDays(1) -> stringResource(R.string.history_yesterday)
    else -> if (date.year == today.year) date.format(dayFormat) else date.format(dayWithYearFormat)
}

/** "just now" / "2 min ago" / "3 h ago" / "2 d ago" for an elapsed duration. */
@Composable
fun relativeAgo(elapsedMillis: Long): String {
    val minutes = (elapsedMillis / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.focus_ago_just_now)
        minutes < 60 -> stringResource(R.string.focus_ago_minutes, minutes)
        minutes < 60 * 24 -> stringResource(R.string.focus_ago_hours, minutes / 60)
        else -> stringResource(R.string.focus_ago_days, minutes / (60 * 24))
    }
}
