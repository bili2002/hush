# 20 — History: open the chat/app, 10-row preview, app sheet, chat filter

## Status
History rows now behave like the shade (#20):

- **Tap opens.** `open/NotificationOpener` fires the notification's own `contentIntent` (lands in
  the chat), falls back to the app's launcher activity, and reports `FAILED` when neither exists;
  History shows "Couldn't open <app> …" with Dismiss. The chevron at the end of the row unfolds the
  full text plus "Only this chat" / "Only this app".
- **Conversation identity.** `notification_event` gained `conversation_id` / `conversation_title`
  (schema v2, `sqldelight/migrations/1.sqm`, covered by `MigrationTest`). The mapper fills them
  from the notification's shortcut id, else from a MessagingStyle title; everything else stays
  null. "Same chat" is `NotificationEvent.chatKey` = `COALESCE(conversation_id, title)` in both
  Kotlin and SQL.
- **10-row preview.** The first view of any mode (browse, search, app, chat, digest batch) is the
  10 newest rows and a "Show all N" button; unrolled it pages by 200 as before. Changing the mode
  returns to the preview. REMOVED rows are filtered in SQL (`selectHistory` / `countHistory`),
  which replaced `search` and the in-memory filter.
- **Filters.** The per-app chip row is gone. One chip opens a searchable bottom sheet of apps
  (icon, count, most recent first); with an app chosen a second chip does the same for its chats
  (`selectChats`).
- **Sync.** `NotificationDto` carries `conversationId` / `conversationTitle`. The deployed server
  ignores unknown keys, so sync keeps working; it stores them once #22 is deployed.

Needs device test: opening a chat from a visible Hush on Android 14+ (the background-activity
opt-in), which apps publish shortcut ids, sheet ergonomics with the keyboard up.

Known limits: rows captured before v2 have no conversation id, so for up to 30 days an active
chat can appear twice in the chat sheet (once by id, once by its old title).

## Decision

**[2026-11-18] Tap targets are cached in memory only**
What: `listener/TapTargets` keeps the `contentIntent` of the last 400 recorded notifications (and
the newest one per app + conversation id) in an LRU. Nothing about it is written to the DB or
synced. When Hush's process is restarted the cache is empty and a tap opens the app instead.
Why: a `PendingIntent` is a live system token, not data; it cannot be persisted, and re-creating
a deep link per messaging app would be a per-app guessing game. The listener keeps the process
alive for long stretches, so recent rows (the ones worth opening) normally have their target.
The alternative that keeps targets forever is `snoozeNotification` instead of cancel, which the
Focus decision of 2026-11-07 ruled out.

**[2026-11-18] A row tap opens; the chevron expands**
What: the row body opens the chat/app, a trailing chevron toggles the full text. Previously the
tap expanded.
Why: the owner asked for held notifications to be "clickable" like the originals. Mirroring the
shade (body opens, chevron expands) needs no explanation; the expanded row says whether the tap
lands in the conversation or only in the app.

**[2026-11-18] Chat key falls back to the title**
What: grouping and filtering use `COALESCE(conversation_id, title)`, not `conversation_id` alone.
Tap targets are shared across rows by `conversation_id` only.
Why: the fallback makes "one sender / one thread" work for apps that publish neither a shortcut
nor a MessagingStyle, and for pre-v2 rows. Sharing a tap target by title would open the wrong
thing when two unrelated notifications have the same title, so that stays strict.

**[2026-11-18] The preview is a query limit, not a UI fold**
What: collapsed = `LIMIT 10` plus a `COUNT(*)` with the same WHERE; "Show all" switches the limit
to the 200-row page.
Why: the count on the button is exact, and the collapsed view never loads rows it will not show.
