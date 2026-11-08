## Status

Screens A (#6): the Focus and History screens replace their placeholders.

- **Focus** (`ui/focus/`): toggle bound to `FocusActions.setEnabled`, gated (disabled with one
  tappable row per reason) when Notification access is off, `POST_NOTIFICATIONS` is not granted,
  or the hold list is empty; "Next digest at HH:mm · N held" from `countHeld()` + `nextSlot`
  on a one-minute ticker; 15/30/60/120 slot picker (M3 segmented buttons); "Deliver now" with the
  `DeliverResult` (count or error) shown inline; hold-list summary with Edit →
  `Routes.appPicker("hold")`; `lastFocusError` card; listener health line; four-row setup
  checklist (listener access with the sideload hint, runtime permission with the
  "don't ask again" fallback to the app's notification settings, battery exemption, hold list),
  re-checked on resume, collapsed for good via `settings.setupDone` once all four are green.
- **History** (`ui/history/`): newest-first `selectRecent` with day headers, app icon + label,
  title, two-line text, time; tap expands to the full text / `bigText`; held rows carry
  "Held" / "Held · delivered HH:mm" from `deliveredAt`; REMOVED events hidden; 300 ms debounced
  search over `search()`; app filter chips from `selectApps()`; empty states (listener off →
  enable button; nothing yet; no matches for 'x'; empty app; empty batch); loading until the first
  page; "load more" grows the page by 200.
- **Digest deep link**: `DigestLink.parse(intent)` reads `EXTRA_FILTER == FILTER_HELD` +
  `EXTRA_DELIVERED_AT`; `MainActivity` applies it in `onCreate` (cold) and `onNewIntent` (warm,
  `singleTop`). History shows `selectHeldBatch(deliveredAt)` under a banner with "Show all";
  leaving the History tab clears the filter.
- **Shared** (`ui/components/`): `SystemStatus` (listener enabled / POST_NOTIFICATIONS / battery
  exemption) + `AppLabelResolver` seams bound in `UiModule`; `UiSignals`
  (activity-retained mailbox: pending digest link + navigation requests); `AppIcon` with a
  process-wide LRU cache; time helpers.
- Tests: `FocusViewModelTest`, `HistoryViewModelTest` (TestDb + FakeSettingsStore + fixed clock,
  synthetic rows), `DigestLinkTest` (Robolectric, real `Intent`). Compose previews for both screens.

Needs device test: permission flows, settings deep links, digest tap cold/warm, icon loading.

## Decision

**[2026-11-08] Screen ↔ shell plumbing goes through an activity-retained `UiSignals`, not NavHost parameters**
What: `UiSignals` (`@ActivityRetainedScoped`) holds the pending `DigestLink` and a
`SharedFlow<String>` of navigation requests. `MainActivity` writes the link from its intent,
`HushRoot` takes it (plus the request flow) as parameters, and the ViewModels inject `UiSignals`
directly. The `composable(...)` call sites in `HushRoot` stay exactly as the foundation wrote them.
Why: Screens A and B both touch `MainActivity` in the same wave; keeping the NavHost lines
untouched avoids a merge conflict, and the deep link has to survive the HistoryViewModel not
existing yet at cold start (the StateFlow holds it until the screen is composed).

**[2026-11-08] Focus/History ViewModel state is pure Kotlin; OS facts come through `SystemStatus`**
What: `FocusUiState` / `HistoryUiState` hold enums, epoch millis and `LocalDate`s; Compose does all
string formatting. The three gating facts are read through the `SystemStatus` interface, snapshotted
on `init` and on every `onResume`, with `AndroidSystemStatus` bound in `ui/components/UiModule.kt`.
Why: every acceptance criterion about gating, checklist, next-slot and empty-state selection is
unit-tested against the real in-memory DB and the foundation's fakes without Robolectric.

**[2026-11-08] Setup checklist collapses by writing `setupDone` the first time all four rows are green**
What: the card is shown while `!setupDone && !allDone`; the ViewModel writes `setupDone = true`
when it first observes all four satisfied. Gating is computed independently, so a later revoked
permission still disables the toggle with its reason row even though the checklist stays hidden.
Why: matches the spec ("shown until all four are true, then collapses; `setupDone` remembers
dismissal") without a separate "dismiss" affordance that could hide an incomplete setup.

**[2026-11-08] App-only filter uses `search("", app)` rather than filtering `selectRecent` in memory**
What: a chip with no query runs `search` with an empty needle and the package.
Why: the result is bounded by that app's own most recent rows, not by the global page; the one
row shape it misses (title, text and big_text all NULL) has nothing to show anyway.
