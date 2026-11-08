#!/usr/bin/env bash
# Build the debug APK and publish it for the phone to download over Tailscale.
#
#   ./publish-apk.sh                # build the currently checked-out tree
#   ./publish-apk.sh <pr-number>    # build the head branch of GitHub PR <pr-number>
#   ./publish-apk.sh <branch-name>  # build origin/<branch-name>
#
# Copies the APK to this app's dir under the shared APK host root (~/apk-host/hush) under a
# versioned name and makes sure the one nginx container (`tracker-apk`, shared with tracker-app)
# serves the whole root on the TAILNET IP ONLY (never another interface). The root is a
# multi-app site: ~/apk-host/index.html lists every app and ~/apk-host/<app>/ holds that app's
# page, builds.tsv, apks/ and latest.apk. Both pages are rendered by the shared scripts in
# ~/.claude/bin (dotclaude repo): apk-app-index.sh and apk-host-index.sh. Then open the printed
# URL on the phone (Tailscale up, "install unknown apps" allowed for the browser) and tap to install.
# Adapted from tracker-app/publish-apk.sh; same pattern, own subdir under the same host.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$PWD"

JAVA_HOME="${JAVA_HOME:-$HOME/toolchain/jdk-17.0.13+11}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export JAVA_HOME ANDROID_HOME

TAILSCALE_IP="${TAILSCALE_IP:-100.102.96.122}"   # serverche on the tailnet
PORT="${APK_PORT:-8081}"                          # the one APK host; shared with tracker-app
APP_SLUG="hush"                                   # this app's dir + URL path under the host root
HOST_ROOT="${APK_HOST_ROOT:-$HOME/apk-host}"      # what the container serves (all apps)
HOST_DIR="${APK_HOST_DIR:-$HOST_ROOT/$APP_SLUG}"  # where THIS app's builds live
CONTAINER="tracker-apk"                           # one container serves every app; its name predates the multi-app layout
RENDER_BIN="${APK_RENDER_BIN:-$HOME/.claude/bin}" # apk-app-index.sh + apk-host-index.sh
KEEP="${APK_KEEP:-10}"

TARGET="${1:-}"
LABEL=""
BUILD_DIR="$REPO_ROOT"
if [[ -n "$TARGET" ]]; then
    if [[ "$TARGET" =~ ^[0-9]+$ ]]; then
        BRANCH="$(gh pr view "$TARGET" --json headRefName -q .headRefName)"
        LABEL="PR #$TARGET — $(gh pr view "$TARGET" --json title -q .title)"
    else
        BRANCH="$TARGET"; LABEL="branch $BRANCH"
    fi
    WT="worktrees/_publish-build"
    echo ">> Fetching origin/$BRANCH into $WT ..."
    git fetch --quiet origin "$BRANCH"
    git worktree remove --force "$WT" >/dev/null 2>&1 || true
    git worktree add --detach "$WT" "origin/$BRANCH" >/dev/null
    [[ -f "$REPO_ROOT/local.properties" ]] && cp "$REPO_ROOT/local.properties" "$REPO_ROOT/$WT/local.properties"
    BUILD_DIR="$REPO_ROOT/$WT"
fi

APK_SRC="$BUILD_DIR/app/build/outputs/apk/debug/app-debug.apk"
echo ">> Building :app:assembleDebug ${LABEL:+($LABEL)} ..."
(cd "$BUILD_DIR" && ./gradlew :app:assembleDebug)
[[ -f "$APK_SRC" ]] || { echo "error: APK not found at $APK_SRC" >&2; exit 1; }

APK_DIR="$HOST_DIR/apks"; MANIFEST="$HOST_DIR/builds.tsv"
mkdir -p "$APK_DIR"
TS="$(date '+%Y%m%d-%H%M%S')"
SHA="$(git -C "$BUILD_DIR" rev-parse --short HEAD 2>/dev/null || echo nogit)"
BUILT_AT="$(date '+%Y-%m-%d %H:%M:%S %Z')"
DISPLAY_LABEL="${LABEL:-$(git -C "$BUILD_DIR" symbolic-ref -q --short HEAD 2>/dev/null || echo "$SHA") (HEAD)}"
APK_NAME="hush-${TS}-${SHA}.apk"
cp "$APK_SRC" "$APK_DIR/.$APK_NAME.tmp" && mv -f "$APK_DIR/.$APK_NAME.tmp" "$APK_DIR/$APK_NAME"
SIZE="$(du -h "$APK_DIR/$APK_NAME" | cut -f1)"
ln -sfn "apks/$APK_NAME" "$HOST_DIR/latest.apk"

PREV_SHA=""; [[ -f "$MANIFEST" ]] && PREV_SHA="$(tail -n 1 "$MANIFEST" | cut -f6)"
collect_changes() {
    local cand cands=()
    if [[ -n "$TARGET" && "$TARGET" =~ ^[0-9]+$ ]]; then
        cands=("origin/$(gh pr view "$TARGET" --json baseRefName -q .baseRefName 2>/dev/null)" origin/main origin/develop)
    else
        cands=("$PREV_SHA" origin/main origin/develop)
    fi
    for cand in "${cands[@]}"; do
        [[ -n "$cand" ]] || continue
        git -C "$BUILD_DIR" rev-parse --verify --quiet "${cand}^{commit}" >/dev/null 2>&1 || continue
        if git -C "$BUILD_DIR" log --no-merges --format='%s' "$cand..HEAD" 2>/dev/null | grep -q .; then
            git -C "$BUILD_DIR" log --no-merges --format='%s' "$cand..HEAD"; return
        fi
    done
    git -C "$BUILD_DIR" log --no-merges --format='%s' -n 5
}
CHANGES="$(collect_changes | tr -d '\037' | paste -sd $'\037' -)"
printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$TS" "$APK_NAME" "$DISPLAY_LABEL" "$BUILT_AT" "$SIZE" "$SHA" "$CHANGES" >> "$MANIFEST"

# Prune to the last $KEEP builds. `${var:?}` aborts instead of expanding to "/" if a var is empty.
if [[ "$KEEP" -gt 0 ]]; then
    total="$(wc -l < "$MANIFEST")"
    if (( total > KEEP )); then
        head -n "$(( total - KEEP ))" "$MANIFEST" | cut -f2 | while read -r old; do
            [[ -n "$old" ]] && rm -f -- "${APK_DIR:?}/${old:?}"
        done
        tail -n "$KEEP" "$MANIFEST" > "$MANIFEST.tmp" && mv -f "$MANIFEST.tmp" "$MANIFEST"
    fi
fi

# Regenerate this app's page (newest build at the top) and the root page listing every app.
# Both renderers live in the dotclaude repo; a missing one is a warning, never a failed publish:
# the APK is already in place and latest.apk already points at it.
if [[ -x "$RENDER_BIN/apk-app-index.sh" ]]; then
    "$RENDER_BIN/apk-app-index.sh" "$HOST_DIR" "Hush"
else
    echo "warning: $RENDER_BIN/apk-app-index.sh not found — $HOST_DIR/index.html NOT regenerated (pull the dotclaude repo)" >&2
fi
if [[ -x "$RENDER_BIN/apk-host-index.sh" ]]; then
    "$RENDER_BIN/apk-host-index.sh" "$HOST_ROOT"
else
    echo "warning: $RENDER_BIN/apk-host-index.sh not found — $HOST_ROOT/index.html (app list) NOT regenerated" >&2
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo ">> (Re)starting $CONTAINER on $TAILSCALE_IP:$PORT ..."
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker run -d --name "$CONTAINER" --restart unless-stopped -p "$TAILSCALE_IP:$PORT:80" \
        -v "$HOST_ROOT:/usr/share/nginx/html:ro" nginx:alpine >/dev/null
fi
if [[ -n "$TARGET" ]]; then git worktree remove --force "worktrees/_publish-build" >/dev/null 2>&1 || true; fi
echo; echo ">> Published: $DISPLAY_LABEL"
echo ">> On the phone (Tailscale on): http://$TAILSCALE_IP:$PORT/$APP_SLUG/   (or http://serverche:$PORT/$APP_SLUG/)"
