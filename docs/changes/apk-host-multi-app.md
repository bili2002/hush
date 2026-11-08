# apk-host-multi-app — Hush joins the shared APK download site

## Status

Hush no longer gets its own nginx container (`hush-apk` on 8083 was never started). It is the
second app on the tailnet APK host serverche already runs for tracker-app:

- `http://100.102.96.122:8081/` — main page, one card per app (Tracker, Hush), each with its blurb
  and newest build. Rendered by `~/.claude/bin/apk-host-index.sh` (dotclaude repo).
- `http://100.102.96.122:8081/hush/` — Hush's build list (newest first, what's-new bullets,
  "No builds yet" until the first publish). Rendered by `~/.claude/bin/apk-app-index.sh`.
- `http://100.102.96.122:8081/hush/latest.apk` — stable pointer to the newest build.
- `~/apk-host/hush/{builds.tsv,apks/,app.meta}` — manifest, APKs, and the two-line
  `name=`/`blurb=` meta the main page reads. Created on 2026-11-08, empty manifest.

`publish-apk.sh` writes to `~/apk-host/hush` (`HOST_DIR`, overridable via `APK_HOST_DIR`; the root
via `APK_HOST_ROOT`), (re)starts the shared container `tracker-apk` on 8081 only if it is not
running, mounting the ROOT `~/apk-host`, and calls the two shared renderers after each publish
instead of carrying its own HTML. A missing renderer is a warning, not a failed publish.
