## Status

Listener (#3) landed: `com.hush.app.listener.NotificationCollectorService` (a Hilt
`@AndroidEntryPoint` `NotificationListenerService`, declared under the `<!-- listener -->` manifest
anchor) forwards every callback into `NotificationIngest`, which owns the whole posted / removed /
backfill / connect / disconnect flow. `NotificationMapper` extracts text (title, text, bigText,
subText, textLines, MessagingStyle lines; `bigText` capped at 4 KB) and app labels (cached,
package-name fallback). `NoiseFilters` drops Hush's own notifications, group summaries, ongoing /
foreground-service notifications (unless `logOngoing`) and `excludedPackages`. `DedupeCache`
collapses updates that change no visible text. Tests: mapper extraction, each filter, dedupe, and
the ingest flow against the real repository on an in-memory driver. Device test still owed
(`needs-device-test`): bind + Hilt injection, a real notification reaching the DB, recording while
the app is swiped away.

## Decision

**[2026-11-08] Persist first, cancel second, in one place**
What: `NotificationIngest.onPosted` = map → `FocusGate.decide` → insert (under `NonCancellable`,
in the application scope) → `cancelNotification` only if the insert returned and the decision was
HOLD. An insert failure logs and returns without cancelling; a cancel failure (SecurityException,
listener gone) logs and leaves the row `held`.
Why: "never lose a notification" (house rule). The DB row is the only thing that makes a held
notification recoverable, so hiding it before the row exists is the one order we never allow. A
stuck `held` row is bounded by the Focus issue's max-age guard, so failing to cancel costs the owner
a visible notification, never a lost one.

**[2026-11-08] Deterministic event ids**
What: `id = UUID.nameUUIDFromBytes("$key|$postTime|$eventType")`.
Why: a backfill after rebind, a listener reconnect replaying active notifications, or a server
re-push all become `INSERT OR IGNORE` no-ops instead of duplicates. POSTED and REMOVED for the same
notification get distinct ids.

**[2026-11-08] Dedupe is an in-memory LRU, not a DB lookup**
What: `DedupeCache` (512 keys, process lifetime) remembers the last `(title, text, bigText)` per
notification key; an identical update is not inserted unless the gate says HOLD (a re-posted held
notification must be cancelled again). A removal forgets the key. The repository has no per-key
query and `db/**` is foundation-owned.
Why: the restart case is already harmless: after a process restart the first "duplicate" update
either has the same `postTime` (deterministic id → no-op) or a new `postTime`, in which case one
extra row is recorded once. Not worth a schema query in a sibling's files.

**[2026-11-08] Backfill bypasses the gate and is stored `held = false`**
What: `onListenerConnected` reads `getActiveNotifications()` (try/catch, on the binder thread) and
inserts each through the mapper with `held = false`, never consulting `FocusGate`.
Why: those notifications are already on screen. Holding them now would cancel something the owner
has seen and start a delivery timer for it, which is neither "hold" nor "pass through".

**[2026-11-08] Ingest signatures differ slightly from the issue text**
What: `NotificationMapper.map(sbn, importance, settings)` takes the `Settings` snapshot (filters
need `logOngoing` / `excludedPackages`; the ingest reads it once per event via
`settings.current()`), and `NotificationIngest.onPosted(sbn, importance, cancel)` takes the cancel
callback because `cancelNotification` only exists on the service.
Why: keeps the service free of logic and the ingest fully unit-testable without a service.
