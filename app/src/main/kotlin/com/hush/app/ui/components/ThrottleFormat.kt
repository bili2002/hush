package com.hush.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hush.app.R
import com.hush.app.focus.INSTANT

/** "15 min" / "1 h" / "1 h 30 min"; [INSTANT] reads "Instantly". */
@Composable
fun throttleLabel(minutes: Int): String = when {
    minutes <= INSTANT -> stringResource(R.string.throttle_instant)
    minutes < 60 -> stringResource(R.string.throttle_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.throttle_hours, minutes / 60)
    else -> stringResource(R.string.throttle_hours_minutes, minutes / 60, minutes % 60)
}

/** "every 15 min" / "not held", for sentences that describe what happens to a notification. */
@Composable
fun throttleEvery(minutes: Int): String =
    if (minutes <= INSTANT) stringResource(R.string.throttle_not_held) else stringResource(R.string.throttle_every, throttleLabel(minutes))
