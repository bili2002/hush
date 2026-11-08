package com.hush.server

import com.hush.server.db.ArchiveDatabaseFactory
import com.hush.server.db.ArchiveRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Binds 0.0.0.0 INSIDE the container; the host-side boundary is `docker run -p 127.0.0.1:...`.
    embeddedServer(Netty, port = port) { module() }.start(wait = true)
}

/**
 * The whole server. [config] defaults to the environment; tests pass their own with `:memory:`.
 * One archive per application instance, closed when the application stops.
 */
fun Application.module(config: ServerConfig = ServerConfig.fromEnv()) {
    if (config.token == null) {
        log.warn("${ServerConfig.ENV_TOKEN} not set — gated routes answer 503")
    }
    val archive = ArchiveDatabaseFactory.open(config.dbPath)
    monitor.subscribe(ApplicationStopped) { archive.close() }
    val repo = ArchiveRepository(archive)

    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(StatusPages) { hushErrorPages() }
    routing {
        archiveRoutes(config, repo)
    }
}
