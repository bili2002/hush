package com.hush.app.ui.settings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class ConnectionTesterTest {

    private val token = "synthetic-token-0123456789"

    private fun tester(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ConnectionTester(HttpClient(MockEngine { request -> handler(request) }))

    @Test
    fun `hits the gated apps route with the bearer and trims a trailing slash`() = runTest {
        var seen: HttpRequestData? = null
        val tester = tester { seen = it; respond("""{"ok":true}""") }

        assertEquals(TestResult.Ok, tester.test("https://example.invalid:8443/", token))
        assertEquals("https://example.invalid:8443/apps", seen!!.url.toString())
        assertEquals("Bearer $token", seen!!.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `401 is WrongToken`() = runTest {
        val tester = tester { respondError(HttpStatusCode.Unauthorized) }
        assertEquals(TestResult.WrongToken, tester.test("https://example.invalid", token))
    }

    @Test
    fun `503 is the server's fail-closed no-token state`() = runTest {
        val tester = tester { respondError(HttpStatusCode.ServiceUnavailable) }
        assertEquals(TestResult.ServerNotConfigured, tester.test("https://example.invalid", token))
    }

    @Test
    fun `other statuses are Unreachable with the code`() = runTest {
        val tester = tester { respondError(HttpStatusCode.BadGateway) }
        assertEquals(TestResult.Unreachable("HTTP 502"), tester.test("https://example.invalid", token))
    }

    @Test
    fun `common transport failures become plain words`() = runTest {
        assertEquals(TestResult.Unreachable("host not found"), tester { throw UnresolvedAddressException() }.test("https://nope.invalid", token))
        assertEquals(TestResult.Unreachable("connection refused"), tester { throw ConnectException("Connection refused") }.test("https://example.invalid", token))
        assertEquals(TestResult.Unreachable("timed out"), tester { throw SocketTimeoutException("Read timed out") }.test("https://example.invalid", token))
    }

    @Test
    fun `transport failure is Unreachable with the cause and never the token`() = runTest {
        val tester = tester { throw IllegalStateException("TLS handshake failed") }
        val result = tester.test("https://example.invalid", token)
        assertIs<TestResult.Unreachable>(result)
        assertEquals("TLS handshake failed", result.cause)
        assertFalse(result.toString().contains(token))
    }

    @Test
    fun `blank url does not even try`() = runTest {
        val tester = tester { error("must not be called") }
        assertIs<TestResult.Unreachable>(tester.test("   ", token))
    }
}
