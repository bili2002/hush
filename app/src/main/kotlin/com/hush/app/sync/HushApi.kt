package com.hush.app.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface PushResult {
    data class Ok(val accepted: Int, val duplicates: Int) : PushResult

    sealed interface SyncError : PushResult {
        /** 401/403: the token is wrong. Retrying cannot help. */
        data object Unauthorized : SyncError

        /** Other 4xx, a redirect, or an unusable URL: retrying the same request cannot help. */
        data class Rejected(val message: String) : SyncError

        /** 5xx, I/O, timeout, unparseable reply: try again later. */
        data class Transient(val message: String) : SyncError
    }
}

interface HushApi {
    suspend fun push(baseUrl: String, token: String, device: String, items: List<NotificationDto>): PushResult
}

/**
 * `POST {baseUrl}/notifications` with a bearer token.
 *
 * Serialises with its own [Json] rather than the client's ContentNegotiation so the contract does not
 * depend on how the injected client is configured. The client must not have the `Logging` plugin:
 * the request carries the bearer token and notification content. Error messages never include the token.
 */
class KtorHushApi @Inject constructor(private val client: HttpClient) : HushApi {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun push(baseUrl: String, token: String, device: String, items: List<NotificationDto>): PushResult {
        if (!looksLikeHttpUrl(baseUrl)) return PushResult.SyncError.Rejected(CHECK_URL)
        val body = json.encodeToString(PushRequest.serializer(), PushRequest(device, items))
        return try {
            val response = client.post(baseUrl.trimEnd('/') + "/notifications") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(TextContent(body, ContentType.Application.Json))
            }
            val status = response.status
            when {
                status.isSuccess() -> parseOk(response.bodyAsText())
                // A redirect means the URL points at something that is not the API (a login page, a proxy).
                status.value in 300..399 -> PushResult.SyncError.Rejected(CHECK_URL)
                status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> PushResult.SyncError.Unauthorized
                status.value in 400..499 -> PushResult.SyncError.Rejected(errorMessage(response.bodyAsText(), status))
                // 5xx: surface the server's own reason when it gives one (e.g. 503 "server not configured").
                else -> PushResult.SyncError.Transient(errorMessage(response.bodyAsText(), status))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: URLParserException) {
            PushResult.SyncError.Rejected(CHECK_URL)
        } catch (e: IllegalArgumentException) {
            PushResult.SyncError.Rejected(CHECK_URL)
        } catch (e: Exception) {
            // I/O, timeout, TLS, DNS: all "try again later". The message never includes the token.
            PushResult.SyncError.Transient(shortCause(e))
        }
    }

    /** Ktor treats a scheme-less string as a path relative to localhost, so check the shape ourselves. */
    private fun looksLikeHttpUrl(s: String): Boolean {
        if ("://" !in s) return false
        val url = try { Url(s) } catch (e: Exception) { return false }
        return url.protocol.name in setOf("http", "https") && url.host.isNotBlank()
    }

    /** A 2xx with an unreadable body is retried: the push is idempotent, so re-sending is harmless. */
    private fun parseOk(text: String): PushResult = try {
        val r = json.decodeFromString(PushResponse.serializer(), text)
        PushResult.Ok(r.accepted, r.duplicates)
    } catch (e: Exception) {
        PushResult.SyncError.Transient("Bad reply from server")
    }

    private fun errorMessage(text: String, status: HttpStatusCode): String {
        val fromBody = try {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
        return fromBody?.takeIf { it.isNotBlank() }?.let(::truncate) ?: "HTTP ${status.value}"
    }

    private fun shortCause(e: Exception): String =
        truncate(e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "error"))

    private fun truncate(s: String) = if (s.length <= MAX_MESSAGE) s else s.take(MAX_MESSAGE - 1) + "…"

    private companion object {
        const val MAX_MESSAGE = 120
        const val CHECK_URL = "Check the server URL"
    }
}
