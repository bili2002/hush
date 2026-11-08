# #5 — Sync: idempotent push, 30-day retention, listener watchdog

## Status
Sync is implemented in `app/src/main/kotlin/com/hush/app/sync/`:
- `SyncWorker` (`@HiltWorker`) runs every 15 minutes (`hush-sync`, `NetworkType.CONNECTED`,
  `ExistingPeriodicWorkPolicy.UPDATE`, registered by `SyncInitializer` via `@IntoSet AppInitializer`)
  and on demand through `SyncScheduler.syncNow()` (`WorkSyncScheduler`, unique one-time work
  `hush-sync-now`, `REPLACE` so a tap always runs even while a previous attempt sits in backoff).
- Push: `selectUnsynced(500)` → `POST {serverUrl}/notifications` with `Authorization: Bearer` →
  `markSynced` until the queue is empty. Body/response shapes are issue #8's exactly (`NotificationDto`,
  `PushRequest`, `PushResponse`, camelCase, `event` = `posted`/`removed`).
- Status the UI can read from `Settings`: `lastSyncAt` (set only after a fully successful run),
  `lastSyncError` (`null` when fine or unconfigured; "Can't reach server: …" / "Wrong token (401)" /
  "Server rejected the batch: …"), and `NotificationRepository.countUnsynced()` for the pending count.
  Unconfigured (blank URL or token) is success with no error: the UI shows "Server not set up (optional)".
- Housekeeping after every run, configured or not: retention `deleteSyncedOlderThan(now - 30d)`,
  every bound `DeliverDueHook`, and the listener watchdog (`requestRebind` on
  `com.hush.app.listener.NotificationCollectorService` whenever notification access is granted).
- Tests: `HushApiTest` (MockEngine), `SyncWorkerTest` (Robolectric + `TestListenableWorkerBuilder`),
  `SyncSchedulerTest` (`WorkManagerTestInitHelper`). Doze deferral of periodic work is accepted and
  not tested.

**For the Focus issue (#4):** contribute the delivery safety net in `focus/FocusModule` with an adapter

```kotlin
@Provides
@IntoSet
fun deliverDueHook(d: Deliverer): com.hush.app.sync.DeliverDueHook = DeliverDueHook { d.deliverDue() }
```

`Deliverer.deliverDue(force = false)` is the non-forced, due-rule path (not "deliver now"). No change to
`sync/**` is needed.

## Decision

**[2026-11-08] `DeliverDueHook` is a Hilt multibinding set, not a single replaceable binding**
What: `SyncModule` declares `@Multibinds Set<DeliverDueHook>` (empty by default); `SyncWorker` calls
every hook. Focus adds its deliverer with `@Binds @IntoSet` in its own module.
Why: no `Deliverer` type existed on the foundation branch when sync was written. A single no-op
`@Provides` would have forced the Focus PR to edit `SyncModule` to avoid a duplicate binding; a set
lets both issues land independently. `FocusActions.deliverNow()` was deliberately not used: it means
"deliver everything now", which would defeat Focus if called every 15 minutes.

**[2026-11-08] Excluded-package rows are stamped `synced_at` without being sent**
What: before each push the batch is filtered by `Settings.excludedPackages`; filtered rows are still
`markSynced` together with the pushed ones (an all-excluded batch skips the HTTP call entirely).
Why: the listener never stores excluded packages, so this is a defensive net; but if such a row ever
existed and stayed unsynced, `selectUnsynced(500)` would return it forever and the worker would loop.
Stamping it lets retention delete it after 30 days.

**[2026-11-08] `KtorHushApi` serialises with its own `Json`, not the client's ContentNegotiation**
What: request body is sent as `TextContent(application/json)`; the response is parsed from
`bodyAsText()`. `Json { ignoreUnknownKeys = true }`.
Why: the `HttpClient` is provided by `di/AppModule.kt` (CIO, no `Logging` plugin, default 15 s request
timeout). Keeping the wire contract inside the API class means tests use a bare `HttpClient(MockEngine)`
and a later change to client plugins cannot alter what the server receives.

**[2026-11-08] Error mapping and the poison-batch rule**
What: 2xx → `Ok`; 401/403 → `Unauthorized` → `Result.failure()`; other 4xx → `Rejected(server
"error" field or "HTTP n")` → `Result.failure()`; 3xx or an unusable URL → `Rejected("Check the server
URL")`; 5xx (server "error" field or "HTTP n") / I/O / timeout / unparseable 2xx body → `Transient` →
`Result.retry()` with exponential backoff. An exception escaping the push loop is recorded as
`"Sync failed: <ExceptionName>"` with `Result.failure()`; housekeeping runs in `finally`. Progress from earlier batches in the same run
stays marked synced; `lastSyncAt` moves only on a complete run.
Why: a rejected batch cannot succeed by retrying (it would only burn the backoff ladder), so the periodic
run simply tries again next cycle. An unparseable 2xx is retried because the server does `INSERT OR
IGNORE`, so a re-push is harmless. Error strings never include the token.

**[2026-11-08] `testing/TestDb.kt` preloads `org.sqlite.JDBC`**
What: `testDatabase()` calls `Class.forName("org.sqlite.JDBC")` before creating the `JdbcSqliteDriver`.
Why: `DriverManager` only hands out a driver registered from the caller's classloader. Robolectric
tests run in a sandbox loader, so once Robolectric tests also used the JDBC database (this issue), the
first `JdbcSqliteDriver` created in whichever loader ran first failed with "No suitable driver"
(order-dependent: it hit `SyncWorkerTest` in one run and the foundation's `NotificationRepositoryTest`
in the next). Loading the driver class in the caller's loader registers it there. This is the one
change outside `sync/**` in this PR.
