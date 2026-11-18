# 33 — Custom intervals, un-muting releases, Muted view

## Status
- **Intervals.** `THROTTLE_CHOICES` is 15 min, 30 min, 1, 2, 3, 4, 6, 8, 12 h, and every throttle
  picker has "Custom…" (`CustomThrottleDialog`, hours and minutes, `THROTTLE_RANGE` = 5 min to
  24 h, parsed by `customThrottle`). A custom value in force is listed in its place.
- **"Muted" lasts as long as the rule does.** At the start of every delivery run with Focus on,
  `Deliverer.releaseUnmuted` asks which chats have anything muted (`selectMutedChats`, one row per
  app and chat key, so a month of muted rows costs nothing to check) and, for each whose rule in
  force is no longer `MUTED`, clears the stamp of its muted rows (`unmuteChat`, guarded so it can
  only touch muted rows). A failure there is reported in the Focus error line and does not stop
  the delivery of rows that are already held. From then on they are ordinary held rows: due by their new interval, covered by the
  max-age guard, delivered once, part of the digest and its History link. This is what a profile
  switch, a schedule change or a rule edit does to what the previous rule muted. This replaces
  #28's "a muted row is finished for good".
- **Muted view.** History has a "Muted · N" filter chip (shown while anything is muted; combines
  with the app and chat filters; `selectHistory`/`countHistory` take `mutedOnly`). The Focus
  screen shows "Muted · N" and opens History on that filter (`UiSignals.showMuted`). A navigation
  request for a tab route is now a tab switch, not a screen pushed on top.

## Decision
- **Turning Focus off does not release muted notifications.** Off is not a rule that un-mutes.
  If it were, "Muted" could only ever mean "held until Focus ends", and the owner asked for a way
  to stop some notifications completely. To stop something for good: mute it in every profile
  that is used. To get it later: have a profile, or a rule edit, that gives it an interval or
  "Instantly".
- **A released row takes its new interval from its original post time.** One that was posted
  before the current slot is due at once; one from the current slot waits for the boundary like
  any other held row. Nothing is special-cased.
- **No age cap on the release.** A weekend profile that mutes work delivers the whole weekend on
  Monday, as one digest with a count and a link to the rows. The other side of that: un-muting a
  group that was muted everywhere for weeks delivers everything still on the phone (retention is
  30 days after sync).
- **Custom intervals are bounded at 5 min and 24 h, and local midnight is always a slot
  boundary** (`nextSlot`). The presets all divide the day, so nothing changes for them; a custom
  100 or 1000 minutes has one shorter last slot and then restarts, which keeps the alarm and each
  row's own due time on the same grid on both sides of midnight. "24 h" therefore means one
  digest at midnight.
- **The other direction is not symmetric.** A notification that is already held when its rule
  becomes "Muted" (a switch into a profile that mutes its group) is delivered at that switch, as
  decided in #28, not muted. So is a row that was released from "Muted" and is still waiting when
  its old profile comes back. Muting applies to what arrives under the muting rule.
- **Anything that stops a rule being "Muted" releases**, not only a profile switch: deleting a
  muted group, taking a muted app off the hold list, or removing a muted chat rule delivers that
  backlog too.
