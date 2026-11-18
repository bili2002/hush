package com.hush.app.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.hush.app.focus.CategoryGroup
import com.hush.app.focus.DeliverResult
import com.hush.app.focus.HeldApp
import com.hush.app.ui.HushTheme
import java.time.ZoneOffset

private val zone = ZoneOffset.UTC

@Preview(showBackground = true, name = "Focus — first run, gated")
@Composable
private fun FocusFirstRunPreview() {
    HushTheme {
        FocusContent(
            state = FocusUiState(
                loading = false,
                enabled = false,
                gateReasons = listOf(GateReason.NO_LISTENER_ACCESS, GateReason.EMPTY_HOLD_LIST),
                checklist = Checklist(listenerAccess = false, postPermission = true, batteryExempt = false, holdListNonEmpty = false),
                listener = ListenerHealth.Disconnected,
            ),
            zone = zone,
            callbacks = FocusCallbacks(),
        )
    }
}

@Preview(showBackground = true, name = "Focus — on, with held")
@Composable
private fun FocusOnPreview() {
    HushTheme {
        FocusContent(
            state = FocusUiState(
                loading = false,
                enabled = true,
                slotMinutes = 60,
                heldCount = 7,
                nextSlotAt = 14 * 3_600_000L,
                groups = listOf(
                    CategoryGroup("messaging", "Messaging", 15, 15, listOf(HeldApp("com.example.alpha", "Alpha"))),
                    CategoryGroup(null, null, null, 60, listOf(HeldApp("com.example.beta", "Beta"))),
                ),
                chatRuleCount = 2,
                listener = ListenerHealth.Connected(agoMillis = 2 * 60_000L),
                deliverResult = DeliverResult(7),
            ),
            zone = zone,
            callbacks = FocusCallbacks(),
        )
    }
}

@Preview(showBackground = true, name = "Focus — error")
@Composable
private fun FocusErrorPreview() {
    HushTheme {
        FocusContent(
            state = FocusUiState(
                loading = false,
                enabled = true,
                heldCount = 1,
                nextSlotAt = 15 * 3_600_000L,
                groups = listOf(CategoryGroup(null, null, null, 60, listOf(HeldApp("com.example.alpha", "Alpha")))),
                lastFocusError = "Digest channel is blocked by the system",
                listener = ListenerHealth.Connected(agoMillis = 0),
                deliverResult = DeliverResult(0, "No digest channel"),
            ),
            zone = zone,
            callbacks = FocusCallbacks(),
        )
    }
}
