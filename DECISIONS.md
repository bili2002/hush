# Decisions

Format per entry:
**[YYYY-MM-DD] Decision title**
What: what we decided
Why: the reasoning / constraint that drove it

---

**[2026-11-07] Native Kotlin + Compose, single app module, tracker-app's version catalog**
What: `:app` is a single-module native Android app; `:server` is Ktor 3. Versions copied verbatim
from tracker-app (AGP 8.7.2, Kotlin 2.1.0, Compose BOM 2024.12.01, Hilt, SQLDelight 2.0.2,
WorkManager 2.9.1, Ktor 3.0.1).
Why: a NotificationListenerService is native-only, so Capacitor (spotify-wrapper's stack) buys
nothing here. The catalog is proven on serverche headless with a warm Gradle cache, which is what
lets parallel AI workers build without toolchain surprises. No KMP: one platform.

**[2026-11-07] Focus mode = cancel on arrival + one digest notification on a schedule**
What: while Focus is on, a notification from an app on the *hold list* is persisted as HELD and
then cancelled via the listener. A scheduled delivery (hourly by default, configurable) posts ONE
digest notification from Hush ("N held notifications from Instagram, WhatsApp…") that opens the
History screen filtered to the held items; "Deliver now" is always available. Originals are not
re-posted.
Why: the owner's choice at the scope gate over `snoozeNotification` (which would re-post the
originals intact but flickers and is OS-owned) — a single digest is the "everything at once"
experience they asked for, and it keeps delivery fully in our DB-backed state machine
(HELD → DELIVERED), which is what makes "never lose a notification" provable. Trade-off accepted:
the originals' tap actions/replies are gone; tapping a digest row opens the app's launcher.

**[2026-11-07] Hold list is opt-in (hold only chosen apps)**
What: Focus mode only holds apps the owner put on the hold list. Everything else shows normally.
Calls, alarms, ongoing/foreground/media notifications and Hush itself are never held, regardless.
Why: owner's choice. Opt-in can never swallow something important by default; the noisy apps are
few and known (Instagram…).

**[2026-11-07] Local-first archive with idempotent push to a self-hosted Ktor server**
What: the phone's SQLDelight DB is the source of truth; rows carry a client-generated UUID and a
`synced_at` stamp. A WorkManager job pushes unsynced rows in batches to `POST /notifications`
(server does `INSERT OR IGNORE`). Phone keeps 30 days; server keeps forever. Server = one Docker
container, one SQLite file (WAL) under a bind-mounted `/data`, FTS5 for search, a static search
page.
Why: same shape as tracker-server, which the owner already operates (deploy script, env-file
secret, loopback publish + `tailscale serve` TLS front). Idempotent IDs make retries and
restores self-healing. Single SQLite file = trivial backup.

**[2026-11-07] Bearer token over tailnet TLS; no secret in the repo or the APK**
What: `HUSH_TOKEN` env on the server (fail closed: 503 when unset, 401 on mismatch,
constant-time compare). The phone stores URL + token in DataStore, typed once in Settings.
Container publishes on 127.0.0.1 only; `tailscale serve --https=8443` fronts it.
Why: public repo; a notification archive is among the most sensitive data the owner has.
Tailnet membership alone is not authorization (tracker-app DECISIONS, #172/#383).

**[2026-11-07] Scheduling: inexact AlarmManager window, no exact-alarm permission**
What: the hourly delivery uses `AlarmManager.setWindow` (≈10-minute window around the slot) or
`setAndAllowWhileIdle`, rescheduled on boot. No `SCHEDULE_EXACT_ALARM`, no foreground service.
Why: "within a few minutes of the hour" is fine for a digest and avoids the Android 14+ exact-alarm
permission regime and the foreground-service-type churn. The listener is kept alive by the
system binding, not by us.

**[2026-11-07] Branch model and labels (house standard)**
What: `main` default (tested on phone), `develop` integration, per-issue worktrees, labels
`in-progress` / `ready-for-review` / `needs-changes` / `needs-device-test`. CI runs the same gate
as the box.
Why: identical to tracker-app and spotify-wrapper so the `work-issue` / `ship-issue` /
`orchestrate` / `promote-develop` skills work unchanged.
