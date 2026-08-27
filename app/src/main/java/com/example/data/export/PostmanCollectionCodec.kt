package com.example.data.export

import com.example.data.models.ApiFolderItem
import com.example.data.models.ApiKeyLocation
import com.example.data.models.ApiRequestItem
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Master plan §1.6 — Postman Collection Schema v2.1.0 import/export.
 *
 * Before this, zero Postman-format code existed anywhere in requestr: the only saved-request
 * export path ([WorkspaceExporter]) is requestr's own ad-hoc JSON shape, not interoperable with
 * Postman/Insomnia/any other API client. This codec is the two-way bridge, modeled on
 * tangcent/easy-api's `PostmanCollection`/`PostmanItem`/`PostmanRequest`/`PostmanUrl`/
 * `PostmanHeader`/`PostmanBody`/`PostmanQuery`/`PostmanFormParam` data shapes (the plain data
 * classes below mirror those names/fields), backed by `org.json` for the same reason
 * [WorkspaceExporter] and [com.example.ui.screens.SettingsScreen]'s ad-hoc JSON already use it:
 * a real-world collection has unknown/extra fields, a `url` that is either a raw string OR the
 * structured object form, and optional fields that may simply be absent — `org.json`'s
 * `opt*`-family accessors make "skip and report, never throw" the natural default, where a
 * statically-typed Moshi adapter would need a hand-written fallback for every one of those
 * shapes anyway.
 *
 * `{{variable}}` templating: requestr already resolves `{{VAR}}` itself via
 * [com.example.data.env.EnvironmentRepository.substitute]. Postman uses the identical
 * `{{var}}` syntax for its own environment variables, so this codec never touches template
 * placeholders in either direction — a `{{token}}` in a Postman collection's URL/header/body
 * round-trips through import/export completely unchanged, ready for requestr's own substitution
 * step to resolve later at Send time.
 */
object PostmanCollectionCodec {

    /** `info.schema` for every collection this codec writes. */
    const val SCHEMA_V21 = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

    // ───────────────────────── Postman-shaped data classes ─────────────────────────

    data class PostmanCollection(
        val info: PostmanInfo,
        val item: List<PostmanItem> = emptyList(),
    )

    data class PostmanInfo(
        val postmanId: String = UUID.randomUUID().toString(),
        val name: String = "requestr export",
        val schema: String = SCHEMA_V21,
    )

    /** Either a folder (non-null [item], null [request]) or a leaf request (the reverse). */
    data class PostmanItem(
        val name: String = "",
        val item: List<PostmanItem>? = null,
        val request: PostmanRequest? = null,
    )

    data class PostmanRequest(
        val method: String = "GET",
        val header: List<PostmanHeader> = emptyList(),
        val body: PostmanBody? = null,
        val url: PostmanUrl = PostmanUrl(),
        val auth: PostmanAuth? = null,
    )

    data class PostmanUrl(
        val raw: String = "",
        val protocol: String? = null,
        val host: List<String> = emptyList(),
        val path: List<String> = emptyList(),
        val query: List<PostmanQuery> = emptyList(),
    )

    data class PostmanHeader(
        val key: String = "",
        val value: String = "",
        val disabled: Boolean = false,
    )

    data class PostmanQuery(
        val key: String = "",
        val value: String = "",
        val disabled: Boolean = false,
    )

    data class PostmanFormParam(
        val key: String = "",
        val value: String = "",
        val type: String = "text",
        val disabled: Boolean = false,
    )

    /** [mode] is one of `raw` / `urlencoded` / `formdata` / `graphql` per the v2.1 schema. */
    data class PostmanBody(
        val mode: String = "raw",
        val raw: String = "",
        val rawLanguage: String? = null,
        val urlencoded: List<PostmanFormParam> = emptyList(),
        val formdata: List<PostmanFormParam> = emptyList(),
        val graphqlQuery: String = "",
        val graphqlVariables: String = "",
    )

    /** [type] is one of `noauth` / `bearer` / `basic` / `apikey` — the subset requestr supports. */
    data class PostmanAuth(
        val type: String = "noauth",
        val attributes: List<PostmanAuthAttr> = emptyList(),
    )

    data class PostmanAuthAttr(
        val key: String = "",
        val value: String = "",
        val type: String = "string",
    )

    /** Result of [import]: parsed requests plus the folders they belong to, and any per-item errors. */
    data class ImportResult(
        val collectionName: String,
        val requests: List<ApiRequestItem>,
        val folders: List<ApiFolderItem>,
        val errors: List<String>,
    )

    // ─────────────────────────────── Export ───────────────────────────────

    /**
     * Exports [requests] (optionally grouped into [folders] by [ApiRequestItem.folderId]) as a
     * Postman Collection v2.1.0 JSON document. Requests with no matching/known folder stay at
     * the collection's top level; every folder that owns at least one request becomes one
     * nested Postman folder item (`item` containing an `item` array of its own).
     */
    fun export(collectionName: String, requests: List<ApiRequestItem>, folders: List<ApiFolderItem> = emptyList()): String {
        val collection = toPostmanCollection(collectionName, requests, folders)
        return collectionToJson(collection).toString(2)
    }

    private fun toPostmanCollection(
        collectionName: String,
        requests: List<ApiRequestItem>,
        folders: List<ApiFolderItem>,
    ): PostmanCollection {
        val folderNameById = folders.associateBy({ it.id }, { it.name })
        val byFolder = requests.groupBy { it.folderId }

        val topLevel = mutableListOf<PostmanItem>()
        byFolder[null]?.forEach { topLevel.add(requestToPostmanItem(it)) }

        byFolder.keys.filterNotNull().forEach { folderId ->
            val reqsInFolder = byFolder[folderId].orEmpty()
            if (reqsInFolder.isEmpty()) return@forEach
            topLevel.add(
                PostmanItem(
                    name = folderNameById[folderId] ?: folderId,
                    item = reqsInFolder.map { requestToPostmanItem(it) },
                )
            )
        }

        return PostmanCollection(info = PostmanInfo(name = collectionName), item = topLevel)
    }

    private fun requestToPostmanItem(req: ApiRequestItem): PostmanItem =
        PostmanItem(name = req.name, request = requestToPostmanRequest(req))

    private fun requestToPostmanRequest(req: ApiRequestItem): PostmanRequest = PostmanRequest(
        method = req.method.name,
        header = req.headers.map { PostmanHeader(key = it.key, value = it.value, disabled = !it.isEnabled) },
        body = requestBodyToPostmanBody(req),
        url = requestUrlToPostmanUrl(req),
        auth = requestAuthToPostmanAuth(req),
    )

    private fun requestUrlToPostmanUrl(req: ApiRequestItem): PostmanUrl {
        val enabledQuery = req.queryParams.filter { it.isEnabled && it.key.isNotBlank() }
        val queryString = enabledQuery.joinToString("&") { "${it.key}=${it.value}" }
        val raw = if (queryString.isBlank()) req.url
        else req.url + (if (req.url.contains('?')) "&" else "?") + queryString

        val schemeSep = req.url.indexOf("://")
        val protocol = if (schemeSep > 0) req.url.substring(0, schemeSep) else null
        val rest = if (schemeSep > 0) req.url.substring(schemeSep + 3) else req.url
        val slashIdx = rest.indexOf('/')
        val hostPart = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
        val pathPart = if (slashIdx >= 0) rest.substring(slashIdx + 1) else ""

        return PostmanUrl(
            raw = raw,
            protocol = protocol,
            host = hostPart.split('.').filter { it.isNotEmpty() },
            path = pathPart.split('/').filter { it.isNotEmpty() },
            query = req.queryParams.filter { it.key.isNotBlank() }
                .map { PostmanQuery(key = it.key, value = it.value, disabled = !it.isEnabled) },
        )
    }

    private fun requestBodyToPostmanBody(req: ApiRequestItem): PostmanBody? = when (req.bodyType) {
        RequestBodyType.NONE -> null
        RequestBodyType.JSON -> PostmanBody(mode = "raw", raw = req.bodyContent, rawLanguage = "json")
        RequestBodyType.RAW, RequestBodyType.BINARY -> PostmanBody(mode = "raw", raw = req.bodyContent, rawLanguage = "text")
        RequestBodyType.GRAPHQL -> PostmanBody(mode = "graphql", graphqlQuery = req.bodyContent, graphqlVariables = "")
        RequestBodyType.FORM -> PostmanBody(mode = "urlencoded", urlencoded = req.formData.map { it.toPostmanFormParam() })
        RequestBodyType.MULTIPART -> PostmanBody(mode = "formdata", formdata = req.formData.map { it.toPostmanFormParam() })
    }

    private fun KeyValueItem.toPostmanFormParam(): PostmanFormParam =
        PostmanFormParam(key = key, value = value, disabled = !isEnabled)

    private fun requestAuthToPostmanAuth(req: ApiRequestItem): PostmanAuth? = when (req.authType) {
        AuthType.NONE -> PostmanAuth(type = "noauth")
        AuthType.BEARER -> PostmanAuth(type = "bearer", attributes = listOf(PostmanAuthAttr("token", req.bearerToken)))
        AuthType.BASIC -> PostmanAuth(
            type = "basic",
            attributes = listOf(
                PostmanAuthAttr("username", req.basicUsername),
                PostmanAuthAttr("password", req.basicPassword),
            ),
        )
        AuthType.API_KEY -> PostmanAuth(
            type = "apikey",
            attributes = listOf(
                PostmanAuthAttr("key", req.apiKeyHeader),
                PostmanAuthAttr("value", req.apiKeyValue),
                PostmanAuthAttr("in", if (req.apiKeyLocation == ApiKeyLocation.QUERY_PARAM) "query" else "header"),
            ),
        )
        // OAUTH2/CUSTOM have no direct Postman v2.1 auth-block equivalent in the subset this
        // codec supports; omit the auth block rather than emit a misleading `type`. The request
        // itself (headers/body/url) still exports normally.
        AuthType.OAUTH2, AuthType.CUSTOM -> null
    }

    // ── PostmanCollection -> JSONObject ──────────────────────────────────

    private fun collectionToJson(collection: PostmanCollection): JSONObject = JSONObject().apply {
        put("info", JSONObject().apply {
            put("_postman_id", collection.info.postmanId)
            put("name", collection.info.name)
            put("schema", collection.info.schema)
        })
        put("item", JSONArray().apply { collection.item.forEach { put(itemToJson(it)) } })
    }

    private fun itemToJson(item: PostmanItem): JSONObject = JSONObject().apply {
        put("name", item.name)
        val nested = item.item
        if (nested != null) {
            put("item", JSONArray().apply { nested.forEach { put(itemToJson(it)) } })
        } else if (item.request != null) {
            put("request", requestToJson(item.request))
        }
    }

    private fun requestToJson(request: PostmanRequest): JSONObject = JSONObject().apply {
        put("method", request.method)
        put("header", JSONArray().apply {
            request.header.forEach { h ->
                put(JSONObject().apply { put("key", h.key); put("value", h.value); put("disabled", h.disabled) })
            }
        })
        put("url", urlToJson(request.url))
        request.body?.let { put("body", bodyToJson(it)) }
        request.auth?.let { put("auth", authToJson(it)) }
    }

    private fun urlToJson(url: PostmanUrl): JSONObject = JSONObject().apply {
        put("raw", url.raw)
        url.protocol?.let { put("protocol", it) }
        if (url.host.isNotEmpty()) put("host", JSONArray(url.host))
        if (url.path.isNotEmpty()) put("path", JSONArray(url.path))
        if (url.query.isNotEmpty()) {
            put("query", JSONArray().apply {
                url.query.forEach { q ->
                    put(JSONObject().apply { put("key", q.key); put("value", q.value); put("disabled", q.disabled) })
                }
            })
        }
    }

    private fun bodyToJson(body: PostmanBody): JSONObject = JSONObject().apply {
        put("mode", body.mode)
        when (body.mode) {
            "raw" -> {
                put("raw", body.raw)
                if (body.rawLanguage != null) {
                    put("options", JSONObject().put("raw", JSONObject().put("language", body.rawLanguage)))
                }
            }
            "urlencoded" -> put("urlencoded", formParamsToJson(body.urlencoded))
            "formdata" -> put("formdata", formParamsToJson(body.formdata))
            "graphql" -> put("graphql", JSONObject().apply {
                put("query", body.graphqlQuery)
                put("variables", body.graphqlVariables)
            })
        }
    }

    private fun formParamsToJson(params: List<PostmanFormParam>): JSONArray = JSONArray().apply {
        params.forEach { p ->
            put(JSONObject().apply {
                put("key", p.key); put("value", p.value); put("type", p.type); put("disabled", p.disabled)
            })
        }
    }

    private fun authToJson(auth: PostmanAuth): JSONObject = JSONObject().apply {
        put("type", auth.type)
        if (auth.attributes.isNotEmpty()) {
            put(auth.type, JSONArray().apply {
                auth.attributes.forEach { a ->
                    put(JSONObject().apply { put("key", a.key); put("value", a.value); put("type", a.type) })
                }
            })
        }
    }

    // ─────────────────────────────── Import ───────────────────────────────

    /**
     * Parses a real Postman v2.1 collection JSON document into requestr [ApiRequestItem]s.
     * Never throws for a per-item problem — an item with an unrecognized/missing shape is
     * skipped and recorded in [ImportResult.errors] rather than aborting the whole import. Only
     * top-level unparseable JSON (not even an object) produces an [ImportResult] with an empty
     * request list and one error entry, mirroring [WorkspaceExporter]'s "malformed top-level
     * JSON" contract being the one case allowed to fail loudly upstream if a caller chooses to.
     */
    fun import(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ImportResult("", emptyList(), emptyList(), listOf("Not a valid JSON object"))

        val collectionName = root.optJSONObject("info")?.optString("name", "Imported Collection")
            ?: "Imported Collection"

        val requests = mutableListOf<ApiRequestItem>()
        val folders = mutableListOf<ApiFolderItem>()
        val errors = mutableListOf<String>()

        fun walk(items: JSONArray, folderId: String?, path: String) {
            for (i in 0 until items.length()) {
                val label = "$path[$i]"
                try {
                    val obj = items.optJSONObject(i)
                    if (obj == null) {
                        errors.add("$label: not an object, skipped")
                        continue
                    }
                    val nestedItems = obj.optJSONArray("item")
                    val requestObj = obj.optJSONObject("request")
                    when {
                        nestedItems != null -> {
                            val folder = ApiFolderItem(name = obj.optString("name", "Folder"))
                            folders.add(folder)
                            walk(nestedItems, folder.id, "$label.item")
                        }
                        requestObj != null -> {
                            requests.add(postmanItemToRequest(obj, requestObj, folderId))
                        }
                        else -> errors.add("$label: neither a folder nor a request, skipped")
                    }
                } catch (e: Exception) {
                    errors.add("$label: ${e.message}")
                }
            }
        }

        walk(root.optJSONArray("item") ?: JSONArray(), null, "item")

        return ImportResult(collectionName, requests, folders, errors)
    }

    private fun postmanItemToRequest(item: JSONObject, request: JSONObject, folderId: String?): ApiRequestItem {
        val name = item.optString("name", "Imported Request")
        val method = runCatching { HttpMethod.valueOf(request.optString("method", "GET").uppercase()) }
            .getOrDefault(HttpMethod.GET)

        val (url, queryParams) = parseUrl(request.opt("url"))
        val headers = parseKeyValueArray(request.optJSONArray("header"))
        val (bodyType, bodyContent, formData) = parseBody(request.optJSONObject("body"))
        val auth = parseAuth(request.optJSONObject("auth"))

        return ApiRequestItem(
            name = name,
            folderId = folderId,
            method = method,
            url = url,
            queryParams = queryParams,
            headers = headers,
            authType = auth.authType,
            bearerToken = auth.bearerToken,
            basicUsername = auth.basicUsername,
            basicPassword = auth.basicPassword,
            apiKeyHeader = auth.apiKeyHeader,
            apiKeyValue = auth.apiKeyValue,
            apiKeyLocation = auth.apiKeyLocation,
            bodyType = bodyType,
            bodyContent = bodyContent,
            formData = formData,
        )
    }

    /** Postman's `url` is either a raw string OR the structured `{raw, protocol, host[], path[], query[]}` object. */
    private fun parseUrl(urlAny: Any?): Pair<String, List<KeyValueItem>> = when (urlAny) {
        is JSONObject -> {
            val query = parseKeyValueArray(urlAny.optJSONArray("query"))
            val raw = urlAny.optString("raw", "").ifBlank { reconstructUrl(urlAny) }
            raw.substringBefore('?') to query
        }
        is String -> {
            val base = urlAny.substringBefore('?')
            val queryStr = urlAny.substringAfter('?', "")
            base to parseQueryString(queryStr)
        }
        else -> "" to emptyList()
    }

    private fun reconstructUrl(urlObj: JSONObject): String {
        val protocol = urlObj.optString("protocol", "https").ifBlank { "https" }
        val hostArr = urlObj.optJSONArray("host")
        val host = if (hostArr != null) (0 until hostArr.length()).joinToString(".") { hostArr.optString(it, "") } else ""
        if (host.isBlank()) return ""
        val pathArr = urlObj.optJSONArray("path")
        val path = if (pathArr != null) (0 until pathArr.length()).joinToString("/") { pathArr.optString(it, "") } else ""
        return "$protocol://$host" + if (path.isNotBlank()) "/$path" else ""
    }

    /**
     * Postman renders header entries, `url.query` entries and `body.urlencoded`/`body.formdata`
     * parts with the identical `{key, value, disabled}` shape, so one reader covers all three.
     * A non-object element is skipped rather than failing the whole item (see [import]).
     */
    private fun parseKeyValueArray(arr: JSONArray?): List<KeyValueItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            KeyValueItem(
                key = o.optString("key", ""),
                value = o.optString("value", ""),
                isEnabled = !o.optBoolean("disabled", false),
            )
        }
    }

    private fun parseQueryString(queryStr: String): List<KeyValueItem> {
        if (queryStr.isBlank()) return emptyList()
        return queryStr.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val idx = pair.indexOf('=')
            if (idx >= 0) KeyValueItem(key = pair.substring(0, idx), value = pair.substring(idx + 1))
            else KeyValueItem(key = pair, value = "")
        }
    }

    private data class ParsedBody(val bodyType: RequestBodyType, val bodyContent: String, val formData: List<KeyValueItem>)

    private fun parseBody(body: JSONObject?): ParsedBody {
        if (body == null) return ParsedBody(RequestBodyType.NONE, "", emptyList())
        return when (body.optString("mode", "")) {
            "raw" -> {
                val raw = body.optString("raw", "")
                val language = body.optJSONObject("options")?.optJSONObject("raw")?.optString("language", "")
                val type = if (language == "json" || looksLikeJson(raw)) RequestBodyType.JSON else RequestBodyType.RAW
                ParsedBody(type, raw, emptyList())
            }
            "urlencoded" -> ParsedBody(RequestBodyType.FORM, "", parseKeyValueArray(body.optJSONArray("urlencoded")))
            "formdata" -> ParsedBody(RequestBodyType.MULTIPART, "", parseKeyValueArray(body.optJSONArray("formdata")))
            "graphql" -> ParsedBody(RequestBodyType.GRAPHQL, body.optJSONObject("graphql")?.optString("query", "") ?: "", emptyList())
            else -> ParsedBody(RequestBodyType.NONE, "", emptyList())
        }
    }

    private fun looksLikeJson(content: String): Boolean {
        val t = content.trim()
        return (t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))
    }

    private data class ParsedAuth(
        val authType: AuthType,
        val bearerToken: String = "",
        val basicUsername: String = "",
        val basicPassword: String = "",
        val apiKeyHeader: String = "X-API-Key",
        val apiKeyValue: String = "",
        val apiKeyLocation: ApiKeyLocation = ApiKeyLocation.HEADER,
    )

    /** Postman expresses bearer/basic/apikey auth as `{type, <type>: [{key,value}, ...]}`. */
    private fun parseAuth(auth: JSONObject?): ParsedAuth {
        if (auth == null) return ParsedAuth(AuthType.NONE)
        return when (auth.optString("type", "noauth")) {
            "bearer" -> ParsedAuth(AuthType.BEARER, bearerToken = authAttr(auth.optJSONArray("bearer"), "token"))
            "basic" -> ParsedAuth(
                AuthType.BASIC,
                basicUsername = authAttr(auth.optJSONArray("basic"), "username"),
                basicPassword = authAttr(auth.optJSONArray("basic"), "password"),
            )
            "apikey" -> {
                val arr = auth.optJSONArray("apikey")
                val headerName = authAttr(arr, "key").ifBlank { "X-API-Key" }
                ParsedAuth(
                    AuthType.API_KEY,
                    apiKeyHeader = headerName,
                    apiKeyValue = authAttr(arr, "value"),
                    apiKeyLocation = if (authAttr(arr, "in").equals("query", ignoreCase = true)) ApiKeyLocation.QUERY_PARAM else ApiKeyLocation.HEADER,
                )
            }
            // Unknown/unsupported auth type (oauth2, digest, noauth, ...): never throw, just
            // record as NONE so the request still imports with everything else intact.
            else -> ParsedAuth(AuthType.NONE)
        }
    }

    private fun authAttr(arr: JSONArray?, key: String): String {
        if (arr == null) return ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("key") == key) return o.optString("value", "")
        }
        return ""
    }
}
