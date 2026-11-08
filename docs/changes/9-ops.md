# #9 — Ops: deploy verification, tailscale serve, phone walkthrough, troubleshooting

## Status

The operator's runbook is **[`docs/ops.md`](../ops.md)**: ports table (8082 loopback server, 8443
`tailscale serve` TLS front, 8081 shared APK host under `/hush/`), the one-time host steps (env
file with `HUSH_TOKEN=$(openssl rand -hex 32)` chmod 600, `sudo tailscale serve --bg --https=8443
http://127.0.0.1:8082`, verification curls from the tailnet), what each line of
`deploy-server.sh` proves, backup (`sqlite3 ... ".backup ..."`, safe online), restore (copy back,
drop stale WAL, **FTS rebuild**), rollback and a server-side troubleshooting table. The phone
walkthrough ("install → permissions → server → hold list → Focus on"), the "how do I know it
works?" checklist and the phone-side troubleshooting table live in `README.md`.

`deploy-server.sh develop` was run against the final #8 API and matched it unchanged (`/health`
open, `/apps` 401 vs token). Two additions:
- `./deploy-server.sh --rollback` re-runs the container from `hush-server:previous` with the same
  flags (one `run_container` function is now the only place the `docker run` line lives) and the
  same verification. `previous` exists from the second deploy on and is overwritten by the next
  build.
- `.dockerignore` also excludes `.kotlin`, `.idea`, `*.keystore`, `*.jks`; build context is
  ~0.3 MB.

Corrections for STATUS.md "How to run" / verify block when folded in:
- APK host line is `./publish-apk.sh develop   # → http://100.102.96.122:8081/hush/` (the
  `8083` / `hush-apk` container never existed; see `apk-host-multi-app.md`).
- Add `./deploy-server.sh --rollback` next to the deploy line.
- Host-state bullets can shrink to "see `docs/ops.md`".
