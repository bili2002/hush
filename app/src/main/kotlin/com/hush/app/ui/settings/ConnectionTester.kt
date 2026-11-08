package com.hush.app.ui.settings

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface TestResult {
    data object Ok : TestResult
    data object WrongToken : TestResult
    data class Unreachable(val cause: String) : TestResult
}

/**
 * `GET {url}/health` with the bearer. The token travels only in the header and is never logged or echoed.
 * Timeouts come from the shared client's engine (CIO defaults: 5 s connect, 15 s request); a virtual-time
 * `withTimeout` here would misfire under `runTest`, and the engine's limits are the ones a real sync hits too.
 */
class ConnectionTester @Inject constructor(private val client: HttpClient) {

    suspend fun test(url: String, token: String): TestResult {
        val base = url.trim().trimEnd('/')
        if (base.isEmpty()) return TestResult.Unreachable("server URL is empty")
        return try {
            val response = client.get("$base/health") { header(HttpHeaders.Authorization, "Bearer $token") }
            when {
                response.status.isSuccess() -> TestResult.Ok
                response.status == HttpStatusCode.Unauthorized -> TestResult.WrongToken
                else -> TestResult.Unreachable("HTTP ${response.status.value}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestResult.Unreachable(e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "unknown error"))
        }
    }
}
