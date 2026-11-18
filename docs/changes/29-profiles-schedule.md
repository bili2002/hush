# 29 — Focus profiles, a weekly schedule, Do Not Disturb

## Status
- **Profiles** (`focus/FocusProfiles.kt`, one JSON preference `focus_profiles`). A profile is a
  name plus `ProfileConfig`: the default interval, each group's throttle, the chat rules; and two
  flags, `dnd` and `oneTime`. The hold list and the groups (which app is in which group) are
  shared by all profiles.
- **The live settings are the active profile.** `Settings.slotMinutes`, the groups' minutes and
  the chat rules stay where they were and are what `FocusPolicy`, `Deliverer` and the scheduler
  read. Switching (`withActiveProfile`) writes them back into the profile that was active and
  loads the other's. Settings from before this change are simply the profile "Default"; nothing
  is migrated. `viewOf(id)` / `withProfileEdited(id, edit)` read and edit a profile that is not
  active with the unchanged rule functions.
- **Schedule.** `ScheduleEntry(profile, weekdays, start, end)`; an entry past midnight belongs to
  the day it starts on; of overlapping entries the one that started last wins.
  `Settings.reconciled(now, zone)` is the whole rule: inside an entry Focus is on with its
  profile, outside all entries Focus is off, and while a manual choice stands (`manualUntil`,
  local midnight) nothing changes.
- **One run does everything.** `Deliverer.deliverDue` starts with `FocusAutomation.apply`
  (reconcile, then Do Not Disturb), judges the held rows under the result, and ends with
  `DeliveryScheduler.rearm`, which arms the alarm for the earlier of the next digest boundary and
  the next schedule change, or clears it. Every `FocusActions` call is "write settings, then one
  run", so `setEnabled` no longer manages the alarm itself and `setSlotMinutes` is gone.
- **The gate does not wait for the alarm.** `HoldGate` decides on `settings.reconciled(now)`, so
  a notification arriving just after a scheduled start is held even if the alarm is late in Doze.
  The Focus screen shows the same reconciled state.
- **Do Not Disturb** (`DndController`): on while Focus is on with a profile that asks for it
  (`INTERRUPTION_FILTER_PRIORITY`), off again when not. `dndByHush` records that Hush turned it
  on; Hush never turns off one it did not turn on, and does not turn it back on if the owner
  switched it off meanwhile. A failure is kept in `dndError` and shown on the Focus screen, which
  opens the system's Do Not Disturb access page. New manifest permission
  `ACCESS_NOTIFICATION_POLICY`.
- **UI.** Focus screen: profile list (pick, ⋮ → Apps & throttles / Rename / Duplicate / Delete,
  "New profile" with "Copy of" and "One-time"), "Schedule" → `ui/schedule/` (entries edited in
  place), a schedule line under the switch with "Resume schedule" while a manual choice stands.
  "Apps & throttles" takes a profile (`focus/rules?profile=<id>`) and has the Do Not Disturb
  switch.

## Decision
- **Groups are shared, throttles are per profile.** Sorting apps is done once; a profile only
  says how each group is treated ("Messaging: 1 h at work, instantly in the evening, muted at
  night"). The alternative, a full copy of everything per profile, would make every newly
  installed app a chore in each profile.
- **Live settings as the active profile, instead of moving the rules into a profile list.** No
  migration of the stored settings, the engine untouched, and a build without profiles would
  still read the same keys. The cost is one rule to respect: the active profile's stored
  `config` is stale, so profiles are read through `Settings.profileList` only.
- **A manual change stands until midnight.** The owner asked to be able to override the schedule
  "for the given day". So with a schedule, the switch and a profile pick both hold until local
  midnight; "Resume schedule" ends it earlier. Without a schedule nothing expires. Known edges of
  "midnight": a manual "on" at 23:50 outside every entry ends ten minutes later, and a manual
  "off" inside an entry that runs past midnight is undone at 00:00. A tap on the profile already
  in force is not a manual change.
- **With a schedule, outside every entry Focus is off.** One rule, no third state. To keep Focus
  on all day, add an entry for it.
- **One-time profiles go when another profile takes over** (picked, or scheduled), not at a
  clock time of their own. Without a schedule that is whenever the owner picks another; with one,
  it is the first scheduled start after the manual hold ends at midnight. They cannot be
  scheduled.
- **"Apps & throttles" is pinned to the profile it was opened for.** It never follows the active
  profile, so a schedule switching underneath cannot move an edit into another profile; if its
  profile disappears the screen closes.
- **A new schedule entry has no day selected**, so it does nothing until it is set up. Every
  later edit of an entry applies at once.
- **A deleted group's throttle is forgotten in every profile** (`withoutCategory`), so a preset
  the automatic sort re-creates under the same id starts on the default everywhere.
- **Process start runs a delivery pass** when Focus is on or a schedule exists
  (`FocusInitializer`), instead of only re-arming: a schedule start whose alarm was lost with the
  process is applied then.
- **A schedule change that turns Focus off, or a switch to a profile that holds less, releases
  the affected rows in the same run.** Nothing waits under rules that no longer hold it.
- **Behaviour change:** any rule edit now ends in a delivery run even with Focus off (it is what
  applies a schedule edit). With Focus off that run releases rows still held from an earlier
  blocked flush, which previously waited for the periodic safety net.
- **Do Not Disturb, known limits.** With targetSdk 35 on Android 15+ the filter Hush sets is its
  own mode (an implicit rule), so turning it off cannot touch the owner's. On Android 14 and
  below the switch is global: if Hush turned it on and the owner's own rule starts before Focus
  ends, Hush's "off" ends that too. `dndByHush` lives in the profiles preference; clearing app
  data while Hush's Do Not Disturb is on leaves it on (Android ≤ 14).
- **Not verifiable off the phone:** that notification access is enough to switch Do Not Disturb
  (the code asks `isNotificationPolicyAccessGranted` and reports when it is not; the Android
  reference does not say), and alarm punctuality for schedule starts (`setAndAllowWhileIdle` is
  inexact; the gate covers holding at once, Do Not Disturb and the stored state wait for the
  alarm).
