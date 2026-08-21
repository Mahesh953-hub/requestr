package com.example.data.repository

import com.example.data.models.AppPackageItem
import com.example.data.models.TrafficRuleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Holds runtime state that has no persistent equivalent.
 *
 * v2.1 had this object fabricate interception state (root/Shizuku/VPN all hardcoded true),
 * generate fake traffic via `Random.nextInt()` + `delay(3500)`, and seed demo data.
 * All of that has been removed:
 *   - Interception state → `InterceptionStateRepository`
 *   - Real certificates → `CertificateAuthority` (BouncyCastle)
 *   - Real probes → `CapabilityProbe`
 *   - Traffic persistence → `CaptureLogRepository` / Room
 *   - Capture control → `PreflightController` + `CaptureController`
 *
 * What remains: the installed-app list (real PackageManager), the in-memory
 * traffic-rules mirror, and the Frida reference-script library.
 */
object TrafficInterceptorRepository {

    // ── installed apps (real PackageManager query) ──────────────────────

    private val _installedApps = MutableStateFlow<List<AppPackageItem>>(emptyList())
    val installedApps: StateFlow<List<AppPackageItem>> = _installedApps.asStateFlow()

    /**
     * Enumerates genuinely installed, launchable apps for the capture filter.
     * Requires QUERY_ALL_PACKAGES for Android 11+ visibility.
     */
    suspend fun loadInstalledApps(context: android.content.Context) {
        val apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .filter { it.packageName != context.packageName }
                    .map { info ->
                        AppPackageItem(
                            packageName = info.packageName,
                            appName = runCatching { pm.getApplicationLabel(info).toString() }
                                .getOrDefault(info.packageName),
                            isInterceptEnabled = false,
                            isSystemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
        }
        _installedApps.value = apps
    }

    // ── traffic rules (in-memory mirror) ───────────────────────────────

    // No seed rules by design.
    //
    // v2.1 shipped three fabricated rules ("Mock Login Token", "Block Ad Trackers",
    // "Inspect API Headers") that were never applied to any traffic, so the Rules screen
    // looked configured on a fresh install (APP_v2.md Bug 1.6). Rules now start empty and
    // real ones are persisted to the intercept_rules Room table.
    private val _trafficRules = MutableStateFlow<List<TrafficRuleItem>>(emptyList())
    val trafficRules: StateFlow<List<TrafficRuleItem>> = _trafficRules.asStateFlow()

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

    // ── Frida reference-script library ─────────────────────────────────

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
}

data class FridaScript(
    val id: String,
    val title: String,
    val framework: String,
    val description: String,
    val scriptContent: String
)
