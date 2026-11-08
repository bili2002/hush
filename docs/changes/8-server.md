# #8 — Server: archive DB with FTS5, bearer auth, push/search/apps routes

## Status

The `:server` module is now the real archive API (Ktor 3.0.1, SQLite via SQLDelight-jdbc, xerial
sqlite-jdbc 3.45.2 which bundles FTS5). One container, one SQLite file.

Environment (unchanged names; `Dockerfile` and `deploy-server.sh` untouched):
- `HUSH_TOKEN` — shared bearer secret. Unset/blank → the server logs
  `HUSH_TOKEN not set — gated routes answer 503` once at startup and every gated route answers
  `503 {"error":"server not configured"}`. Never serves open.
- `HUSH_DB_PATH` — SQLite file (Docker sets `/data/hush.db`; outside Docker defaults to
  `~/.local/share/hush-server/hush.db`). `:memory:` for tests.
- `PORT` — defaults to 8080.

Routes (all JSON; everything except `/health` needs `Authorization: Bearer <HUSH_TOKEN>`, wrong or
missing bearer → `401 {"error":"missing or invalid bearer token"}`):

| Route | Body / params | Answer |
|---|---|---|
| `GET /health` | — | `{"ok":true,"rows":<n>,"service":"hush-server"}` (open; `deploy-server.sh` probes it) |
| `POST /notifications` | `{"device":"…","items":[NotificationDto…]}`, max 1000 items | `{"accepted":n,"duplicates":m,"serverTime":ms}`; `400 {"error":…}` on >1000 items, malformed JSON, missing required fields |
| `GET /notifications` | `q`, `package`, `from`, `to`, `before`, `limit` (default 100, clamped 1..500) | `{"items":[ArchiveItem…],"nextBefore":ms\|null}` newest first; non-numeric number param → 400 |
| `GET /apps` | — | `[{"packageName","appLabel","count","lastSeen"}]`, `count DESC, packageName ASC` |

`NotificationDto` = `{id, key, packageName, appLabel?, postTime, title?, text?, bigText?, subText?,
channelId?, category?, flags, importance?, event, removedReason?}` — identical to the app's DTO
(issue #5 pins the wire JSON). `ArchiveItem` = the same fields plus `device`.

Schema (`server/src/main/sqldelight/com/hush/server/db/Archive.sq`, replaces `Meta.sq`): table
`notification` (id TEXT PK + the DTO columns in snake_case + `device`, `received_at`), indexes
`(post_time)` and `(package_name, post_time)`. FTS5 external-content table `notification_fts(title,
text, big_text)` + triggers `notification_ai/ad/au`, created by raw SQL in
`ArchiveDatabaseFactory` (SQLDelight cannot parse `CREATE VIRTUAL TABLE`). `PRAGMA user_version = 1`.

Tests: `server/src/test/kotlin/com/hush/server/ArchiveRoutesTest.kt` (19 route tests through the
real `module(ServerConfig(token, ":memory:"))`), `db/ArchiveDatabaseFactoryTest.kt` (FTS5 smoke,
bootstrap guard, WAL on files, reopen), `db/FtsPhraseTest.kt`. Synthetic data only.

### Ops notes (for the restore / troubleshooting doc, issue #9)
- **WAL mode** is set once on the file and is persistent; expect `hush.db`, `hush.db-wal`,
  `hush.db-shm` in the data dir. Back up with `sqlite3 hush.db ".backup out.db"` (or stop the
  container first), never by copying only `hush.db` while it runs.
- **Busy timeout is 5000 ms** (`ArchiveDatabaseFactory.BUSY_TIMEOUT_MS`): each request thread has
  its own connection to the file, so a push and a search that collide wait up to 5 s instead of
  failing with SQLITE_BUSY. If pushes start answering 500 with "database is locked", something
  outside the server is holding a write lock on the file for longer than that.
- **Never `VACUUM` this database without rebuilding the FTS index right after.** The FTS5 table is
  external-content keyed on `notification.rowid`, and `notification` has a TEXT primary key, so its
  rowids are implicit and `VACUUM` may renumber them. After any `VACUUM` (or a restore that
  rewrote rowids) run:
  `sqlite3 hush.db "INSERT INTO notification_fts(notification_fts) VALUES('rebuild');"`.
  The server itself never vacuums and leaves `auto_vacuum` at its default (off). Running the
  rebuild when it was not needed is harmless, just O(rows).
- **Schema bootstrap is retryable**: every DDL statement is `IF NOT EXISTS` and `user_version` is
  stamped last, so a container killed mid-bootstrap recovers on the next start. A file whose
  `user_version` is AHEAD of the build (rolled-back deploy) is left untouched with a warning in
  the log.

## Decision

**[2026-11-08] FTS5 via raw SQL on the driver, not in `.sq`**
What: `Archive.sq` holds the plain table, indexes and typed queries; the virtual table, its three
content-sync triggers and the `MATCH` search are raw strings executed on the `SqlDriver` in
`ArchiveDatabaseFactory` / `ArchiveRepository`. The search SQL is a fixed template with `?`
bindings only; the user's `q` is quoted as ONE FTS5 phrase (`"` + q with `"` doubled + `"`), so
`-`, `:`, `*`, `OR`, `NEAR` are literal text and can never reach the MATCH parser as syntax. A `q`
with no letter or digit at all (which tokenises to an empty phrase, an FTS5 syntax error) returns
an empty page without touching the DB.
Why: SQLDelight's SQLite dialect does not parse `CREATE VIRTUAL TABLE … USING fts5`, and the
alternative (a `LIKE` scan) stops being usable on a years-long archive. Keeping the index keyed on
the implicit rowid of the spec'd TEXT-PK table costs the VACUUM caveat above, which is documented
rather than worked around with a surrogate integer key that would change the agreed schema.

**[2026-11-08] Config injection: `ServerConfig` is the only env seam**
What: `ServerConfig(token: String?, dbPath: String)` with `fromEnv()` (blank token → null) and
`Application.module(config: ServerConfig = ServerConfig.fromEnv())`. Nothing else calls
`System.getenv` except `PORT` in `main`. The gate is a route-scoped plugin installed on the
`/notifications` and `/apps` nodes; `/health` is a sibling and stays open.
Why: route tests must boot "token unset" and "token set" in one JVM, each with its own `:memory:`
archive, and no test may depend on the Gradle `environment(...)` lines (kept only for the default
path). The 503 vs 401 split lets the phone say "server not set up" vs "wrong token" instead of a
generic failure (surface-don't-swallow).

**[2026-11-08] Idempotency over last-write-wins on `POST /notifications`**
What: each item is `INSERT OR IGNORE` on `id` inside one transaction; `SELECT changes()` after each
insert yields `accepted`, the rest are `duplicates`. A repeated id with DIFFERENT content keeps the
first row and is counted as a duplicate, never an error and never an update. A hard failure rolls
the whole batch back and answers 500, which the phone treats as transient and retries.
Why: the phone's ids are deterministic per notification event, so a differing body under the same
id is a replay or a bug, not a correction; letting it overwrite would let a stale retry rewrite
history. Counting duplicates (rather than rejecting them) is what makes a half-acknowledged push
self-healing: the phone re-sends, the server says "already have those", the phone marks them synced.
