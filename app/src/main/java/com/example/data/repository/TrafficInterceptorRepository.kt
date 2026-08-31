package com.example.data.repository

import com.example.data.models.AppPackageItem
import com.example.data.models.TrafficRuleItem
import com.example.data.models.resolveAppDisplayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * True if a PackageManager label lookup "failed" for our purposes: it's blank, or it's
 * literally the package name (i.e. resolution silently fell back to the identifier instead of
 * producing a real label). Superseded as the picker's actual fallback logic by
 * [com.example.data.models.resolveAppDisplayLabel] (which encodes the same "unusable" check
 * internally for each rung of its chain), but kept as a small standalone predicate — a caller
 * elsewhere in this module can use it to decide whether a *single* already-resolved label is
 * worth showing without re-deriving the whole three-rung fallback chain.
 */
fun isUnusableLabel(label: String, packageName: String): Boolean =
    label.isBlank() || label == packageName

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

    // Master plan §3 LOW-ADD (C8) — the INTERNET-permission prefilter is now a real, observable
    // setting rather than a silent baked-in `true`. It defaults on (fewer irrelevant apps in the
    // picker), a user can flip it from Settings' "VPN app picker" section, and every load reports
    // exactly how many apps that setting hid — see [totalInstalledAppCount]/[filteredOutAppCount].
    private val _internetOnlyFilterEnabled = MutableStateFlow(true)
    val internetOnlyFilterEnabled: StateFlow<Boolean> = _internetOnlyFilterEnabled.asStateFlow()

    /** Apps considered launchable + not-self, *before* the INTERNET filter is applied. */
    private val _totalInstalledAppCount = MutableStateFlow(0)
    val totalInstalledAppCount: StateFlow<Int> = _totalInstalledAppCount.asStateFlow()

    /** How many of [totalInstalledAppCount] the current [internetOnlyFilterEnabled] hid. */
    private val _filteredOutAppCount = MutableStateFlow(0)
    val filteredOutAppCount: StateFlow<Int> = _filteredOutAppCount.asStateFlow()

    /** Persists the toggle for the next [loadInstalledApps] call that doesn't explicitly override it. */
    fun setInternetOnlyFilter(enabled: Boolean) {
        _internetOnlyFilterEnabled.value = enabled
    }

    /**
     * Enumerates genuinely installed, launchable apps for the capture filter.
     * Requires QUERY_ALL_PACKAGES for Android 11+ visibility.
     *
     * Master plan §3 LOW-ADD — INTERNET app-picker filter + launcher-label fallback:
     *  - When [internetOnly] is true (default: whatever [internetOnlyFilterEnabled] currently
     *    holds, so a Settings-page toggle takes effect the next time any screen — including one
     *    that never passes this parameter — reloads the picker), apps that don't hold the
     *    `INTERNET` permission ([AppUtils.hasInternetPermission]) are excluded — such an app
     *    cannot make network requests, so it has nothing for a traffic interceptor to ever
     *    capture; showing it just adds noise to the picker. The permission check fails open (an
     *    app is kept, not hidden) if the check itself throws, so a PackageManager quirk on some
     *    OEM build never silently disappears a real app. [totalInstalledAppCount] and
     *    [filteredOutAppCount] are updated on every call so the effect of the filter is never a
     *    silent, invisible change.
     *  - [pm.getApplicationLabel] occasionally returns the raw package name instead of a real
     *    display label for apps with a broken/missing `android:label` resolution.
     *    [resolveAppDisplayLabel] resolves the real display name via a launcher-activity label
     *    -> application label -> package name fallback chain (queried once per picker load via
     *    [buildLauncherLabelMap], not once per app) and never produces a blank name.
     */
    suspend fun loadInstalledApps(
        context: android.content.Context,
        internetOnly: Boolean = _internetOnlyFilterEnabled.value
    ) {
        val (apps, totalCandidates) = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching {
                val launcherLabels = buildLauncherLabelMap(pm)
                val candidates = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .filter { it.packageName != context.packageName }
                    .toList()

                val shown = candidates.asSequence()
                    .filter { info ->
                        !internetOnly || AppUtils.hasInternetPermission(info.packageName) { permission, packageName ->
                            pm.checkPermission(permission, packageName)
                        }
                    }
                    .map { info ->
                        val applicationLabel = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
                        val launcherLabel = launcherLabels[info.packageName]
                        val label = resolveAppDisplayLabel(launcherLabel, applicationLabel, info.packageName)
                        AppPackageItem(
                            packageName = info.packageName,
                            appName = label,
                            isInterceptEnabled = false,
                            isSystemApp = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                        )
                    }
                    .sortedBy { it.appName.lowercase() }
                    .toList()

                shown to candidates.size
            }.getOrDefault(emptyList<AppPackageItem>() to 0)
        }
        _installedApps.value = apps
        _totalInstalledAppCount.value = totalCandidates
        _filteredOutAppCount.value = (totalCandidates - apps.size).coerceAtLeast(0)
        _internetOnlyFilterEnabled.value = internetOnly
    }

    /** One `queryIntentActivities` call for every launchable activity's package -> display label. */
    private fun buildLauncherLabelMap(pm: android.content.pm.PackageManager): Map<String, String> =
        runCatching {
            val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, 0)
                .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        }.getOrDefault(emptyMap())

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
