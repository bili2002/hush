package com.hush.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import java.security.MessageDigest

/** Thrown by the gate when no token is configured → 503. The server never serves the archive open. */
class NotConfigured : RuntimeException("server not configured")

/** Thrown by the gate when the bearer is absent or wrong → 401. */
class Unauthorized : RuntimeException("missing or invalid bearer token")

/**
 * Route-scoped bearer gate (issue #8). Install it on every route node except `/health`; each
 * install covers that node and all of its children, so a new handler under a gated node cannot be
 * added without inheriting the gate.
 *
 * Fail CLOSED: [token] null (HUSH_TOKEN unset/blank) → [NotConfigured] for every request, so the
 * phone sees a distinct 503 ("the server isn't set up") rather than a 401 ("your token is wrong").
 * The compare is constant-time over SHA-256 digests: the token is human-managed and may be
 * low-entropy, which is exactly where a timing oracle bites.
 */
fun bearerAuth(token: String?) = createRouteScopedPlugin("HushBearerAuth") {
    onCall { call ->
        val expected = token ?: throw NotConfigured()
        val presented = bearerToken(call) ?: throw Unauthorized()
        if (!constantTimeEquals(expected, presented)) throw Unauthorized()
    }
}

/** The token from `Authorization: Bearer <token>` (scheme case-insensitive, RFC 6750), or null. */
internal fun bearerToken(call: ApplicationCall): String? {
    val header = call.request.header("Authorization") ?: return null
    val parts = header.trim().split(" ", limit = 2)
    if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
    return parts[1].trim().takeIf { it.isNotEmpty() }
}

/**
 * Constant-time compare: both sides are hashed to fixed-length SHA-256 digests first, so neither
 * the length nor the position of the first differing byte leaks through timing, and
 * [MessageDigest.isEqual] (constant-time for equal-length inputs since JDK 6u17) does the rest.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    val sha = MessageDigest.getInstance("SHA-256")
    val da = sha.digest(a.toByteArray(Charsets.UTF_8))
    val db = sha.digest(b.toByteArray(Charsets.UTF_8))
    return MessageDigest.isEqual(da, db)
}
