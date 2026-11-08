## Status

Foundation (#2) landed on `develop`: `notification_event` schema v1 (`Notifications.sq`, replaces
`Meta.sq`, no migration), `NotificationRepository`, `SettingsStore` (interface +
`DataStoreSettingsStore`), the Hilt seams (`FocusGate`/`PassThroughGate`,
`FocusActions`/`DefaultFocusActions`, `SyncScheduler`/`NoopSyncScheduler`, `AppInitializer` set),
`nextSlot` slot maths, the bottom-nav shell (History / Focus / Settings + `apps/{list}`, start =
Focus), manifest permissions/queries/anchors, and shared test helpers under
`app/src/test/kotlin/com/hush/app/testing/`.

Wave-1 rule: any type two sibling issues share is defined in #2 with a no-op binding. A feature
issue swaps only its own binding in `focus/FocusModule.kt` or `sync/SyncModule.kt`; nobody else
edits `di/AppModule.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`,
`robolectric.properties`.

## Decision

**[2026-11-08] `event` stored as TEXT, mapped to `EventType` in the repository**
What: the `event` column is plain `TEXT`; `NotificationRepository` maps it to/from the enum.
No SQLDelight column adapter, so `HushDatabase(driver)` stays zero-arg.
Why: keeps the test helper and every sibling's DB construction trivial; the enum has two values.

**[2026-11-08] `nextSlot` is wall-clock based**
What: slot boundaries are computed on the local wall clock of the given zone (from local midnight
in `slotMinutes` steps, re-zoned), returning the first boundary strictly after now.
Why: "top of the hour" must still mean :00 on the day the clocks change. Across a spring-forward
gap the missing boundary collapses onto the next real one; across a fall-back repeat the loop
simply advances until it is strictly after now.

**[2026-11-08] Retention predicate**
What: `deleteSyncedOlderThan(ts)` deletes only rows with `post_time < ts AND synced_at IS NOT NULL
AND (held = 0 OR delivered_at IS NOT NULL)`.
Why: "never lose a notification": an unsynced row has not reached the server yet, and a held but
undelivered row has not reached the owner yet. Neither is ever eligible for local deletion.

**[2026-11-08] `NotificationEvent` carries `deliveredAt` / `syncedAt` (nullable, default null)**
What: the row type exposes both stamps; they are populated on read and ignored on `insert`
(which always writes NULL). Construction sites that omit them keep compiling.
Why: the digest and sync layers need to show "delivered at" / "synced" without a second query.

**[2026-11-08] `IN ?` lists are chunked to 500 inside the repository**
What: `markSynced` / `markDelivered` split `ids` into chunks of 500 and run them in one
transaction. Signatures are unchanged; callers may pass any size.
Why: SQLite on Android < 31 caps a statement at 999 bound variables; a busy hour of held
notifications or a large sync batch would otherwise throw.
