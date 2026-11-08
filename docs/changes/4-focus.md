# 4 — Focus engine

## Status
Focus engine landed (`focus/**`): `FocusPolicy` + `HoldGate` (the listener's `FocusGate`),
`Deliverer` (HELD → DELIVERED state machine, one digest per slot on channel `hush.digest`),
`DeliveryScheduler` (inexact `setAndAllowWhileIdle` alarm at the next slot), `DeliveryReceiver` /
`RescheduleReceiver` (alarm, boot, app update, timezone, time-set), `FocusInitializer` (re-arms the
alarm at process start while Focus is on, so a force-stop cannot strand held rows), and
`DefaultFocusActionsImpl` (the UI's enable / slot / deliver-now). `FocusModule` also contributes Sync's `DeliverDueHook` (`@Provides @IntoSet`, see
`docs/changes/5-sync.md`) as an adapter over the non-forced `Deliverer.deliverDue()`, so the periodic
sync worker is the second safety net for a missed alarm without ever forcing a mid-slot digest. Still open from this issue: the on-device
checks (Instagram group summary lingering?, Doze delivery timing).

## Decision
**[2026-11-08] Non-forced `deliverDue()` is "due"-gated; forced delivers everything**
What: `deliverDue(force = false)` posts a digest only when at least one held row has had a slot
boundary pass since it was posted, or is older than `max(2 × slot, 120) min`. When anything is
due, every held row goes into the one digest (stragglers posted between the boundary and a late
alarm ride along). `force = true` (Deliver now, Focus off) ignores the gate. Rows are selected
regardless of the current hold list or the Focus toggle, so nothing held is ever stranded.
Why: the same entry point is called by the alarm, by boot/time-change receivers and by the Sync
worker's periodic run; without the gate the worker would deliver mid-slot and defeat "one digest
per slot". The max-age clause is implied by the slot clause for a fixed zone and is kept as an
independent guard against zone/clock jumps.

**[2026-11-08] One digest notification id; the newest digest replaces the previous one**
What: the digest always uses the same notification id. A new slot's digest replaces the one in the
shade; the History screen (filter HELD) shows every delivered batch.
Why: "one thing to look at" was the owner's ask; stacking hourly digests recreates the clutter
Focus exists to remove. The tap intent carries the exact `delivered_at` so the UI can still open
on the latest batch.

**[2026-11-08] Alarm is re-armed by `Deliverer` on every exit path while Focus is on, or while anything is still held**
What: `setAndAllowWhileIdle` is one-shot, so `deliverDue()` reschedules after each run (empty, not
due, permission denied, or delivered) whenever Focus is on, and also whenever rows remain held
after the run (Focus off but delivery blocked). `setEnabled(false)` turns Focus off first, force
delivers, then cancels the alarm only if nothing is held and no error was reported; otherwise it
keeps the alarm so the next slot retries. `FocusInitializer` re-arms at process start.
Why: a single owner of the alarm avoids the receiver/worker/UI each having to remember to re-arm,
and "never lose a notification" means a held row always has a pending delivery attempt.

**[2026-11-08] Receivers are `exported="false"`**
What: both receivers are non-exported; `RescheduleReceiver` still receives the system broadcasts.
Why: the alarm uses our own explicit PendingIntent, and system broadcasts are delivered to
non-exported receivers (WorkManager's own `RescheduleReceiver` is declared the same way).
