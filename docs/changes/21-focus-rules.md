# 21 — Focus rules: app groups with their own throttle, per-chat overrides

## Status
Focus is no longer "one hold list, one interval" (#21):

- **Model** (`focus/FocusRules.kt`, stored as one JSON preference `focus_rules`): groups
  (`FocusCategory`: name + interval, where "no interval" follows the default and `0` means
  instant / not held), `appCategory` (package → group) and chat rules (`(package, chatKey)` →
  interval). `holdPackages` is still the opt-in list of held apps; an app on it without a group
  uses the default interval (`slotMinutes`).
- **Resolution** (`Settings.throttleMinutes`): chat rule → the held app's group → default. A chat
  rule also applies to an app that is not on the hold list, so one noisy chat can be held while
  the rest of the app shows normally, and one chat of a held app can be set to "Instantly".
- **Engine**: `FocusPolicy` holds iff the resolved interval is > 0. `Deliverer` decides "due" per
  row with that row's interval and delivers only the due rows; `DeliveryScheduler` arms the alarm
  at the earliest boundary of any interval in use. `FocusActions.updateRules` saves a rule edit
  and, while Focus is on, re-runs the non-forced delivery (releases what the edit made due,
  re-arms the alarm).
- **UI**: Focus screen shows a "Held apps" summary card → **Apps & throttles**
  (`ui/rules/`): default interval, one card per group (interval menu, rename, delete; per-app
  "move to" / "stop holding"), uncategorised held apps, "Add apps" (the existing picker; a newly
  held app lands in a suggested group), "New group", and the list of chat rules. The default
  interval picker moved there from the Focus card. History: unfold a row → **Throttle this chat**.
- **Presets**: the first visit to Apps & throttles creates Messaging / Social / Email & work /
  News, all on the default interval, and sorts the already-held apps into them (a short table of
  well-known packages, then the app's OS category). Nothing about what is held or when changes
  until the owner picks an interval.
- Intervals offered: 15 min, 30 min, 1 h, 2 h, 4 h, plus "Instantly".

Not done here (follow-ups): confirm/undo for "Delete group"; searching the server archive by chat
name.

Needs device test: the rules screen menus, digest cadence with two different intervals over an
hour or two, "Throttle this chat" on real messaging apps, battery impact of a 15-minute group.

## Decision

**[2026-11-18] "Due" is per row; a digest no longer takes every held row along**
What: supersedes the 2026-11-08 rule "when anything is due, every held row goes into the one
digest". A non-forced run delivers exactly the rows whose own interval boundary has passed (or
that hit the max age, or that the rules no longer hold). Forced delivery (Deliver now, Focus off)
still flushes everything.
Why: with different intervals, riding along would deliver a 4-hour chat every 15 minutes. The
cost is the old straggler case: a row posted between a boundary and a late alarm now waits for
its own next boundary, which is what its interval says anyway.

**[2026-11-18] A new digest absorbs the previous one if it is still unread**
What: the digest keeps one notification id. When the previous digest is still in the shade, the
new one lists the previous rows too and its link carries the older delivery time; History's
batch view is "held rows delivered at or after that time" (`selectHeldBatch` is now `>=`). Once
the digest is opened or dismissed, the next one starts fresh. The carry-over is read from the
live notification's extras; nothing is persisted for it.
Why: per-group intervals make digests more frequent. Replacing an unread hourly digest with a
15-minute one of two messages would hide the bigger one behind a smaller summary.

**[2026-11-18] The hold list stays; groups are a layer on top**
What: `holdPackages` is unchanged and remains the opt-in set. Groups only add an interval;
deleting a group or its mapping never un-holds an app, it falls back to the default.
Why: opt-in holding is a standing decision (2026-11-07). Keeping the list as the single source of
"is this app held" also means no settings migration and no change for an install that never
opens the rules screen.

**[2026-11-18] Chat rules are keyed by `chatKey`, i.e. conversation id or title**
What: a chat rule matches `NotificationEvent.chatKey` (#20). For apps without conversations the
"chat" is the notification title, e.g. one sender.
Why: one mechanism for messengers and for everything else, and a rule made from an old row
(title-keyed) still works. Known limit: a title-keyed rule stops matching if the app changes
that title.

**[2026-11-18] The alarm follows the shortest interval in use**
What: the one-shot alarm is armed at the earliest upcoming boundary over the intervals of held
apps and holding chat rules, whether or not anything is held right now.
Why: one owner of the alarm, no arming from the listener path. A 15-minute group costs at most
96 inexact wake-ups a day; a group interval that no app uses costs none.

**[2026-11-18] Every hold-rule write goes through `FocusActions.updateRules`**
What: the rules screen, History's chat dialog and the app picker's hold list all call
`updateRules`, which saves and then (Focus on) re-runs the non-forced delivery, under
`NonCancellable`. Only the excluded list still writes settings directly.
Why: the alarm now depends on which intervals are in use, so any write that can change that set
must also re-arm it; otherwise a newly held 15-minute app waits for an alarm set for the old,
longer interval. Non-cancellable because the callers are screens the owner may leave right after
the tap.

**[2026-11-18] With Focus off, a non-forced run delivers everything still held**
What: per-row "due" applies only while Focus is on.
Why: rows are only left held with Focus off when the flush was blocked (notifications denied).
Once posting works again there is nothing to throttle for; they should not sit out a 4-hour
interval.

**[2026-11-18] The rules blob is written only when the rules change**
What: `DataStoreSettingsStore.update` leaves `focus_rules` untouched unless the edit changed
`Settings.rules`.
Why: settings are rewritten constantly (listener and sync stamps). A blob this build cannot read
(corrupt, or from a newer build after a downgrade) reads as "no rules" but must not be replaced
by that reading on the next unrelated write.

**[2026-11-18] Overrides the IDEAS "refused" entry for VIP / per-contact rules**
What: per-chat throttles exist, as an interval per chat only; still no keyword filters or rules
engine.
Why: the owner asked for chat-specific timeouts.
