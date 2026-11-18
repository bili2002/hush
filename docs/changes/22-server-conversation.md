# 22 — Server: conversation id/title in the archive

## Status
The archive stores the conversation identity the phone derives since #20: `notification` has two
new nullable columns, `conversation_id` and `conversation_title`; `POST /notifications` accepts
`conversationId` / `conversationTitle` (optional, so an older phone build still syncs) and
`GET /notifications` returns them. The static search page is unchanged.

Schema version is now 2. An existing v1 file is upgraded in place on the next start
(`ArchiveDatabaseFactory.migrate`): two `ALTER TABLE … ADD COLUMN`, no row is rewritten, the FTS
index is untouched. **Not deployed by this PR**: run `./deploy-server.sh develop` when ready; back
up `~/.local/share/hush-server` first if you want a pre-migration copy.

Rows pushed between the phone getting #20 and the server getting this change were stored without
the two fields and stay that way (the phone does not re-push synced rows).

## Decision

**[2026-11-18] Server migrations are hand-written, idempotent steps keyed on `user_version`**
What: `ensureSchema` handles `0` (create), `SCHEMA_VERSION` (nothing) and anything in between via
`migrate(driver, from)`. Each step checks before it changes (`PRAGMA table_info` before
`ADD COLUMN`) and the version stamp is written last.
Why: same crash-safety rule as the bootstrap: a start that dies halfway must be finished by the
next start, not wedge on "duplicate column name". SQLDelight's `.sqm` machinery is not used here
because the schema is already created outside it (FTS5 via raw SQL, `IF NOT EXISTS` everywhere).

**[2026-11-18] New columns go last in `CREATE TABLE`**
What: `conversation_id` / `conversation_title` are declared after `received_at`.
Why: `ADD COLUMN` appends, and both the generated `SELECT *` mapper and the positional raw-SQL
mapper for the FTS path depend on column order being the same on fresh and migrated files.
