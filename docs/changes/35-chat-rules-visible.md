# 35 — Chat rules in sight, default interval can be "Instantly"

## Status
- "Apps & throttles" lists the profile's chat rules in one card directly under the default
  interval (header with "Add", one row per rule with its throttle chip). They used to follow the
  groups, out of sight once the groups held apps. A held app's row notes how many chat rules it
  has.
- History: on an unfolded notification the button that opens the chat's throttle reads
  "Chat rule · <throttle>" when the chat has a rule (`HistoryUiState.chatRuleMinutes`), and
  "Throttle this chat" when it does not.
- The default interval offers "Instantly" (`slotMinutes = INSTANT`). Held apps are then only
  held through a group or chat that has a throttle of its own. `DeliveryScheduler.nextBoundary`
  falls back to an hourly retry when no interval is in use and the default is "Instantly".

## Decision
- The default cannot be "Muted": it would hide every held app that has no throttle of its own,
  and with the automatic sort that can be most of the phone. A group can be muted; the default
  cannot.
- Chat rules shown are those of the profile the screen is for; History shows the active
  profile's, which is the one "Throttle this chat" edits.
