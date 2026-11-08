# Hush server — operator's runbook (serverche)

Everything about running the archive server on the box: ports, the one-time host setup, deploy,
backup/restore, rollback, and what the error codes mean. The phone side (install, permissions,
"how do I know it works") is in [`README.md`](../README.md).

The server is one Docker container (`hush-server`) with one SQLite file (`hush.db`, WAL mode,
FTS5 search index) under a bind-mounted host directory. Its API is described in
[`docs/changes/8-server.md`](changes/8-server.md).

## Ports

| Port | What | Who listens | Reached from |
|---|---|---|---|
| **8082** | Hush server, plain HTTP | container `hush-server` (8080 inside), published on `127.0.0.1` only | the box itself, `tailscale serve` |
| **8443** | Hush server, HTTPS | `tailscale serve` → `http://127.0.0.1:8082` | the phone / laptop on the tailnet: `https://serverche.tail8236e9.ts.net:8443` |
| **8081** | APK download host (all apps), plain HTTP | container `tracker-apk`, tailnet IP | `http://100.102.96.122:8081/hush/` (MagicDNS alias `http://serverche:8081/hush/`) |
| 8080 / 443 | tracker-server (another project) | — | that is why Hush uses 8082 / 8443 and not the defaults |

Paths on the host:

| Path | What | Mode |
|---|---|---|
| `~/.config/hush-server.env` | one line `HUSH_TOKEN=<random>`; the only copy of the secret on the box | `chmod 600`, never committed, never echoed |
| `~/.local/share/hush-server/` | data dir, bind-mounted as `/data` in the container | owned by you (the container runs as your uid/gid) |
| `~/.local/share/hush-server/hush.db` (+ `-wal`, `-shm`) | the archive | see Backup |
| `~/apk-host/hush/` | APKs, `builds.tsv`, `app.meta` for the download page | written by `publish-apk.sh` |

## One-time host setup

1. **Token.** Generate it, store it, lock it down. The command never prints it, and it refuses
   to overwrite an existing file (rotating the token means re-entering it on the phone).
   ```
   mkdir -p ~/.config
   [ -e ~/.config/hush-server.env ] || (umask 077; echo "HUSH_TOKEN=$(openssl rand -hex 32)" > ~/.config/hush-server.env)
   chmod 600 ~/.config/hush-server.env
   ```
   The token is never logged or echoed by any script. View it only when you need to paste it
   into the app (`cat ~/.config/hush-server.env`, then Hush → Settings → Server → Token).
2. **Data dir.** `mkdir -p ~/.local/share/hush-server` (the deploy script also creates it).
3. **Deploy once** so the container exists: `./deploy-server.sh develop` from the repo root (see
   Deploy below).
4. **TLS front for the phone.** Once; `tailscaled` restores it across reboots:
   ```
   sudo tailscale serve --bg --https=8443 http://127.0.0.1:8082
   tailscale serve status        # should list https://serverche.tail8236e9.ts.net:8443 → 127.0.0.1:8082
   ```
   Port 443 already belongs to tracker-server on this box, hence 8443. The bearer token only ever
   travels inside this TLS tunnel; the container itself is unreachable from the tailnet.
5. **Verify from the laptop or phone** (any device on the tailnet):
   ```
   curl https://serverche.tail8236e9.ts.net:8443/health
   # → {"ok":true,"rows":0,"service":"hush-server"}

   curl -H "Authorization: Bearer $(sed -n 's/^HUSH_TOKEN=//p' ~/.config/hush-server.env)" \
        https://serverche.tail8236e9.ts.net:8443/apps
   # → []            (200; the archive is empty until the phone syncs)
   curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer nope' \
        https://serverche.tail8236e9.ts.net:8443/apps
   # → 401
   ```
   The second `curl` only works on the box (it reads the env file). From the phone's browser,
   opening `https://serverche.tail8236e9.ts.net:8443/health` is enough to prove the TLS front.

## Deploy

```
./deploy-server.sh develop      # fetch origin/develop into a temp worktree and deploy that
./deploy-server.sh              # deploy the tree you have checked out (for trying a branch)
./deploy-server.sh --rollback   # see Rollback
```

What `./deploy-server.sh develop` does, and what each line it prints proves:

1. Preflight: env file present, readable, has a non-empty `HUSH_TOKEN=`; data dir exists and is
   writable. Fails before touching Docker if not.
2. `>> Deploying origin/develop @ <sha> — <subject>` — exactly what is being built.
3. Tags the current image `hush-server:previous`, builds the new image from the repo root
   (`server/Dockerfile`; `.dockerignore` keeps the context at a few hundred KB), replaces the
   container: `--restart unless-stopped`, `--env-file ~/.config/hush-server.env`,
   `--user $(id -u):$(id -g)`, `-v ~/.local/share/hush-server:/data`, `-p 127.0.0.1:8082:8080`.
4. Checks that `/data` really is a bind mount of the data dir (a wrong mount would silently
   start an empty archive inside the container — this check is why).
5. `>> /health: {"ok":true,"rows":N,...}` — the server is up and `N` is the row count in the
   file that is mounted. After a redeploy `N` must be what it was before; `0` on a non-empty
   archive means the wrong directory is mounted.
6. `>> /apps with wrong token: 401, with the env token: 200` — auth is on and the token in the
   env file is the one the server accepts. `503` would mean the token never reached the
   container (fail closed by design).
7. `>> Deployed. Phones reach it at https://serverche.tail8236e9.ts.net:8443 ...` — reminder that
   the `tailscale serve` mapping is a separate, one-time step.

Overridable via environment (defaults in brackets): `SERVER_PORT` [8082], `HUSH_ENV_FILE`
[`~/.config/hush-server.env`], `HUSH_DATA_DIR` [`~/.local/share/hush-server`], `HUSH_SERVE_URL`
(only used in the final message).

## Backup

The database is in WAL mode. `hush.db-wal` and `hush.db-shm` appear next to `hush.db` while a
connection is open and are folded back into `hush.db` on checkpoint; seeing them or not seeing
them is both normal. **Never back up by copying `hush.db` alone while the server runs** — the
newest rows may still be in the WAL. Use SQLite's online backup, which is safe while the
container is running:

```
BACKUP_DIR="$HOME/backups/hush"; mkdir -p "$BACKUP_DIR"
sqlite3 "$HOME/.local/share/hush-server/hush.db" ".backup $BACKUP_DIR/hush-$(date +%F).db"
```

(`$HOME`, not `~`: neither bash nor sqlite3 expands a tilde inside double quotes, and sqlite3
would silently create a directory literally named `~`.) That produces one self-contained file
(no `-wal`). Copy it off the box however you like. A cron line is all the retention the archive
needs — note that `%` must be escaped as `\%` in a crontab:

```
17 4 * * * sqlite3 "$HOME/.local/share/hush-server/hush.db" ".backup $HOME/backups/hush/hush-$(date +\%F).db" && find "$HOME/backups/hush" -name 'hush-*.db' -mtime +30 -delete
```

The server keeps everything forever, the phone keeps 30 days.

If `sqlite3` is missing: `sudo apt install sqlite3`. Alternatively stop the container
(`docker stop hush-server`), copy all three files `hush.db*`, `docker start hush-server`.

## Restore

```
docker stop hush-server
cd ~/.local/share/hush-server && mv hush.db "hush.db.broken-$(date +%F)"   # keep it until verified
cd ~/.local/share/hush-server && rm -f hush.db-wal hush.db-shm               # stale WAL must not be replayed
cp ~/backups/hush/hush-2026-11-08.db ~/.local/share/hush-server/hush.db
sqlite3 ~/.local/share/hush-server/hush.db "INSERT INTO notification_fts(notification_fts) VALUES('rebuild');"
docker start hush-server
curl http://127.0.0.1:8082/health             # rows = what you expect from that backup
```

(The `cd … &&` chains mean a typo in the path can never run `mv`/`rm` in the wrong directory. The
rebuild runs before the server reopens the file so no request ever sees the stale index.)

The **FTS rebuild line is mandatory** after a restore, and after any `VACUUM`: the search index
is an external-content FTS5 table keyed on `notification`'s implicit rowid, and a rewritten file
may renumber rowids. Without the rebuild, search quietly returns the wrong rows. Running it when
it was not needed is harmless (it just takes O(rows)). The server itself never vacuums.

The phone re-pushes anything the server does not have: ids are deterministic and the server does
`INSERT OR IGNORE`, so after a restore from an older backup the phone's last 30 days flow back in
on the next sync and nothing is duplicated. Rows older than 30 days that were only on the server
are gone with the backup gap — which is why the backup cron matters.

## Rollback

```
./deploy-server.sh --rollback
```

Re-runs the container from `hush-server:previous` with the identical flags, then runs the same
`/health` + auth verification as a deploy. Nothing is built. Notes:

- `previous` is tagged at the start of every build, so it exists from the second deploy on, and
  it is **overwritten by the next deploy attempt** — roll back right after a bad deploy, before
  trying another build. If a build failed before `docker build` finished, `previous` and the
  running image are the same and the rollback is a harmless no-op.
- A newer build may have bumped the schema (`PRAGMA user_version`). The older server leaves a
  file whose `user_version` is ahead of it untouched and logs a warning; it may then fail on
  routes that need the new columns. In that case fix forward rather than rolling back.
- The data dir is never touched by deploy or rollback; the archive is exactly where it was.

## Troubleshooting (server side)

| Symptom | Meaning | Do |
|---|---|---|
| Gated routes answer `503 {"error":"server not configured"}`; log says `HUSH_TOKEN not set` | the token did not reach the container (env file missing/blank, or started without `--env-file`) | fix `~/.config/hush-server.env`, redeploy with the script |
| `401 {"error":"missing or invalid bearer token"}` with the token you think is right | phone/laptop token ≠ env file (trailing whitespace, old token after a rotation) | re-read the env file, re-enter it in Settings → Server, "Test connection" |
| Phone says "Can't reach server" | TLS front not up, wrong URL/port, phone off the tailnet | `tailscale serve status` on the box; `curl https://serverche.tail8236e9.ts.net:8443/health` from the laptop; the URL in Settings must be `https://…:8443` with no path |
| Pushes answer 500 with "database is locked" | something outside the server held a write lock > 5 s (`busy_timeout` is 5000 ms per connection) | finish/stop the external `sqlite3` session or backup; the phone retries by itself |
| `/health` says `rows: 0` after a redeploy on a non-empty archive | wrong directory mounted at `/data` | the script's mount check should have caught it; verify `HUSH_DATA_DIR`, `docker inspect hush-server` |
| Log: `user_version ... ahead` | the file was written by a newer build (after a rollback) | fix forward (deploy the newer build again) |
| Search returns wrong/no rows after a restore or `VACUUM` | FTS index out of step with the table | run the FTS rebuild line from Restore |
| Container restarts in a loop | see `docker logs --tail 100 hush-server` | the usual causes are the env file and the data dir permissions (container runs as your uid) |

Useful commands: `docker ps --filter name=hush-server`, `docker logs -f hush-server`,
`curl http://127.0.0.1:8082/health`, `sqlite3 ~/.local/share/hush-server/hush.db
'select count(*), max(post_time) from notification;'`.
