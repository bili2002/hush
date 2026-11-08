## Status

Screens B (#7) landed: the real **Settings** tab (`ui/settings/`) and the **app picker**
(`ui/apps/`, route `apps/{list}`). Settings has four cards: Server (URL + masked token, 500 ms
debounced into `SettingsStore`; "Test connection" = `GET {url}/health` with the bearer through the
shared `HttpClient`; "Sync now" → `SyncScheduler.syncNow()`; status line from `lastSyncAt` /
`lastSyncError` plus `countUnsynced()`), Listener (state from `listenerConnectedAt` /
`listenerDisconnectedAt` + `NotificationManagerCompat.getEnabledListenerPackages`, opens
`ACTION_NOTIFICATION_LISTENER_SETTINGS`, sideload hint only while not enabled), Battery
(`isIgnoringBatteryOptimizations`, opens `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`), Apps (hold /
excluded counts → picker, `logOngoing` switch). Listener/battery state is re-probed on every
`ON_RESUME`. The picker merges launchable apps (package scan on IO, loading state) with
`selectApps()` and anything still selected, selected-first then A→Z, with search.

Seams for tests: `SystemStateProbe`, `InstalledAppsSource`, `ConnectionTester`; bound in
`ui/settings/ScreensBModule.kt`.

## Decision

**[2026-11-08] Screens B's OS probes live behind two tiny interfaces bound in `ui/settings/`**
What: `SystemStateProbe` (listener enabled, battery exempt) and `InstalledAppsSource` (launcher
scan) are interfaces with `@Inject` Android implementations, bound by `ScreensBModule` inside
`ui/settings/`, not `di/AppModule`.
Why: the ViewModels stay plain-JVM testable with `FakeSettingsStore` + `TestDb` (no Robolectric
for the screens), and the wave-1 rule says only the foundation edits `di/`.

**[2026-11-08] Listener health is a package-level check**
What: "enabled" = our package is in `Settings.Secure.enabled_notification_listeners`
(`NotificationManagerCompat.getEnabledListenerPackages`), not a `ComponentName` match against the
service class.
Why: the service class belongs to the listener issue, which is built in parallel; the package-level
check is what the system actually keys the toggle on and needs no cross-issue type.

**[2026-11-08] Hush itself is filtered out of the app picker**
What: `PackageManagerAppsSource` drops `ctx.packageName`.
Why: Hush is never held or excluded regardless (Focus decision), so offering it would only confuse.
