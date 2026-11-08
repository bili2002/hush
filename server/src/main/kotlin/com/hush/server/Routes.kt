package com.hush.server

import com.hush.server.db.ArchiveRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A request the server refuses on its own terms (too many items, a non-numeric parameter) → 400. */
class BadRequest(message: String) : RuntimeException(message)

const val MAX_PUSH_ITEMS = 1000
const val DEFAULT_PAGE_LIMIT = 100L
const val MAX_PAGE_LIMIT = 500L

/**
 * The archive API (issue #8). `/health` is open (deploy-server.sh probes it without a token); the
 * three data routes sit behind [bearerAuth]. No delete/edit/pull routes by design.
 */
fun Route.archiveRoutes(config: ServerConfig, repo: ArchiveRepository, clock: () -> Long = System::currentTimeMillis) {
    get("/health") {
        call.respond(Health(ok = true, rows = withContext(Dispatchers.IO) { repo.count() }))
    }

    route("/notifications") {
        install(bearerAuth(config.token))

        post {
            val body = call.receive<PushRequest>()
            if (body.items.size > MAX_PUSH_ITEMS) throw BadRequest("too many items: ${body.items.size} > $MAX_PUSH_ITEMS")
            val now = clock()
            val counts = withContext(Dispatchers.IO) { repo.insertAll(body.device, body.items, receivedAt = now) }
            call.respond(PushResponse(accepted = counts.accepted, duplicates = counts.duplicates, serverTime = now))
        }

        get {
            val q = call.request.queryParameters["q"]
            val packageName = call.request.queryParameters["package"]?.takeIf { it.isNotBlank() }
            val from = call.longParam("from")
            val to = call.longParam("to")
            val before = call.longParam("before") ?: Long.MAX_VALUE
            val limit = (call.longParam("limit") ?: DEFAULT_PAGE_LIMIT).coerceIn(1L, MAX_PAGE_LIMIT)
            val items = withContext(Dispatchers.IO) { repo.page(q, packageName, from, to, before, limit) }
            // A full page MAY have more behind it; a short page is the end. Ties at the same
            // millisecond across a page boundary are skipped — accepted for a notification archive.
            val nextBefore = if (items.size.toLong() == limit) items.last().postTime else null
            call.respond(PageResponse(items = items, nextBefore = nextBefore))
        }
    }

    route("/apps") {
        install(bearerAuth(config.token))
        get { call.respond(withContext(Dispatchers.IO) { repo.apps() }) }
    }
}

private fun ApplicationCall.longParam(name: String): Long? {
    val raw = request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return null
    return raw.toLongOrNull() ?: throw BadRequest("$name must be an integer")
}

/** Every exception → status mapping, in one place so tests exercise the production mappings. */
fun StatusPagesConfig.hushErrorPages() {
    exception<NotConfigured> { call, cause ->
        call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody(cause.message ?: "server not configured"))
    }
    exception<Unauthorized> { call, cause ->
        call.respond(HttpStatusCode.Unauthorized, ErrorBody(cause.message ?: "unauthorized"))
    }
    exception<BadRequest> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorBody(cause.message ?: "bad request"))
    }
    // Ktor raises this when the JSON body cannot be parsed into the request type.
    exception<BadRequestException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ErrorBody("malformed request body: ${cause.cause?.message ?: cause.message ?: "unreadable"}"))
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("unhandled error on ${call.request.local.uri}", cause)
        call.respond(HttpStatusCode.InternalServerError, ErrorBody(cause.message ?: "internal error"))
    }
}
