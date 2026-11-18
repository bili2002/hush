package com.hush.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.hush.app.R
import com.hush.app.focus.INSTANT
import com.hush.app.focus.MUTED

/** "15 min" / "1 h" / "1 h 30 min"; [INSTANT] reads "Instantly", [MUTED] "Muted". */
@Composable
fun throttleLabel(minutes: Int): String = when {
    minutes == MUTED -> stringResource(R.string.throttle_muted)
    minutes <= INSTANT -> stringResource(R.string.throttle_instant)
    minutes < 60 -> stringResource(R.string.throttle_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.throttle_hours, minutes / 60)
    else -> stringResource(R.string.throttle_hours_minutes, minutes / 60, minutes % 60)
}

/**
 * "every 15 min" / "instantly" / "muted", for lines that describe what happens to a notification.
 * "Instantly" and not "not held": an app on the hold list can be on it too (its group, or the default).
 */
@Composable
fun throttleEvery(minutes: Int): String = when {
    minutes == MUTED -> stringResource(R.string.throttle_muted_lower)
    minutes <= INSTANT -> stringResource(R.string.throttle_instantly_lower)
    else -> stringResource(R.string.throttle_every, throttleLabel(minutes))
}
