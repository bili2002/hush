# 28 — Mute, chat rules from the rules screen, automatic sort

## Status
- **Muted** is a third kind of throttle next to "Instantly" and the intervals (`MUTED = -1` in
  `focus/FocusRules.kt`), for a group or a chat. While Focus is on, a muted notification is
  recorded, hidden, and never put in a digest; History shows it with a "Muted" chip.
  `FocusPolicy` returns `Decision.MUTE`; `NotificationIngest` inserts the row as held, stamps it
  `deliveredAt = NotificationEvent.MUTED_AT` (0) and cancels it. Calls, alarms, navigation and
  ongoing notifications are never muted, same as holding.
- **Chat rules** can be added on "Apps & throttles": "Add" in the chat-rules header (app → chat →
  throttle) or "Chat rule…" in a held app's menu (chat → throttle). The sheet
  (`ui/components/PickerSheet.kt`) and the throttle dialog (`ui/rules/ChatThrottle.kt`) are the
  ones History uses.
- **Auto-sort apps** (`Settings.withAutoSorted`) holds every installed or seen app that
  `Presets.suggest` can place, in its preset group, and says how many it added. It runs by itself
  on a first visit when nothing is held. Presets gained "Shopping" and "Games & media".
- With nothing held, the Focus screen's "No apps held" row now opens "Apps & throttles" (which
  sorts apps in) instead of the bare app picker.

## Decision
- **Mute does not break "never lose".** The row is persisted before the notification is hidden,
  and it stays in History and syncs like any other. What the owner gives up, by choosing "Muted",
  is being told. If the mute stamp cannot be written the row is an ordinary held row and the next
  digest delivers it. A row that was already held when its group or chat became muted is also
  delivered once: muting applies to what arrives after the choice.
- **No new column for "muted".** A muted row is finished the moment it is recorded, so it carries
  a delivery stamp like every released row and nothing has to learn a new state; the stamp value
  0 is what tells it apart. Digest views select `delivered_at >= since` with a real `since`, so
  they never list it.
- **SMS apps are never sorted in automatically** (`Presets.neverAutomatic`): login codes arrive
  there. Adding one by hand still works. Apps with no suggestion (banking, authenticators, system
  apps) are untouched by the sort.
- The automatic sort only adds. It never moves an app the owner placed and never changes an
  interval; a preset group it needs that was deleted is created again.
