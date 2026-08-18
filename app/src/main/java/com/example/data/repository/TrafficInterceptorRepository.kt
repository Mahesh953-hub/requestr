package com.example.data.repository

import com.example.data.models.AppPackageItem
import com.example.data.models.CapturedTrafficItem
import com.example.data.models.CertificateDetails
import com.example.data.models.HttpMethod
import com.example.data.models.ProxyTierType
import com.example.data.models.RuleAction
import com.example.data.models.TrafficRuleItem
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object TrafficInterceptorRepository {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var streamJob: Job? = null

    private val _isInterceptionActive = MutableStateFlow(true)
    val isInterceptionActive: StateFlow<Boolean> = _isInterceptionActive.asStateFlow()
    val isCapturing: StateFlow<Boolean> = _isInterceptionActive.asStateFlow()

    // Local VPN and MITM Proxy States
    private val _isVpnActive = MutableStateFlow(true)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _vpnPort = MutableStateFlow(8080)
    val vpnPort: StateFlow<Int> = _vpnPort.asStateFlow()

    private val _vpnPacketsCount = MutableStateFlow(1420)
    val vpnPacketsCount: StateFlow<Int> = _vpnPacketsCount.asStateFlow()

    private val _vpnBytesTransferred = MutableStateFlow(489200L)
    val vpnBytesTransferred: StateFlow<Long> = _vpnBytesTransferred.asStateFlow()

    private val _activeConnections = MutableStateFlow(6)
    val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

    // Shizuku & Root Bypass States
    private val _isShizukuPermissionGranted = MutableStateFlow(true)
    val isShizukuPermissionGranted: StateFlow<Boolean> = _isShizukuPermissionGranted.asStateFlow()

    private val _isRootAvailable = MutableStateFlow(true)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _isSslPinningBypassed = MutableStateFlow(true)
    val isSslPinningBypassed: StateFlow<Boolean> = _isSslPinningBypassed.asStateFlow()

    fun toggleVpn(): Boolean {
        _isVpnActive.value = !_isVpnActive.value
        return _isVpnActive.value
    }

    fun toggleShizuku(): Boolean {
        _isShizukuPermissionGranted.value = !_isShizukuPermissionGranted.value
        return _isShizukuPermissionGranted.value
    }

    fun toggleShizukuPermission(): Boolean {
        return toggleShizuku()
    }

    fun toggleRootMode(): Boolean {
        _isRootAvailable.value = !_isRootAvailable.value
        return _isRootAvailable.value
    }

    fun toggleSslPinningBypass(): Boolean {
        _isSslPinningBypassed.value = !_isSslPinningBypassed.value
        return _isSslPinningBypassed.value
    }

    fun toggleCapture(): Boolean {
        val newState = !_isInterceptionActive.value
        _isInterceptionActive.value = newState
        if (newState) {
            startLiveStreamSimulation()
        } else {
            stopLiveStreamSimulation()
        }
        return newState
    }

    private val _capturedTraffic = MutableStateFlow<List<CapturedTrafficItem>>(emptyList())
    val capturedTraffic: StateFlow<List<CapturedTrafficItem>> = _capturedTraffic.asStateFlow()

    private val _trafficRules = MutableStateFlow<List<TrafficRuleItem>>(
        listOf(
            TrafficRuleItem(
                id = "rule-1",
                name = "Mock Login Token",
                isEnabled = true,
                urlPattern = ".*/auth/login.*",
                method = HttpMethod.POST,
                action = RuleAction.MOCK_RESPONSE,
                mockStatusCode = 200,
                mockResponseBody = "{\"status\": \"success\", \"user_id\": 1337, \"token\": \"jwt_requestr_cyber_sec_token_xyz\"}"
            ),
            TrafficRuleItem(
                id = "rule-2",
                name = "Block Ad Trackers",
                isEnabled = true,
                urlPattern = ".*telemetry|analytics|adservice.*",
                method = null,
                action = RuleAction.BLOCK
            ),
            TrafficRuleItem(
                id = "rule-3",
                name = "Inspect API Headers",
                isEnabled = false,
                urlPattern = ".*api\\.stripe\\.com.*",
                method = HttpMethod.POST,
                action = RuleAction.BREAKPOINT
            )
        )
    )
    val trafficRules: StateFlow<List<TrafficRuleItem>> = _trafficRules.asStateFlow()

    private val _certificateDetails = MutableStateFlow(
        CertificateDetails(
            commonName = "requestr CA Root",
            organization = "requestr Security Lab",
            serialNumber = "7A:B4:89:E2:01:C9:4F:9D",
            validFrom = "2026-01-01",
            validTo = "2036-01-01",
            sha256Fingerprint = "3F:A9:41:88:C2:5E:7B:19:D4:62:00:81:AA:9C:3E:45:B1:09:88:2F:E1:92:43:88:99:A1:00:CC:23:41:7A:B9",
            isInstalledUser = true,
            isInstalledSystem = true,
            isMagiskModuleGenerated = true
        )
    )
    val certificateDetails: StateFlow<CertificateDetails> = _certificateDetails.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppPackageItem>>(
        listOf(
            AppPackageItem("com.github.android", "GitHub", true, false),
            AppPackageItem("com.twitter.android", "X / Twitter", true, false),
            AppPackageItem("com.spotify.music", "Spotify", true, false),
            AppPackageItem("org.telegram.messenger", "Telegram", true, false),
            AppPackageItem("com.discord", "Discord", true, false),
            AppPackageItem("com.android.chrome", "Chrome Browser", true, true),
            AppPackageItem("org.mozilla.firefox", "Firefox Browser", true, false),
            AppPackageItem("com.google.android.youtube", "YouTube", false, true),
            AppPackageItem("com.google.android.gms", "Google Play Services", false, true)
        )
    )
    val installedApps: StateFlow<List<AppPackageItem>> = _installedApps.asStateFlow()

    // Sample initial capture feed
    init {
        _capturedTraffic.value = listOf(
            CapturedTrafficItem(
                id = UUID.randomUUID().toString(),
                method = HttpMethod.POST,
                url = "https://api.github.com/graphql",
                host = "api.github.com",
                path = "/graphql",
                statusCode = 200,
                statusMessage = "OK",
                protocol = "h2",
                latencyMs = 148,
                requestSize = 480,
                responseSize = 2840,
                requestHeaders = mapOf(
                    "Host" to "api.github.com",
                    "Authorization" to "Bearer gho_***masked***",
                    "Content-Type" to "application/json"
                ),
                responseHeaders = mapOf(
                    "content-type" to "application/json; charset=utf-8",
                    "x-ratelimit-limit" to "5000",
                    "x-ratelimit-remaining" to "4982"
                ),
                requestBody = "{\"query\":\"query { viewer { login name repositories(first: 5) { nodes { name } } } }\"}",
                responseBody = "{\n  \"data\": {\n    \"viewer\": {\n      \"login\": \"hacker-one\",\n      \"name\": \"Security Researcher\",\n      \"repositories\": {\n        \"nodes\": [\n          { \"name\": \"requestr-engine\" },\n          { \"name\": \"frida-scripts\" }\n        ]\n      }\n    }\n  }\n}",
                appPackage = "com.github.android",
                appName = "GitHub"
            ),
            CapturedTrafficItem(
                id = UUID.randomUUID().toString(),
                method = HttpMethod.GET,
                url = "https://api.spotify.com/v1/me/player/currently-playing",
                host = "api.spotify.com",
                path = "/v1/me/player/currently-playing",
                statusCode = 200,
                statusMessage = "OK",
                protocol = "h2",
                latencyMs = 86,
                requestSize = 312,
                responseSize = 1420,
                requestHeaders = mapOf("Authorization" to "Bearer BQC..."),
                responseHeaders = mapOf("content-type" to "application/json"),
                requestBody = "",
                responseBody = "{\n  \"is_playing\": true,\n  \"item\": {\n    \"name\": \"Midnight Cyber Beats\",\n    \"artists\": [{\"name\": \"requestr Synth\"}]\n  }\n}",
                appPackage = "com.spotify.music",
                appName = "Spotify"
            ),
            CapturedTrafficItem(
                id = UUID.randomUUID().toString(),
                method = HttpMethod.POST,
                url = "https://discord.com/api/v9/channels/987654321/messages",
                host = "discord.com",
                path = "/api/v9/channels/987654321/messages",
                statusCode = 200,
                statusMessage = "OK",
                protocol = "h2",
                latencyMs = 94,
                requestSize = 512,
                responseSize = 820,
                requestHeaders = mapOf("Content-Type" to "application/json"),
                responseHeaders = mapOf("content-type" to "application/json"),
                requestBody = "{\"content\":\"Test payload via requestr proxy\"}",
                responseBody = "{\"id\":\"1234567890\",\"content\":\"Test payload via requestr proxy\"}",
                appPackage = "com.discord",
                appName = "Discord"
            ),
            CapturedTrafficItem(
                id = UUID.randomUUID().toString(),
                method = HttpMethod.GET,
                url = "https://telemetry.tracker.io/v1/event?id=384920",
                host = "telemetry.tracker.io",
                path = "/v1/event",
                statusCode = 403,
                statusMessage = "Blocked by requestr Rule",
                protocol = "h2",
                latencyMs = 2,
                requestSize = 120,
                responseSize = 0,
                requestHeaders = mapOf("User-Agent" to "AndroidApp/1.0"),
                responseHeaders = mapOf("X-Blocked-By" to "requestr-RuleEngine"),
                requestBody = "",
                responseBody = "/* Request blocked by Rule #2: Block Ad Trackers */",
                appPackage = "com.twitter.android",
                appName = "X / Twitter",
                isBlocked = true
            )
        )
    }

    fun setInterceptionActive(active: Boolean) {
        _isInterceptionActive.value = active
    }

    fun toggleAppInterception(packageName: String) {
        _installedApps.value = _installedApps.value.map {
            if (it.packageName == packageName) it.copy(isInterceptEnabled = !it.isInterceptEnabled) else it
        }
    }

    fun setAllAppsEnabled(enabled: Boolean, userAppsOnly: Boolean = false) {
        _installedApps.value = _installedApps.value.map {
            if (userAppsOnly && it.isSystemApp) it else it.copy(isInterceptEnabled = enabled)
        }
    }

    fun addRule(rule: TrafficRuleItem) {
        _trafficRules.value = listOf(rule) + _trafficRules.value
    }

    fun toggleRule(ruleId: String) {
        _trafficRules.value = _trafficRules.value.map {
            if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it
        }
    }

    fun deleteRule(ruleId: String) {
        _trafficRules.value = _trafficRules.value.filter { it.id != ruleId }
    }

    fun clearTraffic() {
        _capturedTraffic.value = emptyList()
    }

    fun addCapturedItem(item: CapturedTrafficItem) {
        _capturedTraffic.value = listOf(item) + _capturedTraffic.value
    }

    fun regenerateCaCertificate() {
        try {
            val keyGen = KeyPairGenerator.getInstance("RSA")
            keyGen.initialize(2048)
            val pair = keyGen.generateKeyPair()
            val md = MessageDigest.getInstance("SHA-256")
            val fpBytes = md.digest(pair.public.encoded)
            val fp = fpBytes.joinToString(":") { "%02X".format(it) }

            _certificateDetails.value = CertificateDetails(
                commonName = "requestr CA Root (v2)",
                organization = "requestr Security Lab",
                serialNumber = UUID.randomUUID().toString().take(18).uppercase(),
                validFrom = "2026-08-18",
                validTo = "2036-08-18",
                sha256Fingerprint = fp,
                isInstalledUser = true,
                isInstalledSystem = true,
                isMagiskModuleGenerated = true
            )
        } catch (e: Exception) {
            // fallback
        }
    }

    // Frida scripts library
    val fridaScripts = listOf(
        FridaScript(
            id = "ssl-universal",
            title = "Universal SSL Pinning Bypass",
            framework = "TrustManager / OpenSSL / Conscrypt / BoringSSL",
            description = "Hooks Java TrustManagerImpl, SSLContext, and native OpenSSL verify callbacks to force all certificate validations to return true.",
            scriptContent = """
Java.perform(function() {
    console.log("[*] requestr Universal SSL Pinning Bypass injected");
    var TrustManagerImpl = Java.use('com.android.org.conscrypt.TrustManagerImpl');
    TrustManagerImpl.verifyChain.implementation = function(untrustedChain, trustAnchorChain, host, clientAuth, ocspData, tlsSctData) {
        console.log("[+] Bypassed TrustManagerImpl.verifyChain for " + host);
        return untrustedChain;
    };
});
            """.trimIndent()
        ),
        FridaScript(
            id = "okhttp-pinning",
            title = "OkHttp CertificatePinner Bypass",
            framework = "OkHttp 3.x / 4.x",
            description = "Nullifies CertificatePinner.check() methods across all instances and variants in the target process.",
            scriptContent = """
Java.perform(function() {
    console.log("[*] Hooking OkHttp CertificatePinner...");
    try {
        var CertificatePinner = Java.use('okhttp3.CertificatePinner');
        CertificatePinner.check.overload('java.lang.String', 'java.util.List').implementation = function(str, list) {
            console.log('[+] OkHttp CertificatePinner.check(str, list) bypassed for: ' + str);
            return;
        };
    } catch(e) { console.log(e); }
});
            """.trimIndent()
        ),
        FridaScript(
            id = "flutter-boringssl",
            title = "Flutter BoringSSL Pinning Bypass",
            framework = "Flutter / Dart VM",
            description = "Disables native session verification inside libflutter.so by patching the ssl_crypto_x509_session_verify_cert offset.",
            scriptContent = """
function bypassFlutter() {
    var m = Process.findModuleByName("libflutter.so");
    if (m) {
        console.log("[+] Found libflutter.so at " + m.base);
        // Scans pattern and forces verify_callback to return 1 (success)
    }
}
bypassFlutter();
            """.trimIndent()
        )
    )

    fun startLiveStreamSimulation() {
        if (streamJob?.isActive == true) return
        streamJob = scope.launch {
            val sampleUrls = listOf(
                Triple(HttpMethod.GET, "https://api.github.com/user/repos?per_page=10", "com.github.android" to "GitHub"),
                Triple(HttpMethod.POST, "https://api.telegram.org/bot591823/getUpdates", "org.telegram.messenger" to "Telegram"),
                Triple(HttpMethod.GET, "https://api.spotify.com/v1/me/playlists", "com.spotify.music" to "Spotify"),
                Triple(HttpMethod.POST, "https://discord.com/api/v9/interactions", "com.discord" to "Discord"),
                Triple(HttpMethod.GET, "https://cloudflare-dns.com/dns-query?name=google.com&type=A", "com.android.chrome" to "Chrome Browser"),
                Triple(HttpMethod.PUT, "https://api.stripe.com/v1/payment_intents/pi_92384/confirm", "com.kailashhh.requestr" to "API Testr"),
                Triple(HttpMethod.DELETE, "https://api.twitter.com/2/tweets/18294829", "com.twitter.android" to "X / Twitter")
            )

            while (isActive && _isInterceptionActive.value) {
                delay(3500)
                if (!_isInterceptionActive.value) break

                val sample = sampleUrls.random()
                val latency = Random.nextInt(45, 320)
                val status = if (Random.nextFloat() > 0.85f) 401 else 200
                val reqSize = Random.nextInt(180, 1200)
                val resSize = Random.nextInt(600, 4800)

                val newItem = CapturedTrafficItem(
                    id = UUID.randomUUID().toString(),
                    method = sample.first,
                    url = sample.second,
                    host = java.net.URI(sample.second).host ?: "api.target.com",
                    path = java.net.URI(sample.second).path ?: "/",
                    statusCode = status,
                    statusMessage = if (status == 200) "OK" else "Unauthorized",
                    protocol = if (Random.nextBoolean()) "h2" else "HTTP/1.1",
                    latencyMs = latency.toLong(),
                    requestSize = reqSize.toLong(),
                    responseSize = resSize.toLong(),
                    requestHeaders = mapOf(
                        "Host" to (java.net.URI(sample.second).host ?: ""),
                        "User-Agent" to "requestr/2.1 (Linux; Android 15; arm64-v8a)",
                        "Accept" to "application/json",
                        "X-Request-ID" to UUID.randomUUID().toString().take(8)
                    ),
                    responseHeaders = mapOf(
                        "content-type" to "application/json; charset=utf-8",
                        "server" to "cloudflare",
                        "x-response-time" to "${latency}ms"
                    ),
                    requestBody = if (sample.first == HttpMethod.POST || sample.first == HttpMethod.PUT) "{\"action\":\"sync\",\"timestamp\":${System.currentTimeMillis()}}" else "",
                    responseBody = "{\n  \"status\": $status,\n  \"message\": \"Captured live via requestr MITM engine\",\n  \"data\": {\n    \"endpoint\": \"${sample.second}\",\n    \"bytes_transferred\": $resSize\n  }\n}",
                    appPackage = sample.third.first,
                    appName = sample.third.second,
                    timestamp = System.currentTimeMillis()
                )

                _capturedTraffic.value = listOf(newItem) + _capturedTraffic.value
                _vpnPacketsCount.value += Random.nextInt(1, 4)
                _vpnBytesTransferred.value += (reqSize + resSize)
            }
        }
    }

    fun stopLiveStreamSimulation() {
        streamJob?.cancel()
        streamJob = null
    }

    init {
        startLiveStreamSimulation()
    }

    fun exportAsHar(): String {
        val entries = _capturedTraffic.value.joinToString(",\n") { item ->
            """
            {
              "startedDateTime": "${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date(item.timestamp))}",
              "time": ${item.latencyMs},
              "request": {
                "method": "${item.method.name}",
                "url": "${item.url}",
                "httpVersion": "${item.protocol}",
                "headers": [
                  ${item.requestHeaders.entries.joinToString(",") { "{\"name\":\"${it.key}\",\"value\":\"${it.value}\"}" }}
                ],
                "bodySize": ${item.requestSize}
              },
              "response": {
                "status": ${item.statusCode},
                "statusText": "${item.statusMessage}",
                "httpVersion": "${item.protocol}",
                "headers": [
                  ${item.responseHeaders.entries.joinToString(",") { "{\"name\":\"${it.key}\",\"value\":\"${it.value}\"}" }}
                ],
                "content": {
                  "size": ${item.responseSize},
                  "mimeType": "application/json",
                  "text": "${item.responseBody.replace("\"", "\\\"").replace("\n", "\\n")}"
                },
                "bodySize": ${item.responseSize}
              }
            }
            """.trimIndent()
        }

        return """
        {
          "log": {
            "version": "1.2",
            "creator": { "name": "requestr HTTP(S) Interceptor", "version": "2.0" },
            "entries": [
              $entries
            ]
          }
        }
        """.trimIndent()
    }
}

data class FridaScript(
    val id: String,
    val title: String,
    val framework: String,
    val description: String,
    val scriptContent: String
)
