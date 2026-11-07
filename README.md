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
Bootstrapped; MVP in progress. See `STATUS.md`.

## Build
```
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
./gradlew :server:test :app:testDebugUnitTest :app:assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Server
```
docker build -f server/Dockerfile -t hush-server .
docker run -d --name hush-server --restart unless-stopped \
  --env-file ~/.config/hush-server.env -v ~/.local/share/hush-server:/data \
  -p 127.0.0.1:8082:8080 hush-server
```
`~/.config/hush-server.env` contains one line, `HUSH_TOKEN=<random>`. Put the same token and the
server URL into the app's Settings. Front it with `tailscale serve` (or any TLS proxy) so the token
never travels in clear. `deploy-server.sh` does the above with verification.

## Installing a sideloaded APK on Android 13+
Notification access for a sideloaded app is a "restricted setting": open App info → ⋮ →
**Allow restricted settings** before the Notification access toggle becomes available. Installing
with `adb install` skips this.

## License
MIT
