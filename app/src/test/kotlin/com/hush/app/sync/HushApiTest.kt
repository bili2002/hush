package com.hush.app.sync

import com.hush.app.db.EventType
import com.hush.app.testing.event
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class HushApiTest {

    private val token = "synthetic-token-0123456789"
    private var lastRequest: HttpRequestData? = null

    private fun api(status: HttpStatusCode, body: String = "", throwing: Throwable? = null): HushApi {
        val engine = MockEngine { request ->
            lastRequest = request
            if (throwing != null) throw throwing
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        // Bare client on purpose: the API must not rely on client plugins.
        return KtorHushApi(HttpClient(engine))
    }

    private val items = listOf(
        event("id-1", postTime = 0, title = null, text = null, appLabel = "Alpha").toDto(),
        event("id-2", packageName = "com.example.beta", postTime = 42, event = EventType.REMOVED, bigText = "big").toDto(),
    )

    @Test
    fun `sends bearer header and exact JSON shape to baseUrl slash notifications`() = runTest {
        val api = api(HttpStatusCode.OK, """{"accepted":2,"duplicates":0,"serverTime":123}""")

        val result = api.push("https://example.test:8443/", token, "Pixel", items)

        assertEquals(PushResult.Ok(2, 0), result)
        val req = assertNotNull(lastRequest)
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://example.test:8443/notifications", req.url.toString())
        assertEquals("Bearer $token", req.headers[HttpHeaders.Authorization])
        assertTrue(req.body.contentType.toString().startsWith("application/json"))

        val expected = Json.parseToJsonElement(
            """
            {"device":"Pixel","items":[
              {"id":"id-1","key":"0|com.example.alpha|id-1|null|10000","packageName":"com.example.alpha","appLabel":"Alpha",
               "postTime":0,"title":null,"text":null,"bigText":null,"subText":null,"channelId":"default","category":null,
               "flags":0,"importance":3,"event":"posted","removedReason":null},
              {"id":"id-2","key":"0|com.example.beta|id-2|null|10000","packageName":"com.example.beta","appLabel":"Alpha",
               "postTime":42,"title":"title id-2","text":"text id-2","bigText":"big","subText":null,"channelId":"default","category":null,
               "flags":0,"importance":3,"event":"removed","removedReason":null}
            ]}
            """.trimIndent(),
        )
        assertEquals(expected, Json.parseToJsonElement(req.body.toByteArray().decodeToString()))
    }

    @Test
    fun `2xx parses accepted and duplicates and ignores unknown keys`() = runTest {
        val api = api(HttpStatusCode.Created, """{"accepted":1,"duplicates":4,"serverTime":5,"extra":true}""")
        assertEquals(PushResult.Ok(1, 4), api.push("https://h", token, "d", items))
    }

    @Test
    fun `401 and 403 map to Unauthorized`() = runTest {
        assertEquals(PushResult.SyncError.Unauthorized, api(HttpStatusCode.Unauthorized).push("https://h", token, "d", items))
        assertEquals(PushResult.SyncError.Unauthorized, api(HttpStatusCode.Forbidden).push("https://h", token, "d", items))
    }

    @Test
    fun `400 maps to Rejected with the server's error message`() = runTest {
        val r = api(HttpStatusCode.BadRequest, """{"error":"too many items"}""").push("https://h", token, "d", items)
        assertEquals(PushResult.SyncError.Rejected("too many items"), r)
    }

    @Test
    fun `4xx without a readable body falls back to the status code`() = runTest {
        val r = api(HttpStatusCode.NotFound, "<html>nope</html>").push("https://h", token, "d", items)
        assertEquals(PushResult.SyncError.Rejected("HTTP 404"), r)
    }

    @Test
    fun `5xx maps to Transient`() = runTest {
        val r = api(HttpStatusCode.ServiceUnavailable, """{"error":"server not configured"}""").push("https://h", token, "d", items)
        assertEquals(PushResult.SyncError.Transient("HTTP 503"), r)
    }

    @Test
    fun `IO exception maps to Transient with a short cause`() = runTest {
        val r = api(HttpStatusCode.OK, throwing = IOException("Connection refused")).push("https://h", token, "d", items)
        assertEquals(PushResult.SyncError.Transient("Connection refused"), r)
    }

    @Test
    fun `2xx with an unparseable body is Transient so the idempotent push is retried`() = runTest {
        val r = api(HttpStatusCode.OK, "not json").push("https://h", token, "d", items)
        assertIs<PushResult.SyncError.Transient>(r)
    }

    @Test
    fun `error messages never contain the token`() = runTest {
        val results = listOf(
            api(HttpStatusCode.BadRequest, """{"error":"bad"}""").push("https://h", token, "d", items),
            api(HttpStatusCode.InternalServerError).push("https://h", token, "d", items),
            api(HttpStatusCode.OK, throwing = IOException("boom")).push("https://h", token, "d", items),
        )
        results.forEach { assertFalse(it.toString().contains(token), "leaked token in $it") }
    }
}
