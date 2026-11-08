#!/usr/bin/env bash
# Rebuild and redeploy the hush-server container on serverche, then verify it.
#
#   ./deploy-server.sh              # build + deploy the currently checked-out tree
#   ./deploy-server.sh develop      # fetch origin/develop into a temp worktree and deploy that
#
# The archive DB lives on the HOST at $DATA_DIR and is bind-mounted onto /data; the container runs
# as the invoking user so the SQLite file (WAL mode) stays owned by them. The bearer token lives
# ONLY in $ENV_FILE (chmod 600) as HUSH_TOKEN=... and reaches the container via --env-file.
# The container publishes on 127.0.0.1 ONLY; `tailscale serve` terminates TLS in front of it
# (one-time host step, see STATUS.md "How to run"), so the phone talks HTTPS and the bearer is never
# in clear on the tailnet.
set -euo pipefail
cd "$(dirname "$0")"
REPO_ROOT="$PWD"

SERVER_PORT="${SERVER_PORT:-8082}"          # 8080 = tracker-server, 8081 = apk host (all apps)
CONTAINER="hush-server"; IMAGE="hush-server"
ENV_FILE="${HUSH_ENV_FILE:-$HOME/.config/hush-server.env}"
DATA_DIR="${HUSH_DATA_DIR:-$HOME/.local/share/hush-server}"
DATA_DIR="${DATA_DIR%/}"
VERIFY_BASE="http://127.0.0.1:$SERVER_PORT"
SERVE_URL="${HUSH_SERVE_URL:-https://serverche.tail8236e9.ts.net:8443}"
CURL=(--connect-timeout 5 --max-time 20)

die() { echo "error: $*" >&2; exit 1; }
DEPLOY_WT=""
cleanup() { if [[ -n "$DEPLOY_WT" ]]; then git -C "$REPO_ROOT" worktree remove --force "$DEPLOY_WT" >/dev/null 2>&1 || true; fi; }
trap cleanup EXIT

# --- preflight ---
[[ -f "$ENV_FILE" ]] || die "env file not found: $ENV_FILE (needs HUSH_TOKEN=<random>, chmod 600)"
[[ -r "$ENV_FILE" ]] || die "env file $ENV_FILE is not readable"
TOKEN_LINE="$(grep -m1 '^HUSH_TOKEN=' "$ENV_FILE" || true)"
TOKEN="${TOKEN_LINE#HUSH_TOKEN=}"
[[ -n "$TOKEN" ]] || die "no HUSH_TOKEN= in $ENV_FILE — the server would fail closed (503) on every gated route"
mkdir -p "$DATA_DIR" || die "could not create $DATA_DIR"
[[ -w "$DATA_DIR" ]] || die "data dir $DATA_DIR is not writable by $(id -un)"

# --- resolve target ---
TARGET="${1:-}"; BUILD_DIR="$REPO_ROOT"; LABEL="checked-out tree"
if [[ -n "$TARGET" ]]; then
    DEPLOY_WT="$REPO_ROOT/worktrees/_deploy-build"
    git fetch --quiet origin "$TARGET"
    git worktree remove --force "$DEPLOY_WT" >/dev/null 2>&1 || true
    git worktree add --detach "$DEPLOY_WT" "origin/$TARGET" >/dev/null
    BUILD_DIR="$DEPLOY_WT"; LABEL="origin/$TARGET"
fi
echo ">> Deploying $LABEL @ $(git -C "$BUILD_DIR" rev-parse --short HEAD) — $(git -C "$BUILD_DIR" log -1 --format='%s')"

# --- build + run ---
docker image inspect "$IMAGE" >/dev/null 2>&1 && docker tag "$IMAGE" "$IMAGE:previous" || true
docker build -f "$BUILD_DIR/server/Dockerfile" -t "$IMAGE" "$BUILD_DIR"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" --restart unless-stopped \
    --env-file "$ENV_FILE" --user "$(id -u):$(id -g)" \
    -v "$DATA_DIR:/data" -p "127.0.0.1:$SERVER_PORT:8080" "$IMAGE" >/dev/null

# --- verify ---
mount="$(docker inspect "$CONTAINER" --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Type}} {{.Source}}{{end}}{{end}}')"
[[ "$mount" == "bind $DATA_DIR" ]] || die "data dir is NOT bind-mounted at /data (found: '$mount')"
for i in $(seq 1 30); do
    if curl -fs "${CURL[@]}" "$VERIFY_BASE/health" >/dev/null 2>&1; then break; fi
    sleep 1
    [[ $i -lt 30 ]] || { docker logs --tail 40 "$CONTAINER" >&2; die "/health did not come up on $VERIFY_BASE"; }
done
echo ">> /health: $(curl -s "${CURL[@]}" "$VERIFY_BASE/health")"
# Auth smoke: wrong bearer must be 401, right bearer must not be 401/503.
code_bad="$(curl -s -o /dev/null -w '%{http_code}' "${CURL[@]}" -H 'Authorization: Bearer nope' "$VERIFY_BASE/apps" || true)"
code_ok="$(curl -s -o /dev/null -w '%{http_code}' "${CURL[@]}" -H "Authorization: Bearer $TOKEN" "$VERIFY_BASE/apps" || true)"
echo ">> /apps with wrong token: $code_bad, with the env token: $code_ok"
[[ "$code_ok" != 401 && "$code_ok" != 503 ]] || die "the env token is rejected by the server — token/env mismatch"
echo ">> Deployed. Phones reach it at $SERVE_URL (tailscale serve must map it to 127.0.0.1:$SERVER_PORT)."
