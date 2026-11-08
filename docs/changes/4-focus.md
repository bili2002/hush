# 4 — Focus engine

## Status
Focus engine landed (`focus/**`): `FocusPolicy` + `HoldGate` (the listener's `FocusGate`),
`Deliverer` (HELD → DELIVERED state machine, one digest per slot on channel `hush.digest`),
`DeliveryScheduler` (inexact `setAndAllowWhileIdle` alarm at the next slot), `DeliveryReceiver` /
`RescheduleReceiver` (alarm, boot, app update, timezone, time-set), and `DefaultFocusActionsImpl`
(the UI's enable / slot / deliver-now). `Deliverer` is an injectable `@Singleton` so the Sync
worker can call `deliverDue()` as a second safety net. Still open from this issue: the on-device
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

**[2026-11-08] Alarm is re-armed by `Deliverer`, on every exit path, while Focus is on**
What: `setAndAllowWhileIdle` is one-shot, so `deliverDue()` reschedules after each run (empty, not
due, permission denied, or delivered). `setEnabled(false)` turns Focus off first, then force
delivers, then cancels, so the forced run does not re-arm.
Why: a single owner of the alarm avoids the receiver/worker/UI each having to remember to re-arm.

**[2026-11-08] Receivers are `exported="false"`**
What: both receivers are non-exported; `RescheduleReceiver` still receives the system broadcasts.
Why: the alarm uses our own explicit PendingIntent, and system broadcasts are delivered to
non-exported receivers (WorkManager's own `RescheduleReceiver` is declared the same way).
