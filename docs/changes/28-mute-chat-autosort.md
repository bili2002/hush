# 28 — Mute, chat rules from the rules screen, automatic sort

## Status
- **Muted** is a third kind of throttle next to "Instantly" and the intervals (`MUTED = -1` in
  `focus/FocusRules.kt`), for a group or a chat. While Focus is on, a muted notification is
  recorded, hidden, and never put in a digest; History shows it with a "Muted" chip.
  `FocusPolicy` returns `Decision.MUTE`; `NotificationIngest` inserts the row as held and already
  stamped `deliveredAt = NotificationEvent.MUTED_AT` (0), in one statement, then cancels it. Calls, alarms, navigation and
  ongoing notifications are never muted, same as holding.
- **Chat rules** can be added on "Apps & throttles": "Add" in the chat-rules header (app → chat →
  throttle) or "Chat rule…" in a held app's menu (chat → throttle). The sheet
  (`ui/components/PickerSheet.kt`) and the throttle dialog (`ui/rules/ChatThrottle.kt`) are the
  ones History uses.
- **Auto-sort apps** (`Settings.withAutoSorted`) holds every installed or seen app that
  `Presets.suggest` can place, in its preset group, and says how many it added. It runs by itself
  on a first visit when nothing is held and Focus is off. Presets gained "Shopping" and
  "Games & media".
- With nothing held, the Focus screen's "No apps held" row now opens "Apps & throttles" (add
  apps, auto-sort) instead of the bare app picker.
- `DigestLink.parse` rejects a non-positive time: the activity is exported, and 0 would select
  the muted rows.

## Decision
- **Mute is an explicit exception to "anything held has a maximum age".** A muted row is hidden
  and never released; that is what the owner asked for by choosing "Muted". It does not break
  "never lose": the row is persisted before the notification is hidden (a failed insert leaves it
  visible), stays in History and syncs like any other. A row that was already held when its group
  or chat became muted is delivered once: muting applies to what arrives after the choice.
- **No new column for "muted".** A muted row is finished the moment it is recorded, so it carries
  a delivery stamp like every released row and nothing has to learn a new state; the stamp value
  0 is what tells it apart. Digest views select `delivered_at >= since` with a real `since`, so
  they never list it.
- **The automatic sort stays away from anything time-critical.** SMS apps are never sorted in
  (`Presets.neverAutomatic` plus the phone's default SMS app): login codes arrive there. The OS
  category "productivity" is not a reason either when the owner did not pick the app, because
  sign-in approval apps, calendars and reminders declare it; only the known mail and work-chat
  packages go to "Email & work" by themselves. Adding any of these by hand still works.
- The automatic sort only adds. It never moves an app the owner placed and never changes an
  interval; a preset group it needs that was deleted is created again.
