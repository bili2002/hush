# Hush

A personal notification ledger for Android with one polite rule.

- **Record everything.** A `NotificationListenerService` writes every notification (app, title,
  text, time) to a local database. Searchable history on the phone.
- **Mirror it home.** Unsynced rows are pushed to your own server (one Docker container, one
  SQLite file) over Tailscale. Long-term archive with full-text search.
- **Focus mode.** Put the noisy apps on a hold list. While Focus is on, their notifications don't
  show at all; at the top of every hour (configurable) you get one digest with everything that
  arrived. "Deliver now" whenever you like.

No ads, no cloud, no accounts. Your data stays on your phone and your box.

## Status
MVP in progress. See `STATUS.md` for where things are, `docs/ops.md` for running the server.

## Set up the phone

Do these in order; each step unlocks the next. The Focus screen shows the same steps as a setup
checklist and turns each row green as you complete it, so you can also just follow the app.

1. **Install.** On the phone, open `http://100.102.96.122:8081/hush/` (the same host by name:
   `http://serverche:8081/hush/`), tap the newest build, allow installs from the browser if asked.
   Open Hush; it lands on the **Focus** tab with the setup checklist.
2. **Notification access** (checklist row 1 → opens the system *Notification access* page).
   Hush is listed but on Android 13+ a sideloaded app's toggle is greyed out. Tap the greyed
   toggle once anyway, go back, long-press the Hush icon → **App info** → **⋮** (top right) →
   **Allow restricted settings**, then return to Notification access and enable Hush. The ⋮ entry
   is missing on some phones; installing with `adb install app-debug.apk` skips the whole
   restriction. The Focus screen's listener line turns to "Listener connected" within seconds.
3. **Notification permission** (checklist row 2 → the system prompt). Allow it; this is what lets
   Hush post the digest. If you denied it earlier, the row opens Hush's notification settings
   instead; turn notifications on there.
4. **Battery exemption** (checklist row 3 → *Ignore battery optimisation*). Set Hush to
   "Don't optimise" / "Unrestricted". Without it some phones delay or drop the hourly digest.
5. **Server** (Settings tab → **Server** card). URL `https://serverche.tail8236e9.ts.net:8443`,
   token = the value of `HUSH_TOKEN` in `~/.config/hush-server.env` on the box (paste works, the
   field is masked). Tap **Test connection** → "OK". The server is optional: without it Hush
   still records and holds; the phone just keeps only the last 30 days.
6. **Hold list** (checklist row 4, or Settings → *Hold list* → picker). Tick Instagram (and any
   other app you want quiet). Only ticked apps are ever held; everything else shows as normal.
7. **Focus on.** Back on the Focus tab the big toggle is now enabled (it stays disabled, with the
   reason shown, until steps 2, 3 and 6 are done). Turn it on. The screen shows "Next digest at
   HH:00 · 0 held" and the slot picker (15 / 30 / 60 / 120 min).

## How do I know it works?

1. Have a held app post a notification (have a friend message you on Instagram, or send yourself
   one from a second account). It never appears in the shade. A brief flash of the icon or the
   sound may still happen — that is the OS posting it a split second before Hush cancels it;
   expected.
2. On the Focus tab the counter reads "1 held". Tap **Deliver now**. One notification from Hush
   arrives: "1 held notification — Instagram 1", with the title and text inside. (Normally this
   arrives by itself at the slot; the first one can be a few minutes after the hour, because the
   alarm is deliberately inexact so Hush needs no exact-alarm permission and survives Doze.)
3. Tap the digest. Hush opens **History** filtered to that batch ("Held notifications delivered
   at HH:MM · Show all"); the row shows the app, title, text and a "Held · delivered HH:MM" chip.
   Tap the row to expand the full text.
4. On the server (from the box or any tailnet device, within 15 minutes or after Settings →
   **Sync now**):
   ```
   curl -H "Authorization: Bearer <token>" \
        'https://serverche.tail8236e9.ts.net:8443/notifications?q=<a word from that message>'
   ```
   returns the row. Settings → Server shows "Last sync HH:MM · ok" and "0 not yet synced".

## Troubleshooting (phone side)

| Symptom | Likely cause | Fix |
|---|---|---|
| Nothing is held; everything shows normally | Notification access is off or the listener got disconnected; or the app is not on the hold list; or Focus is off | Focus tab: the listener line must say "connected". If "not connected", open Notification access, toggle Hush off and on (sideload: redo *Allow restricted settings*). Check the hold list and the toggle. Calls, alarms, navigation and ongoing notifications are never held by design. |
| Held count grows but no digest arrives | notification permission denied, the `hush.digest` channel muted, battery optimisation killing the alarm, or Doze delaying it | Focus tab shows the exact error ("Notifications permission denied — digests can't be shown"). Allow notifications, unmute the "Digest" channel, set battery to unrestricted. In deep sleep the digest may be a few minutes late — never lost: anything held longer than two slots is delivered regardless of schedule, and **Deliver now** always works. |
| Settings says "Wrong token (401)" | token on the phone ≠ `HUSH_TOKEN` on the box | re-paste it (watch for trailing spaces), **Test connection** |
| "Can't reach server: …" | phone not on the tailnet, URL wrong, or `tailscale serve` not set up on the box | URL must be `https://serverche.tail8236e9.ts.net:8443` exactly (no path). On the box: `tailscale serve status` (see `docs/ops.md`). "Server not set up (optional)" just means URL or token is blank. |
| Works for a day, then stops recording (Samsung, Xiaomi, Huawei, OnePlus…) | OEM background killer | don't swipe Hush away from Recents; App info → Battery → *Unrestricted* / disable "Put app to sleep"; Xiaomi: enable *Autostart* and lock the app in Recents. Hush rebinds the listener every 15 minutes by itself, but it cannot fight a force-stop. |
| Reinstalled or cleared data; History is empty | phone DB and settings are gone | expected — the server kept everything (`/notifications` still returns it; the static search page on the server shows it). Re-enter URL + token and the hold list; the phone archive refills from new notifications only. |
| Digest tap opens History but shows everything | the deep link filter was cleared by navigating | that is "Show all"; the chip "Held · delivered HH:MM" still marks the batch rows |

## Build
```
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./gradlew :server:test :app:testDebugUnitTest :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. `./publish-apk.sh develop` builds
`origin/develop` and parks it on the download page above.

## Server
```
./deploy-server.sh develop      # build origin/develop, replace the container, verify /health + auth
./deploy-server.sh --rollback   # back to the previous image, same verification
```
One container on `127.0.0.1:8082`, one SQLite file in `~/.local/share/hush-server`, token in
`~/.config/hush-server.env`, TLS via `tailscale serve` on 8443. Setup, backup, restore and the
server-side troubleshooting table: **[`docs/ops.md`](docs/ops.md)**.

## License
MIT
