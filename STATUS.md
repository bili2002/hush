# Hush — STATUS

_Last updated: 2026-11-07_

## Where we are
Bootstrapped 2026-11-07: hello-world Compose app (`:app`) + Ktor `/health` server (`:server`),
both green on serverche and in CI. No features yet; the MVP backlog is filed as GitHub issues.

## Verify commands (read by `orchestrate` / `publish-apk` / `deploy-server`)
```
export JAVA_HOME=~/toolchain/jdk-17.0.13+11 ANDROID_HOME=~/android-sdk
./gradlew :server:test :app:testDebugUnitTest :app:assembleDebug     # green gate
./publish-apk.sh develop     # → http://100.102.96.122:8083/  (serverche:8083 on the tailnet)
./deploy-server.sh develop   # → container hush-server on 127.0.0.1:8082, data in ~/.local/share/hush-server
```

## How to run (host state, one-time)
- Server env file: `~/.config/hush-server.env`, chmod 600, one line `HUSH_TOKEN=<random 32+ chars>`
  (`openssl rand -hex 32`). Never committed, never echoed.
- TLS front for the phone: `sudo tailscale serve --bg --https=8443 http://127.0.0.1:8082` once;
  tailscaled resumes it across reboots. The phone then uses
  `https://serverche.tail8236e9.ts.net:8443` as the server URL in Settings. (tracker-server owns
  port 443.)
- APK host: `publish-apk.sh` starts an nginx container `hush-apk` on the tailnet IP, port 8083.
- Sideloaded APKs on Android 13+: before Notification access can be granted, open App info → ⋮ →
  "Allow restricted settings". `adb install` avoids that step.

## MVP backlog (wave 1)
See the open issues. Order: #1 foundation first, then the rest in parallel.

## Done
_(nothing shipped yet)_
