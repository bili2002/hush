# Ideas — next waves (not in MVP)

Drawn from the research/ideation pass on 2026-11-07. Triage with the owner after the MVP is
signed off; file survivors as issues.

## Likely wave 2
- **"Also silence" DND rule** while Focus is on (`AutomaticZenRule`, policy-access permission):
  removes the brief heads-up flash/sound before the listener cancels a held notification.
- **Quick Settings tile** to toggle Focus, subtitle "until 14:00"; optional auto-off time.
- **Per-app "metadata only" tier** (store package + time, blank title/text) and a default
  exclude list for banking / authenticator / password-manager packages; OTP-pattern scrub.
- **Listener health history** in Settings (connect/disconnect timestamps) + a loud "Focus paused,
  listener was killed" notification when the watchdog fails open.
- **Tap a digest row → open the original app**; long-press → mark as read.

## Later
- Attention analytics: per-app counts, hour-of-day heatmap, "interrupted N times today"; could be a
  tracker-app module.
- AI triage of the batch on the server (group/rank/summarize; promote urgent items).
- "What did I miss" natural-language query over the archive.
- Context-aware release (on unlock after idle, when a tracker-app timer ends, Home Assistant
  presence/sleep state).
- Home Assistant hooks both ways (notification → HA event; HA state → Focus profile).
- Attention budgets per app/category.
- Export (CSV/JSON) from the server page.
- Cross-device ingest (tablet).

## Refused for now (Steward's list)
Rules engine / keyword filters / VIP contacts matrices, replies/actions on re-delivered items,
multi-user accounts, Play Store distribution, image/attachment archiving, SQLCipher.
