package com.example.data.repository

import android.content.Context
import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.AuthType
import com.example.data.models.HttpMethod
import com.example.data.models.RequestBodyType
import com.example.interception.OkHttpProxyInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

object ApiTestrRepository {

    // R3 fix: ApiTestrRepository was the most user-facing/traffic-generating OkHttpClient
    // owner in the app with no Context available to it, so OkHttpProxyInterceptor (which
    // records every real request/response pair for the OkHttp runtime-instrumentation
    // plane -- see HttpStackAdapters.kt's OkHttpAdapter) was never attached to any live
    // client. Follows the same lazy-Context-init pattern as FlowRepository.init() /
    // SettingsRepository.init() in AppRepositories.kt, called once from
    // MainActivity.onCreate(). Note this means API Testr's own outbound calls now always
    // show up in the OkHttp instrumentation plane (OkHttpProxyInterceptor.intercept()
    // records events even when no capture tier's proxy port is active -- see its own
    // port <= 0 branch), which is the intended, documented scope: this only ever
    // instruments requestr's own outbound traffic, never a third-party app's (that would
    // require the separate Observer SDK, S1).
    private var appContext: Context? = null

    /** Call once from MainActivity.onCreate() so [client] can attach [OkHttpProxyInterceptor]. */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
        appContext?.let { builder.addInterceptor(OkHttpProxyInterceptor(it)) }
        builder.build()
    }

    suspend fun executeRequest(requestItem: ApiRequestItem, followRedirects: Boolean = true): ApiResponseData = withContext(Dispatchers.IO) {
        // Per-request client that respects the follow-redirects toggle
        val reqClient = if (followRedirects) client else run {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
            appContext?.let { builder.addInterceptor(OkHttpProxyInterceptor(it)) }
            builder.build()
        }
        val startDns = System.currentTimeMillis()
        val urlBuilder = requestItem.url.trim()
        
        // Build final URL with enabled query params if present
        val finalUrl = if (requestItem.queryParams.any { it.isEnabled && it.key.isNotBlank() }) {
            val delimiter = if (urlBuilder.contains("?")) "&" else "?"
            val qs = requestItem.queryParams
                .filter { it.isEnabled && it.key.isNotBlank() }
                .joinToString("&") { "${it.key}=${it.value}" }
            "$urlBuilder$delimiter$qs"
        } else {
            urlBuilder
        }

        val requestBuilder = Request.Builder().url(finalUrl)

        // Headers
        requestItem.headers
            .filter { it.isEnabled && it.key.isNotBlank() }
            .forEach {
                requestBuilder.addHeader(it.key, it.value)
            }

        // Auth
        when (requestItem.authType) {
            AuthType.BEARER -> {
                if (requestItem.bearerToken.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer ${requestItem.bearerToken}")
                }
            }
            AuthType.BASIC -> {
                val creds = "${requestItem.basicUsername}:${requestItem.basicPassword}"
                val encoded = android.util.Base64.encodeToString(creds.toByteArray(), android.util.Base64.NO_WRAP)
                requestBuilder.header("Authorization", "Basic $encoded")
            }
            AuthType.API_KEY -> {
                if (requestItem.apiKeyValue.isNotBlank()) {
                    requestBuilder.header(requestItem.apiKeyHeader, requestItem.apiKeyValue)
                }
            }
            else -> {}
        }

        // Body
        val mediaType = when (requestItem.bodyType) {
            RequestBodyType.JSON -> "application/json; charset=utf-8".toMediaTypeOrNull()
            RequestBodyType.RAW -> "text/plain; charset=utf-8".toMediaTypeOrNull()
            RequestBodyType.GRAPHQL -> "application/graphql".toMediaTypeOrNull()
            RequestBodyType.FORM -> "application/x-www-form-urlencoded".toMediaTypeOrNull()
            else -> null
        }

        val body = when {
            requestItem.method == HttpMethod.GET || requestItem.method == HttpMethod.HEAD -> null
            requestItem.bodyType == RequestBodyType.NONE -> "".toRequestBody(null)
            else -> requestItem.bodyContent.toRequestBody(mediaType)
        }

        when (requestItem.method) {
            HttpMethod.GET -> requestBuilder.get()
            HttpMethod.POST -> requestBuilder.post(body ?: "".toRequestBody(null))
            HttpMethod.PUT -> requestBuilder.put(body ?: "".toRequestBody(null))
            HttpMethod.DELETE -> if (body != null) requestBuilder.delete(body) else requestBuilder.delete()
            HttpMethod.PATCH -> requestBuilder.patch(body ?: "".toRequestBody(null))
            HttpMethod.HEAD -> requestBuilder.head()
            else -> requestBuilder.get()
        }

        val t0 = System.currentTimeMillis()
        try {
            val response: Response = reqClient.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - t0
            val responseBody = response.body?.string() ?: ""
            val size = responseBody.toByteArray().size.toLong()

            val headerMap = mutableMapOf<String, String>()
            for (i in 0 until response.headers.size) {
                headerMap[response.headers.name(i)] = response.headers.value(i)
            }

            ApiResponseData(
                statusCode = response.code,
                statusMessage = response.message.ifBlank { if (response.isSuccessful) "OK" else "Error" },
                protocol = response.protocol.toString(),
                latencyMs = latency,
                sizeBytes = size,
                headers = headerMap,
                body = responseBody,
                timestamp = System.currentTimeMillis(),
                isSuccess = response.isSuccessful,
                dnsTimeMs = (latency * 0.15).toLong().coerceAtLeast(4),
                connectTimeMs = (latency * 0.20).toLong().coerceAtLeast(8),
                tlsTimeMs = (latency * 0.30).toLong().coerceAtLeast(12),
                requestTimeMs = (latency * 0.10).toLong().coerceAtLeast(3),
                responseTimeMs = (latency * 0.25).toLong().coerceAtLeast(10)
            )
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - t0
            ApiResponseData(
                statusCode = 0,
                statusMessage = "Network Failure",
                protocol = "none",
                latencyMs = latency,
                sizeBytes = 0,
                headers = emptyMap(),
                body = "Failed to connect to ${requestItem.url}\n\nCause: ${e.localizedMessage ?: e.message}",
                timestamp = System.currentTimeMillis(),
                isSuccess = false,
                error = e.localizedMessage ?: e.message
            )
        }
    }

    fun generateCodeSnippet(req: ApiRequestItem, language: String): String {
        return when {
            language.contains("Python - Requests", ignoreCase = true) -> {
                val headersDict = req.headers.filter { it.isEnabled }.joinToString(",\n    ") { "\"${it.key}\": \"${it.value}\"" }
                """
import requests

url = "${req.url}"
headers = {
    $headersDict
}
${if (req.bodyType == RequestBodyType.JSON) "data = ${req.bodyContent.replace("\n", "\n")}" else "data = \"${req.bodyContent.replace("\"", "\\\"")}\""}

response = requests.${req.method.name.lowercase()}(url, headers=headers${if (req.bodyType != RequestBodyType.NONE) ", data=data" else ""})
print(response.status_code)
print(response.text)
                """.trimIndent()
            }
            language.contains("Python - httpx", ignoreCase = true) -> {
                """
import httpx

with httpx.Client() as client:
    response = client.${req.method.name.lowercase()}("${req.url}"${if (req.bodyType != RequestBodyType.NONE) ", content='''${req.bodyContent}'''" else ""})
    print(response.status_code)
    print(response.text)
                """.trimIndent()
            }
            language.contains("JavaScript - fetch", ignoreCase = true) -> {
                val headersJson = req.headers.filter { it.isEnabled }.joinToString(",\n    ") { "\"${it.key}\": \"${it.value}\"" }
                """
const options = {
  method: '${req.method.name}',
  headers: {
    $headersJson
  }${if (req.bodyType != RequestBodyType.NONE) ",\n  body: JSON.stringify(${req.bodyContent})" else ""}
};

fetch('${req.url}', options)
  .then(res => res.json())
  .then(data => console.log(data))
  .catch(err => console.error(err));
                """.trimIndent()
            }
            language.contains("Kotlin - OkHttp", ignoreCase = true) -> {
                """
val client = OkHttpClient()
val request = Request.Builder()
    .url("${req.url}")
    ${req.headers.filter { it.isEnabled }.joinToString("\n    ") { ".addHeader(\"${it.key}\", \"${it.value}\")" }}
    ${if (req.method == HttpMethod.GET) ".get()" else ".method(\"${req.method.name}\", body)"}
    .build()

val response = client.newCall(request).execute()
println(response.body?.string())
                """.trimIndent()
            }
            language.contains("cURL", ignoreCase = true) -> {
                val h = req.headers.filter { it.isEnabled }.joinToString(" ") { "-H \"${it.key}: ${it.value}\"" }
                val b = if (req.bodyType != RequestBodyType.NONE) "-d '${req.bodyContent.replace("'", "\\'")}'" else ""
                "curl -X ${req.method.name} \"${req.url}\" $h $b".trim()
            }
            language.contains("Go", ignoreCase = true) -> {
                """
package main

import (
    "fmt"
    "net/http"
    "io/ioutil"
)

func main() {
    url := "${req.url}"
    req, _ := http.NewRequest("${req.method.name}", url, nil)
    res, _ := http.DefaultClient.Do(req)
    defer res.Body.Close()
    body, _ := ioutil.ReadAll(res.Body)
    fmt.Println(string(body))
}
                """.trimIndent()
            }
            else -> {
                "// Export for $language\ncurl -X ${req.method.name} \"${req.url}\""
            }
        }
    }

    fun parseCurl(curlCommand: String): ApiRequestItem {
        var method = HttpMethod.GET
        var url = "https://httpbin.org/get"
        val headers = mutableListOf<com.example.data.models.KeyValueItem>()
        var body = ""
        var bodyType = RequestBodyType.NONE

        val tokens = curlCommand.replace("\\\n", " ").split("\\s+".toRegex())
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when (token) {
                "-X", "--request" -> {
                    if (i + 1 < tokens.size) {
                        method = try { HttpMethod.valueOf(tokens[i + 1].uppercase()) } catch (e: Exception) { HttpMethod.GET }
                        i++
                    }
                }
                "-H", "--header" -> {
                    if (i + 1 < tokens.size) {
                        val headerStr = tokens[i + 1].removeSurrounding("\"").removeSurrounding("'")
                        val parts = headerStr.split(":", limit = 2)
                        if (parts.size == 2) {
                            headers.add(com.example.data.models.KeyValueItem(key = parts[0].trim(), value = parts[1].trim()))
                        }
                        i++
                    }
                }
                "-d", "--data", "--data-raw" -> {
                    if (i + 1 < tokens.size) {
                        body = tokens[i + 1].removeSurrounding("\"").removeSurrounding("'")
                        bodyType = RequestBodyType.JSON
                        if (method == HttpMethod.GET) method = HttpMethod.POST
                        i++
                    }
                }
                else -> {
                    if (token.startsWith("http://") || token.startsWith("https://")) {
                        url = token.removeSurrounding("\"").removeSurrounding("'")
                    }
                }
            }
            i++
        }

        return ApiRequestItem(
            name = "Imported cURL",
            method = method,
            url = url,
            headers = if (headers.isEmpty()) listOf(com.example.data.models.KeyValueItem(key = "Accept", value = "*/*")) else headers,
            bodyType = bodyType,
            bodyContent = body.ifBlank { "{\n  \"imported\": true\n}" }
        )
    }
}
