# #10 — Server: static archive search page

## Status

`GET /` now serves `server/src/main/resources/static/index.html` (Ktor `staticResources("/", "static")`
mounted in `Application.module` as a sibling of `archiveRoutes`, so it is open like `/health`; the
bearer gate on `/notifications` and `/apps` is unchanged). One self-contained HTML file: vanilla
JS/CSS, no framework, no CDN, no request leaves the origin.

Route table addition:

| Route | Body / params | Answer |
|---|---|---|
| `GET /` | — | the search page, `text/html` (open) |

Using it: open `https://serverche.tail8236e9.ts.net:8443/` from any tailnet device. The page asks
for `HUSH_TOKEN` once and keeps it in that browser's `localStorage` (in-memory fallback when
storage is blocked); "change token" in the header re-prompts; any 401 from the API clears the
stored token and re-prompts with the status. While the prompt is open with no token stored it also
fetches `/health` unauthenticated and shows "server reachable (N rows), token missing" vs
"server unreachable / HTTP x", so a bad URL and a missing token look different.

Filters map 1:1 to `GET /notifications`: search box → `q`, app dropdown (filled from `/apps`,
labelled `appLabel (count)`) → `package`, the two `datetime-local` pickers → `from` / `to` as epoch
ms in the browser's local time zone (`to` is passed as picked, so it is exclusive at that instant,
as the server defines it). Results are the server's order (newest first) with app label, local time,
event badge when not `posted`, title, `bigText ?: text`, subText; the query is highlighted after
HTML-escaping. "Load more" re-issues the same filters with `before=nextBefore` and appends; it is
hidden when `nextBefore` is null. States: loading (buttons disabled, previous in-flight request
aborted), "No matches", error with `HTTP <status>: <error body>` or the network error next to a
Retry button.

Test: `ArchiveRoutesTest."GET slash serves the search page without a token and the API stays gated"`
(200 + `text/html` without a bearer; `/notifications` and `/apps` still 401 in the same app). The
page's JS is not unit-tested: it is a thin fetch/render layer over routes that are tested, and a
browser harness would be disproportionate for one file.

## Decision

**[2026-11-08] Token lives in the browser's localStorage; 401 wipes it**
What: the page keeps `HUSH_TOKEN` in `localStorage['hushToken']` of whichever browser opened it,
sends it as `Authorization: Bearer` on every API call, and removes it the moment any call answers
401, re-opening the prompt with the status. No cookie, no server-side session, no query-string token.
Why: the server already has exactly one credential and one gate (issue #8), and the page is a
client of that gate like the phone is, so adding a cookie/session layer would mean a second auth
path to keep fail-closed. localStorage keeps the token out of URLs (history, logs, `tailscale serve`
access logs) and the wipe-on-401 rule is what keeps a rotated token from trapping the user behind a
stale one. Trade-off accepted: a shared browser profile can read the token; the page is only
reachable over the tailnet TLS front anyway.

**[2026-11-08] Escape-then-highlight, no HTML from the server**
What: every rendered field is HTML-escaped first; the highlight regex is built from the escaped and
regex-quoted query and wraps matches in `<mark>` inside the already-escaped string. The API never
returns HTML.
Why: notification text is attacker-influenced (anyone who can message the owner controls it). Doing
the escaping as the first step makes it impossible for a later rendering tweak to leak raw text into
`innerHTML`.
