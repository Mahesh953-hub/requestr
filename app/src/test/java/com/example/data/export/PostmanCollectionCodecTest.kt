package com.example.data.export

import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiKeyLocation
import com.example.data.models.ApiRequestItem
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Master plan §1.6 — Postman Collection Schema v2.1.0 codec.
 *
 * Uses Robolectric because [PostmanCollectionCodec] round-trips through `org.json`, and the
 * plain android.jar stub on the unit-test classpath does not actually implement `org.json`
 * parsing (see [com.example.flow.FlowSerializerRoundTripTest]'s identical rationale).
 */
@RunWith(RobolectricTestRunner::class)
class PostmanCollectionCodecTest {

    private fun sampleRequest(folderId: String? = null) = ApiRequestItem(
        id = "req-1",
        name = "Get Users",
        folderId = folderId,
        method = HttpMethod.POST,
        url = "https://api.example.com/v1/users",
        queryParams = listOf(
            KeyValueItem(key = "page", value = "1", isEnabled = true),
            KeyValueItem(key = "debug", value = "1", isEnabled = false),
        ),
        headers = listOf(
            KeyValueItem(key = "Accept", value = "application/json", isEnabled = true),
        ),
        authType = AuthType.BEARER,
        bearerToken = "{{authToken}}",
        bodyType = RequestBodyType.JSON,
        bodyContent = "{\"name\":\"Ada\"}",
    )

    // ── Export shape ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `export produces the v2_1 schema URL and info name`() {
        val json = PostmanCollectionCodec.export("My Collection", listOf(sampleRequest()))
        val root = JSONObject(json)
        assertEquals(PostmanCollectionCodec.SCHEMA_V21, root.getJSONObject("info").getString("schema"))
        assertEquals("My Collection", root.getJSONObject("info").getString("name"))
        assertTrue(root.getJSONObject("info").has("_postman_id"))
    }

    @Test
    fun `export nests a request under its folder`() {
        val folder = ApiFolderItem(id = "f-1", name = "Users API")
        val json = PostmanCollectionCodec.export(
            "Coll", listOf(sampleRequest(folderId = "f-1")), listOf(folder)
        )
        val root = JSONObject(json)
        val items = root.getJSONArray("item")
        assertEquals(1, items.length())
        val folderItem = items.getJSONObject(0)
        assertEquals("Users API", folderItem.getString("name"))
        val nested = folderItem.getJSONArray("item")
        assertEquals(1, nested.length())
        assertEquals("Get Users", nested.getJSONObject(0).getString("name"))
    }

    @Test
    fun `export puts an unfoldered request at the top level`() {
        val json = PostmanCollectionCodec.export("Coll", listOf(sampleRequest(folderId = null)))
        val items = JSONObject(json).getJSONArray("item")
        assertEquals(1, items.length())
        assertEquals("Get Users", items.getJSONObject(0).getString("name"))
        assertFalse(items.getJSONObject(0).has("item"))
    }

    @Test
    fun `export writes headers body auth and query`() {
        val json = PostmanCollectionCodec.export("Coll", listOf(sampleRequest()))
        val request = JSONObject(json).getJSONArray("item").getJSONObject(0).getJSONObject("request")

        assertEquals(1, request.getJSONArray("header").length())
        assertEquals("Accept", request.getJSONArray("header").getJSONObject(0).getString("key"))

        val body = request.getJSONObject("body")
        assertEquals("raw", body.getString("mode"))
        assertEquals("{\"name\":\"Ada\"}", body.getString("raw"))

        val auth = request.getJSONObject("auth")
        assertEquals("bearer", auth.getString("type"))
        assertEquals("{{authToken}}", auth.getJSONArray("bearer").getJSONObject(0).getString("value"))

        val url = request.getJSONObject("url")
        assertTrue(url.getString("raw").contains("page=1"))
        assertFalse(url.getString("raw").contains("debug=1"))
        assertEquals(2, url.getJSONArray("query").length())
    }

    // ── Import: realistic collection ─────────────────────────────────────────────────────────

    private val realisticCollection = """
        {
          "info": {
            "_postman_id": "abc-123",
            "name": "Realistic Collection",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
            "unknownField": "should be ignored"
          },
          "item": [
            {
              "name": "Top Level Ping",
              "request": {
                "method": "GET",
                "header": [{"key": "X-Test", "value": "1", "disabled": false}],
                "url": "https://example.com/ping?x=1"
              }
            },
            {
              "name": "Users Folder",
              "item": [
                {
                  "name": "Structured URL Get",
                  "request": {
                    "method": "GET",
                    "header": [],
                    "url": {
                      "raw": "https://example.com/users/{{userId}}?active=true",
                      "protocol": "https",
                      "host": ["example", "com"],
                      "path": ["users", "{{userId}}"],
                      "query": [{"key": "active", "value": "true", "disabled": false}]
                    },
                    "auth": {
                      "type": "bearer",
                      "bearer": [{"key": "token", "value": "{{authToken}}", "type": "string"}]
                    }
                  }
                },
                {
                  "name": "Urlencoded Body",
                  "request": {
                    "method": "POST",
                    "url": "https://example.com/form",
                    "body": {
                      "mode": "urlencoded",
                      "urlencoded": [{"key": "a", "value": "1"}, {"key": "b", "value": "2", "disabled": true}]
                    }
                  }
                },
                {
                  "name": "Formdata Body",
                  "request": {
                    "method": "POST",
                    "url": "https://example.com/upload",
                    "body": {
                      "mode": "formdata",
                      "formdata": [{"key": "file", "value": "data", "type": "text"}]
                    }
                  }
                },
                {
                  "name": "Raw Json Body",
                  "request": {
                    "method": "POST",
                    "url": "https://example.com/raw",
                    "body": {
                      "mode": "raw",
                      "raw": "{\"a\":1}",
                      "options": {"raw": {"language": "json"}}
                    }
                  }
                },
                {
                  "name": "GraphQL Body",
                  "request": {
                    "method": "POST",
                    "url": "https://example.com/graphql",
                    "body": {
                      "mode": "graphql",
                      "graphql": {"query": "{ users { id } }", "variables": "{}"}
                    }
                  }
                },
                {
                  "name": "Basic Auth",
                  "request": {
                    "method": "GET",
                    "url": "https://example.com/basic",
                    "auth": {
                      "type": "basic",
                      "basic": [{"key": "username", "value": "bob"}, {"key": "password", "value": "secret"}]
                    }
                  }
                },
                {
                  "name": "ApiKey Header Auth",
                  "request": {
                    "method": "GET",
                    "url": "https://example.com/apikey-header",
                    "auth": {
                      "type": "apikey",
                      "apikey": [{"key": "key", "value": "X-Api-Key"}, {"key": "value", "value": "secret123"}, {"key": "in", "value": "header"}]
                    }
                  }
                },
                {
                  "name": "ApiKey Query Auth",
                  "request": {
                    "method": "GET",
                    "url": "https://example.com/apikey-query",
                    "auth": {
                      "type": "apikey",
                      "apikey": [{"key": "key", "value": "apiKey"}, {"key": "value", "value": "secret456"}, {"key": "in", "value": "query"}]
                    }
                  }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `import parses a top-level request and a nested folder`() {
        val result = PostmanCollectionCodec.import(realisticCollection)
        assertEquals("Realistic Collection", result.collectionName)
        assertEquals(1, result.folders.size)
        assertEquals("Users Folder", result.folders.first().name)

        val topLevel = result.requests.first { it.name == "Top Level Ping" }
        assertNull(topLevel.folderId)

        val nested = result.requests.first { it.name == "Structured URL Get" }
        assertEquals(result.folders.first().id, nested.folderId)
    }

    @Test
    fun `import resolves a structured url object including query and leaves templates untouched`() {
        val result = PostmanCollectionCodec.import(realisticCollection)
        val req = result.requests.first { it.name == "Structured URL Get" }
        assertEquals("https://example.com/users/{{userId}}", req.url)
        assertEquals(1, req.queryParams.size)
        assertEquals("active", req.queryParams.first().key)
        assertEquals("true", req.queryParams.first().value)
        assertEquals(AuthType.BEARER, req.authType)
        assertEquals("{{authToken}}", req.bearerToken)
    }

    @Test
    fun `import resolves a raw string url with a query string`() {
        val result = PostmanCollectionCodec.import(realisticCollection)
        val req = result.requests.first { it.name == "Top Level Ping" }
        assertEquals("https://example.com/ping", req.url)
        assertEquals(1, req.queryParams.size)
        assertEquals("x", req.queryParams.first().key)
        assertEquals("1", req.queryParams.first().value)
    }

    @Test
    fun `import resolves urlencoded body including a disabled param`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "Urlencoded Body" }
        assertEquals(RequestBodyType.FORM, req.bodyType)
        assertEquals(2, req.formData.size)
        assertTrue(req.formData.any { it.key == "a" && it.isEnabled })
        assertTrue(req.formData.any { it.key == "b" && !it.isEnabled })
    }

    @Test
    fun `import resolves formdata body`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "Formdata Body" }
        assertEquals(RequestBodyType.MULTIPART, req.bodyType)
        assertEquals(1, req.formData.size)
        assertEquals("file", req.formData.first().key)
    }

    @Test
    fun `import resolves raw json body`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "Raw Json Body" }
        assertEquals(RequestBodyType.JSON, req.bodyType)
        assertEquals("{\"a\":1}", req.bodyContent)
    }

    @Test
    fun `import resolves graphql body`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "GraphQL Body" }
        assertEquals(RequestBodyType.GRAPHQL, req.bodyType)
        assertEquals("{ users { id } }", req.bodyContent)
    }

    @Test
    fun `import resolves basic auth`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "Basic Auth" }
        assertEquals(AuthType.BASIC, req.authType)
        assertEquals("bob", req.basicUsername)
        assertEquals("secret", req.basicPassword)
    }

    @Test
    fun `import resolves apikey auth in header location`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "ApiKey Header Auth" }
        assertEquals(AuthType.API_KEY, req.authType)
        assertEquals("X-Api-Key", req.apiKeyHeader)
        assertEquals("secret123", req.apiKeyValue)
        assertEquals(ApiKeyLocation.HEADER, req.apiKeyLocation)
    }

    @Test
    fun `import resolves apikey auth in query location`() {
        val req = PostmanCollectionCodec.import(realisticCollection).requests.first { it.name == "ApiKey Query Auth" }
        assertEquals(AuthType.API_KEY, req.authType)
        assertEquals(ApiKeyLocation.QUERY_PARAM, req.apiKeyLocation)
        assertEquals("secret456", req.apiKeyValue)
    }

    // ── Robustness ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `import never throws on unknown extra fields or a missing optional field`() {
        val json = """
            {
              "info": {"name": "Sparse", "schema": "x", "somethingWeird": {"nested": [1,2,3]}},
              "item": [
                {"name": "No body no auth", "request": {"method": "GET", "url": "https://example.com"}},
                {"weirdShape": true},
                {"name": "Not a request or folder"}
              ]
            }
        """.trimIndent()
        val result = PostmanCollectionCodec.import(json)
        assertEquals(1, result.requests.size)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `import of completely invalid json returns an error result rather than throwing`() {
        val result = PostmanCollectionCodec.import("{ not json at all")
        assertTrue(result.requests.isEmpty())
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `disabled header and query items are marked not enabled on import`() {
        val json = """
            {
              "info": {"name": "Disabled", "schema": "x"},
              "item": [{
                "name": "Req",
                "request": {
                  "method": "GET",
                  "header": [{"key": "X-Off", "value": "1", "disabled": true}],
                  "url": {"raw": "https://example.com?a=1", "query": [{"key": "a", "value": "1", "disabled": true}]}
                }
              }]
            }
        """.trimIndent()
        val req = PostmanCollectionCodec.import(json).requests.first()
        assertFalse(req.headers.first().isEnabled)
        assertFalse(req.queryParams.first().isEnabled)
    }

    // ── Round trip ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `export then import then export again is stable`() {
        val original = listOf(sampleRequest(folderId = "f-1"))
        val folders = listOf(ApiFolderItem(id = "f-1", name = "Folder A"))

        val firstJson = PostmanCollectionCodec.export("Coll", original, folders)
        val imported = PostmanCollectionCodec.import(firstJson)
        assertEquals(1, imported.requests.size)
        assertTrue(imported.errors.isEmpty())

        val importedFolderId = imported.folders.first().id
        val secondJson = PostmanCollectionCodec.export(
            "Coll", imported.requests, listOf(ApiFolderItem(id = importedFolderId, name = "Folder A"))
        )
        val reimported = PostmanCollectionCodec.import(secondJson)

        assertEquals(imported.requests.first().name, reimported.requests.first().name)
        assertEquals(imported.requests.first().method, reimported.requests.first().method)
        assertEquals(imported.requests.first().authType, reimported.requests.first().authType)
        assertEquals(imported.requests.first().bearerToken, reimported.requests.first().bearerToken)
        assertEquals(imported.requests.first().bodyContent, reimported.requests.first().bodyContent)
    }

    @Test
    fun `template variables survive a round trip verbatim`() {
        val req = sampleRequest().copy(
            url = "https://example.com/{{userId}}",
            bearerToken = "{{authToken}}",
            bodyContent = "{\"id\":\"{{userId}}\"}",
        )
        val json = PostmanCollectionCodec.export("Coll", listOf(req))
        val imported = PostmanCollectionCodec.import(json).requests.first()
        assertEquals("https://example.com/{{userId}}", imported.url)
        assertEquals("{{authToken}}", imported.bearerToken)
        assertEquals("{\"id\":\"{{userId}}\"}", imported.bodyContent)
    }
}
