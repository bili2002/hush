package com.hush.app.ui.settings

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface TestResult {
    data object Ok : TestResult
    data object WrongToken : TestResult
    /** The server runs but has no `HUSH_TOKEN`: gated routes fail closed with 503. */
    data object ServerNotConfigured : TestResult
    data class Unreachable(val cause: String) : TestResult
}

/**
 * `GET {url}/apps` with the bearer: a gated route, so a wrong token is a 401 (`/health` is open and would
 * say OK to anything). The token travels only in the header and is never logged or echoed.
 * Timeouts come from the shared client's engine (CIO defaults: 5 s connect, 15 s request); a virtual-time
 * `withTimeout` here would misfire under `runTest`, and the engine's limits are the ones a real sync hits too.
 */
class ConnectionTester @Inject constructor(private val client: HttpClient) {

    /** [url] and [token] are expected already trimmed (the ViewModel trims once for both test and save). */
    suspend fun test(url: String, token: String): TestResult {
        val base = url.trimEnd('/')
        if (base.isEmpty()) return TestResult.Unreachable("server URL is empty")
        return try {
            val response = client.get("$base/apps") { header(HttpHeaders.Authorization, "Bearer $token") }
            when {
                response.status.isSuccess() -> TestResult.Ok
                response.status == HttpStatusCode.Unauthorized -> TestResult.WrongToken
                response.status == HttpStatusCode.ServiceUnavailable -> TestResult.ServerNotConfigured
                else -> TestResult.Unreachable("HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestResult.Unreachable(describe(e))
        }
    }

    /** Plain words for the failures a phone actually sees; anything else keeps its own message. */
    private fun describe(e: Exception): String = when (e) {
        is UnresolvedAddressException -> "host not found"
        is ConnectException -> "connection refused"
        is ConnectTimeoutException, is SocketTimeoutException, is HttpRequestTimeoutException,
        is java.net.SocketTimeoutException -> "timed out"
        else -> e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "unknown error")
    }
}
