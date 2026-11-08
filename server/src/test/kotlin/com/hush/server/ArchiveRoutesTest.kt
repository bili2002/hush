package com.hush.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every route through the real [module] with an injected `:memory:` config (issue #8). Real
 * seams: real SQLite + FTS5, the real bearer gate, the real StatusPages mapping. Synthetic data only.
 */
class ArchiveRoutesTest {

    private val token = "synthetic-test-token"
    private val json = Json { ignoreUnknownKeys = true }

    private fun ApplicationTestBuilder.boot(token: String? = this@ArchiveRoutesTest.token) {
        application { module(ServerConfig(token = token, dbPath = ":memory:")) }
    }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient { install(ContentNegotiation) { json(this@ArchiveRoutesTest.json) } }

    private fun item(
        id: String, pkg: String = "pkg.example.alpha", postTime: Long = 1_000L, title: String? = "Synthetic $id",
        text: String? = "body of $id", label: String? = "Alpha", event: String = "posted",
    ) = NotificationDto(
        id = id, key = "0|$pkg|$id", packageName = pkg, appLabel = label, postTime = postTime, title = title,
        text = text, bigText = null, subText = null, channelId = "general", category = null, flags = 0,
        importance = 3, event = event, removedReason = null,
    )

    private fun pushBody(vararg items: NotificationDto, device: String = "test-phone") =
        Json.encodeToString(PushRequest(device = device, items = items.toList()))

    private suspend fun HttpClient.push(body: String, bearer: String? = token): HttpResponse =
        post("/notifications") {
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.page(query: String = "", bearer: String? = token): HttpResponse =
        get("/notifications${if (query.isNotEmpty()) "?$query" else ""}") {
            if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
        }

    private suspend fun HttpResponse.pageBody(): PageResponse = json.decodeFromString(bodyAsText())
    private suspend fun HttpResponse.pushBody(): PushResponse = json.decodeFromString(bodyAsText())

    // ------------------------------------------------------------------ auth

    @Test
    fun `token unset - gated routes answer 503 and health stays open`() = testApplication {
        boot(token = null)
        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        for (response in listOf(
            client.push(pushBody(item("n1")), bearer = "anything"),
            client.page(bearer = "anything"),
            client.get("/apps") { header(HttpHeaders.Authorization, "Bearer anything") },
        )) {
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("""{"error":"server not configured"}""", response.bodyAsText())
        }
    }

    @Test
    fun `wrong or missing bearer answers 401 on every gated route`() = testApplication {
        boot()
        val client = jsonClient()
        for (response in listOf(
            client.push(pushBody(item("n1")), bearer = "wrong"),
            client.push(pushBody(item("n1")), bearer = null),
            client.page(bearer = "wrong"),
            client.get("/apps"),
            client.get("/apps") { header(HttpHeaders.Authorization, "Basic $token") },
        )) {
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertTrue(response.bodyAsText().contains("\"error\""))
        }
        // Nothing got stored through a rejected push.
        assertEquals(0L, json.decodeFromString<Health>(client.get("/health").bodyAsText()).rows)
    }

    @Test
    fun `bearer scheme is case-insensitive`() = testApplication {
        boot()
        val response = jsonClient().get("/apps") { header(HttpHeaders.Authorization, "bearer $token") }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ------------------------------------------------------------------ push

    @Test
    fun `push 3 then re-push the same 3 - second push is all duplicates`() = testApplication {
        boot()
        val client = jsonClient()
        val body = pushBody(item("n1"), item("n2"), item("n3"))

        val first = client.push(body)
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = first.pushBody()
        assertEquals(3, firstBody.accepted)
        assertEquals(0, firstBody.duplicates)
        assertTrue(firstBody.serverTime > 0)

        val second = client.push(body).pushBody()
        assertEquals(0, second.accepted)
        assertEquals(3, second.duplicates)
        assertEquals(3L, json.decodeFromString<Health>(client.get("/health").bodyAsText()).rows)
    }

    @Test
    fun `same id with different content keeps the first row and counts a duplicate`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(item("n1", title = "original")))
        val replay = client.push(pushBody(item("n1", title = "rewritten"))).pushBody()
        assertEquals(PushResponse(accepted = 0, duplicates = 1, serverTime = replay.serverTime), replay)
        assertEquals("original", client.page().pageBody().items.single().title)
    }

    @Test
    fun `more than 1000 items is a 400`() = testApplication {
        boot()
        val items = (1..1001).map { item("bulk-$it") }.toTypedArray()
        val response = jsonClient().push(pushBody(*items))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("too many items"))
    }

    @Test
    fun `exactly 1000 items is accepted`() = testApplication {
        boot()
        val items = (1..1000).map { item("bulk-$it") }.toTypedArray()
        assertEquals(1000, jsonClient().push(pushBody(*items)).pushBody().accepted)
    }

    @Test
    fun `malformed JSON is a 400 with an error body, not a 500`() = testApplication {
        boot()
        val client = jsonClient()
        for (body in listOf("{not json", """{"device":"x","items":[{"id":1}]}""", """{"items":[]}""")) {
            val response = client.push(body)
            assertEquals(HttpStatusCode.BadRequest, response.status, "body: $body")
            // Fixed body: the parser's message (which can quote the request) is never echoed.
            assertEquals("""{"error":"malformed request body"}""", response.bodyAsText(), "body: $body")
        }
    }

    @Test
    fun `a non-JSON content type is a 415`() = testApplication {
        boot()
        val response = jsonClient().post("/notifications") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Text.Plain)
            setBody(pushBody(item("n1")))
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals("""{"error":"request body must be application/json"}""", response.bodyAsText())
    }

    // ------------------------------------------------------------------ search / paging

    @Test
    fun `search by q matches title and text through FTS and carries the device`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(
            item("n1", title = "Re-sync finished", text = "all good"),
            item("n2", title = "Lunch", text = "she said \"hi there\" twice"),
            item("n3", title = "Other", text = "nothing relevant"),
            device = "phone-one",
        ))
        val bySpecialChars = client.page("q=re-sync").pageBody()
        assertEquals(listOf("n1"), bySpecialChars.items.map { it.id })
        assertEquals("phone-one", bySpecialChars.items.single().device)

        val byQuotedPhrase = client.page("q=" + "said \"hi there\"".urlEncoded()).pageBody()
        assertEquals(listOf("n2"), byQuotedPhrase.items.map { it.id })

        assertEquals(emptyList(), client.page("q=unicorn").pageBody().items)
    }

    @Test
    fun `operator-looking queries never 500`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(item("n1", title = "a:b", text = "star* dash-dash")))
        for (q in listOf("-", "*", "\"", ":", "a:b", "star*", "NEAR", "OR", "(x OR y)", "***", "\"\"")) {
            val response = client.page("q=" + q.urlEncoded())
            assertEquals(HttpStatusCode.OK, response.status, "q=$q")
            assertNull(response.pageBody().nextBefore, "q=$q")
        }
        assertEquals(listOf("n1"), client.page("q=" + "a:b".urlEncoded()).pageBody().items.map { it.id })
    }

    @Test
    fun `empty q is a plain page, newest first`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(item("old", postTime = 100), item("new", postTime = 300), item("mid", postTime = 200)))
        assertEquals(listOf("new", "mid", "old"), client.page("q=").pageBody().items.map { it.id })
        assertEquals(listOf("new", "mid", "old"), client.page().pageBody().items.map { it.id })
    }

    @Test
    fun `filter by package and by time window, with and without q`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(
            item("a1", pkg = "pkg.example.alpha", postTime = 100, text = "shared word"),
            item("a2", pkg = "pkg.example.alpha", postTime = 200, text = "shared word"),
            item("b1", pkg = "pkg.example.beta", postTime = 300, text = "shared word"),
            item("b2", pkg = "pkg.example.beta", postTime = 400, text = "different"),
        ))
        assertEquals(listOf("b2", "b1"), client.page("package=pkg.example.beta").pageBody().items.map { it.id })
        assertEquals(listOf("b1", "a2"), client.page("from=150&to=350").pageBody().items.map { it.id })
        assertEquals(listOf("b1"), client.page("q=shared&from=150&to=350&package=pkg.example.beta").pageBody().items.map { it.id })
        assertEquals(listOf("a2", "a1"), client.page("q=shared&package=pkg.example.alpha").pageBody().items.map { it.id })
        assertEquals(emptyList(), client.page("package=pkg.example.gamma").pageBody().items)
    }

    @Test
    fun `paging via nextBefore walks every row once and ends with null`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(*(1..7).map { item("p$it", postTime = it * 10L) }.toTypedArray()))

        val seen = mutableListOf<String>()
        var before: Long? = null
        var pages = 0
        do {
            val page = client.page("limit=3" + (before?.let { "&before=$it" } ?: "")).pageBody()
            seen += page.items.map { it.id }
            before = page.nextBefore
            pages++
        } while (before != null && pages < 10)
        assertEquals((7 downTo 1).map { "p$it" }, seen)
        assertEquals(3, pages) // 3 + 3 + 1; the short page carries nextBefore=null
    }

    @Test
    fun `paging with q goes through the FTS path too`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(*(1..5).map { item("p$it", postTime = it * 10L, text = "needle") }.toTypedArray()))
        val first = client.page("q=needle&limit=2").pageBody()
        assertEquals(listOf("p5", "p4"), first.items.map { it.id })
        assertEquals(40L, first.nextBefore)
        val second = client.page("q=needle&limit=2&before=40").pageBody()
        assertEquals(listOf("p3", "p2"), second.items.map { it.id })
    }

    @Test
    fun `limit is capped at 500 and floored at 1, bad numbers are 400`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(*(1..3).map { item("p$it", postTime = it * 10L) }.toTypedArray()))
        // limit=0 → 1 row and a cursor; limit=9999 → everything (3 rows), short page → null cursor.
        val floored = client.page("limit=0").pageBody()
        assertEquals(1, floored.items.size)
        assertNotNull(floored.nextBefore)
        val capped = client.page("limit=9999").pageBody()
        assertEquals(3, capped.items.size)
        assertNull(capped.nextBefore)
        assertEquals(HttpStatusCode.BadRequest, client.page("limit=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.page("before=yesterday").status)
        assertEquals(HttpStatusCode.BadRequest, client.page("from=1&to=x").status)
    }

    @Test
    fun `a full page of exactly 500 is returned when asked`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(*(1..501).map { item("p$it", postTime = it.toLong()) }.toTypedArray()))
        val page = client.page("limit=500").pageBody()
        assertEquals(500, page.items.size)
        assertEquals(2L, page.nextBefore)
        assertEquals(1, client.page("limit=500&before=2").pageBody().items.size)
    }

    // ------------------------------------------------------------------ apps / health

    @Test
    fun `apps aggregates per package - count desc then package asc`() = testApplication {
        boot()
        val client = jsonClient()
        client.push(pushBody(
            item("a1", pkg = "pkg.example.alpha", postTime = 100, label = "Alpha"),
            item("a2", pkg = "pkg.example.alpha", postTime = 250, label = "Alpha"),
            item("b1", pkg = "pkg.example.beta", postTime = 300, label = "Beta"),
            item("c1", pkg = "pkg.example.gamma", postTime = 50, label = null),
        ))
        val apps = json.decodeFromString<List<AppSummary>>(
            client.get("/apps") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText(),
        )
        assertEquals(
            listOf(
                AppSummary("pkg.example.alpha", "Alpha", 2, 250),
                AppSummary("pkg.example.beta", "Beta", 1, 300),
                AppSummary("pkg.example.gamma", null, 1, 50),
            ),
            apps,
        )
    }

    @Test
    fun `health reports ok and the row count`() = testApplication {
        boot()
        val client = jsonClient()
        assertEquals(Health(ok = true, rows = 0), json.decodeFromString<Health>(client.get("/health").bodyAsText()))
        client.push(pushBody(item("n1"), item("n2")))
        assertEquals(Health(ok = true, rows = 2), json.decodeFromString<Health>(client.get("/health").bodyAsText()))
    }

    @Test
    fun `removed events and null text fields round-trip`() = testApplication {
        boot()
        val client = jsonClient()
        val removed = item("r1", title = null, text = null, label = null, event = "removed").copy(removedReason = 2, importance = null)
        client.push(pushBody(removed))
        val stored = client.page().pageBody().items.single()
        assertEquals("removed", stored.event)
        assertEquals(2, stored.removedReason)
        assertNull(stored.title)
        assertNull(stored.importance)
        assertNull(stored.appLabel)
    }

    // ------------------------------------------------------------------ static page (issue #10)

    @Test
    fun `GET slash serves the search page without a token and the API stays gated`() = testApplication {
        boot()
        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.contentType()?.match(ContentType.Text.Html) == true, "content type was ${page.contentType()}")
        val html = page.bodyAsText()
        assertTrue(html.contains("<title>Hush"), "page title missing")
        assertTrue(html.contains("/notifications") && html.contains("/apps"), "page does not call the API")
        // Mounting the static root did not widen the bearer gate.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/notifications").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/apps").status)
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)
}
