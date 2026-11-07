# Hush — project notes for Claude

Android app that records every notification on the phone, mirrors the history to the owner's
home server, and in **Focus mode** holds back chosen apps' notifications and delivers them as one
digest on a schedule (hourly by default).

Solo developer; code is authored by Claude workers picking up GitHub issues. Read `STATUS.md`
first for where we are, then `DECISIONS.md` for "why", then `IDEAS.md` for "what's next".
`README.md` covers install/run details for humans.

## Key facts
- Two Gradle modules: `:app` (Android, Kotlin + Compose + Hilt + SQLDelight + WorkManager) and
  `:server` (Ktor 3 + SQLite via SQLDelight-jdbc, runs as a Docker container on serverche).
- Versions in `gradle/libs.versions.toml` are copied from tracker-app and are proven on this box.
  Don't bump them in a feature PR.
- Package `com.hush.app` / `com.hush.server`. minSdk 26, compileSdk/targetSdk 35.
- **No secrets in the repo, ever.** The server bearer token lives only in
  `~/.config/hush-server.env` on the box (`HUSH_TOKEN=...`) and in the app's Settings screen on the
  phone (DataStore). The server URL is typed in-app too. Public repo.
- **No real notification content in issues, PRs, tests or docs.** Fixtures are synthetic.
- The listener (`NotificationListenerService`) is the one OS-facing piece. Keep it thin: callbacks
  delegate to pure, tested logic.

## Commands (serverche)
```
export JAVA_HOME=~/toolchain/jdk-17.0.13+11 ANDROID_HOME=~/android-sdk
./gradlew :server:test :app:testDebugUnitTest :app:assembleDebug   # the green gate (same as CI)
./publish-apk.sh develop        # build origin/develop and park the APK on the tailnet for the phone
./deploy-server.sh develop      # rebuild + redeploy the hush-server container and verify /health + auth
```
APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Worker contract

If you are a Claude worker picking up an issue in this repo, follow these rules.

### Branching
- **`main`** = tested, runs on the phone. **The human promotes `develop` → `main`** in batches
  after on-device testing (`promote-develop` skill). `main` is the default branch, so `Closes #N`
  fires at promotion.
- **`develop`** = integration branch. All worker PRs target `develop`. Workers may merge their own
  PRs into `develop` only after the fresh-eyes review left no unresolved blockers and CI is green.
- Branch from `develop`. Branch name `issue-<N>-<short-slug>`.
- Per-issue worktrees live under `worktrees/` at the repo root (gitignored):
  `git worktree add worktrees/issue-<N>-<slug> -b issue-<N>-<slug> origin/develop`.
- PR labels: `in-progress` → `ready-for-review` → `needs-changes`; `needs-device-test` when the
  diff touches a runtime surface the CI gate can't see (UI, the listener, permissions, sync).

### Before opening a PR
- Run the green gate above. Never open a PR on a broken tree.
- If you changed architecture, transport, on-disk format, or anything captured in `STATUS.md` /
  `DECISIONS.md`, write the entry as a NEW file `docs/changes/<issue>-<slug>.md` (sections
  `## Status` / `## Decision`) in the same PR. Don't append to `STATUS.md` / `DECISIONS.md`
  directly: parallel PRs conflict there. Entries are folded in at promotion.
- Link the issue (`Closes #N`). Title = concise imperative summary. Target branch = `develop`.

### House rules
- **Conflict triage before parallel dispatch.** Two issues that touch the same files run
  sequentially, never in parallel.
- Don't push directly to `main` or `develop`. Always via PR.
- Don't skip hooks or force-push without the human's say-so.
- **Never lose a notification.** Persist first, hide second. If the DB write fails, let the
  notification show normally (fail open). Anything held must have a maximum age after which it is
  released regardless of schedule.
- **Surface errors, don't swallow them.** A failed sync/listener/permission state shows its real
  cause in the UI, next to a retry, never a bare "something went wrong".

### Tests
- `kotlin.test` + JUnit4 in `app/src/test` (Robolectric 4.14, SDK pinned to 34 in
  `robolectric.properties`); `kotlin.test` + Ktor `testApplication` in `server/src/test`.
- Unit-test pure logic (focus policy, slot maths, mapper/extractor, dedupe, retention).
  Robolectric for the service and worker seams. `testApplication` for every server route (auth
  401/503, idempotent push, search).
- Tests use synthetic notification data only.
- When tests would be disproportionate (copy tweak, styling), say so in the PR body.

### Tooling on this box (`serverche`)
- JDK 17 + Gradle 8.10.2 under `~/toolchain/`, Android SDK under `~/android-sdk`.
- `local.properties` is gitignored and has `sdk.dir=/home/bili2002/android-sdk`.
- Gradle daemon/config cache shared across worktrees; pass `--no-daemon` on lock errors.
