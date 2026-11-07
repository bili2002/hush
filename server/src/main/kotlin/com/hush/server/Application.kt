package com.hush.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Binds 0.0.0.0 INSIDE the container; the host-side boundary is `docker run -p 127.0.0.1:...`.
    embeddedServer(Netty, port = port, module = Application::module).start(wait = true)
}

@Serializable
data class Health(val ok: Boolean, val service: String = "hush-server")

fun Application.module() {
    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    routing {
        get("/health") { call.respond(Health(ok = true)) }
    }
}
