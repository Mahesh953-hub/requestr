package com.example.interception.tls

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.data.interception.CapabilityState
import com.example.data.interception.FridaState
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState
import com.example.interception.CertificateAuthority
import com.example.interception.ConnectControl
import com.example.interception.quic.UdpPolicyRouter
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

/**
 * S1 task 13: one place that answers "why is (or isn't) MITM working for this app / host?".
 *
 * Every existing trust-related signal lives somewhere else and only answers one narrow question:
 * [com.example.interception.CaTrustGate] is a cached boolean ("is our CA in some trust store"),
 * [ConnectControl.PinningFailureRegistry] knows which hosts keep failing handshakes,
 * [TlsFailureClassifier] knows the last alert, [UdpPolicyRouter] knows what happened to QUIC, and
 * the capability probes know about root/frida. [TrustDiagnostics] pulls those into a single
 * [Report] with plain-language [Report.findings] so the UI (and a bug report) can explain the
 * situation instead of showing "TLS handshake failed" for the fifth time.
 *
 * Boundaries:
 *  - This is a *diagnostic* facade. It never changes behaviour: the fast per-connection decision
 *    stays with [com.example.interception.CaTrustGate], and [Report.caTrusted] is defined so that
 *    it is always the same boolean the gate's default probe would return (CA present in the
 *    system or user store) -- see `TrustDiagnosticsTest`.
 *  - Reports are on-demand and cached for [cacheTtlMs] (default 30 s, the same TTL as the gate)
 *    because scanning the trust stores and the package manager is not free. [invalidate] drops
 *    the cache, e.g. after the user installs the CA.
 *  - All inputs are injected as functions/interfaces so the whole thing is a pure-JVM unit test;
 *    [platform] wires the real Android sources.
 */
class TrustDiagnostics(
    /** SHA-256 fingerprint (lowercase hex, no separators) of requestr's current CA, or null when no CA exists yet. */
    private val ownCaFingerprint: () -> String?,
    private val stores: TrustStoreScanner,
    /** Per-app trust facts. Returns null for an unknown package. */
    private val appInspector: (packageName: String) -> AppTrustInfo?,
    private val pinningHistory: () -> List<ConnectControl.PinningFailureRegistry.HostHistory> =
        { ConnectControl.PinningFailureRegistry.snapshot() },
    private val mutualTlsHosts: () -> Set<String> = { MutualTlsSignals.hosts() },
    private val quicBranches: () -> Map<UdpPolicyRouter.Branch, Long> = { emptyMap() },
    private val capabilities: () -> CapabilityState = { InterceptionStateRepository.capabilities.value },
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
) {

    /** Which OS trust store a CA entry was found in. */
    enum class Store { SYSTEM, USER }

    /** One CA entry as seen in a trust store. [sha256] is lowercase hex, no separators. */
    data class CaEntry(val store: Store, val alias: String, val subject: String, val sha256: String)

    /** Read-only view of the device trust stores. */
    interface TrustStoreScanner {
        fun entries(): List<CaEntry>
    }

    /**
     * What a client app's manifest says about user-added CAs.
     *
     * Since Android 7.0, apps with `targetSdkVersion >= 24` do NOT trust user-installed CAs
     * unless their Network Security Config opts back in (`<certificates src="user"/>`) -- and the
     * compiled config is not readable from another app, so when [hasNetworkSecurityConfig] is
     * true the answer is "depends on the config", not "no".
     */
    data class AppTrustInfo(
        val packageName: String,
        val targetSdk: Int,
        val hasNetworkSecurityConfig: Boolean,
        val debuggable: Boolean,
    )

    enum class UserCaPolicy {
        /** targetSdk < 24: the platform default still trusts user CAs. */
        TRUSTS_USER_CAS,
        /** targetSdk >= 24 and no Network Security Config: user CAs are ignored; system store only. */
        SYSTEM_STORE_ONLY,
        /** targetSdk >= 24 with a Network Security Config we cannot read: may or may not opt in. */
        CUSTOM_CONFIG,
        /** Package not installed / not visible. */
        UNKNOWN,
    }

    /** How the requestr CA shows up in one store. */
    enum class Presence {
        /** An entry with our exact fingerprint. */
        INSTALLED,
        /** An entry whose alias/subject says "requestr" but whose fingerprint is not ours: a stale CA from an earlier install or a regeneration. */
        STALE,
        ABSENT,
    }

    data class StoreStatus(val store: Store, val presence: Presence, val entry: CaEntry?)

    data class HostFinding(
        val host: String,
        val failureCount: Int,
        val lastFailureAt: Long?,
        val bypassedUntil: Long?,
        val lastReason: String?,
        val mutualTlsRequired: Boolean,
    )

    data class Report(
        val generatedAt: Long,
        val ownCaFingerprint: String?,
        val system: StoreStatus,
        val user: StoreStatus,
        val app: AppTrustInfo?,
        val userCaPolicy: UserCaPolicy,
        val hosts: List<HostFinding>,
        val quicBranches: Map<UdpPolicyRouter.Branch, Long>,
        val capabilities: CapabilityState,
        /** Plain-language findings, most important first. */
        val findings: List<String>,
    ) {
        /** Same boolean [com.example.interception.CaTrustGate.DEFAULT_PROBE] answers: CA installed in system or user store. */
        val caTrusted: Boolean
            get() = system.presence == Presence.INSTALLED || user.presence == Presence.INSTALLED

        /**
         * Whether the inspected [app] can be expected to trust requestr's CA: true when the CA is
         * in the system store, or in the user store AND the app's policy admits user CAs. Null
         * when it depends on a Network Security Config we cannot read or the app is unknown.
         */
        val appWillTrustCa: Boolean?
            get() = when {
                system.presence == Presence.INSTALLED -> true
                user.presence != Presence.INSTALLED -> false
                else -> when (userCaPolicy) {
                    UserCaPolicy.TRUSTS_USER_CAS -> true
                    UserCaPolicy.SYSTEM_STORE_ONLY -> false
                    UserCaPolicy.CUSTOM_CONFIG, UserCaPolicy.UNKNOWN -> null
                }
            }

        /** First finding, for one-line status surfaces (e.g. the capture-method watcher). */
        val headline: String get() = findings.firstOrNull() ?: "No trust problems detected"
    }

    private val lock = Any()
    private var cached: Report? = null
    private var cachedPackage: String? = null
    private var cachedAt: Long = Long.MIN_VALUE

    /**
     * Builds (or returns the cached) report. [packageName] selects which client app's trust
     * policy to explain; the store scan and host history are app-independent. A different
     * [packageName] than the cached one forces a rebuild.
     */
    fun report(packageName: String? = null): Report {
        val now = clock()
        synchronized(lock) {
            val hit = cached
            if (hit != null && cachedPackage == packageName && now - cachedAt in 0 until cacheTtlMs) return hit
        }
        val fresh = build(now, packageName)
        synchronized(lock) {
            cached = fresh
            cachedPackage = packageName
            cachedAt = now
        }
        return fresh
    }

    /** Returns the cached report without rebuilding, or null when none is cached / expired. */
    fun cachedReport(): Report? = synchronized(lock) {
        cached?.takeIf { clock() - cachedAt in 0 until cacheTtlMs }
    }

    fun invalidate() = synchronized(lock) {
        cached = null
        cachedPackage = null
        cachedAt = Long.MIN_VALUE
    }

    private fun build(now: Long, packageName: String?): Report {
        val fingerprint = runCatching { ownCaFingerprint() }.getOrNull()?.lowercase()
        val entries = runCatching { stores.entries() }.getOrElse { e ->
            Log.w(TAG, "trust store scan failed: ${e.message}")
            emptyList()
        }
        val system = statusFor(Store.SYSTEM, entries, fingerprint)
        val user = statusFor(Store.USER, entries, fingerprint)

        val app = packageName?.let { runCatching { appInspector(it) }.getOrNull() }
        val policy = policyFor(app)

        val mtls = mutualTlsHosts().map { it.lowercase() }.toSet()
        val hostMap = LinkedHashMap<String, HostFinding>()
        for (h in pinningHistory()) {
            val key = h.host.lowercase()
            hostMap[key] = HostFinding(
                host = key,
                failureCount = h.failureCount,
                lastFailureAt = h.lastFailureAt,
                bypassedUntil = h.bypassedUntil?.takeIf { it > now },
                lastReason = h.lastReason,
                mutualTlsRequired = key in mtls,
            )
        }
        for (host in mtls) {
            if (host !in hostMap) hostMap[host] = HostFinding(host, 0, null, null, null, mutualTlsRequired = true)
        }
        val hosts = hostMap.values.sortedWith(compareByDescending<HostFinding> { it.failureCount }.thenBy { it.host })

        val quic = quicBranches()
        val caps = runCatching { capabilities() }.getOrDefault(CapabilityState())

        val findings = findings(fingerprint, system, user, app, policy, hosts, quic, caps)
        return Report(now, fingerprint, system, user, app, policy, hosts, quic, caps, findings)
    }

    private fun statusFor(store: Store, entries: List<CaEntry>, fingerprint: String?): StoreStatus {
        val inStore = entries.filter { it.store == store }
        val exact = fingerprint?.let { fp -> inStore.firstOrNull { it.sha256.equals(fp, ignoreCase = true) } }
        if (exact != null) return StoreStatus(store, Presence.INSTALLED, exact)
        val stale = inStore.firstOrNull { looksLikeOurs(it) }
        return if (stale != null) StoreStatus(store, Presence.STALE, stale) else StoreStatus(store, Presence.ABSENT, null)
    }

    private fun findings(
        fingerprint: String?,
        system: StoreStatus,
        user: StoreStatus,
        app: AppTrustInfo?,
        policy: UserCaPolicy,
        hosts: List<HostFinding>,
        quic: Map<UdpPolicyRouter.Branch, Long>,
        caps: CapabilityState,
    ): List<String> {
        val out = ArrayList<String>()

        // 1. CA presence -- the thing that breaks everything when wrong.
        when {
            fingerprint == null ->
                out += "requestr has not generated its CA yet; start interception once, then export and install the certificate."
            system.presence == Presence.INSTALLED ->
                out += "requestr CA is installed in the SYSTEM trust store (${system.entry?.alias}); every app, including Chrome/WebView and apps that ignore user CAs, will trust it."
            user.presence == Presence.INSTALLED ->
                out += "requestr CA is installed in the USER trust store (${user.entry?.alias}); apps targeting Android 7+ only trust it if their Network Security Config allows user CAs."
            else ->
                out += "requestr CA is NOT installed in any trust store; TLS interception will fail for every app until it is installed (Settings > Security > Install a certificate > CA certificate)."
        }
        for (st in listOf(system, user)) {
            if (st.presence == Presence.STALE) {
                val current = fingerprint?.let { "the current CA (${it.take(16)}...)" } ?: "any CA this install has generated"
                out += "A different requestr CA (${st.entry?.alias}, sha256 ${st.entry?.sha256?.take(16)}...) is installed in the ${st.store.name} store; it does not match $current. Remove the old one and install the current export."
            }
        }

        // 2. Per-app policy.
        if (app != null) {
            when (policy) {
                UserCaPolicy.SYSTEM_STORE_ONLY ->
                    if (system.presence != Presence.INSTALLED) {
                        out += "${app.packageName} targets API ${app.targetSdk} without a Network Security Config, so it ignores user-installed CAs; interception needs the CA in the system store (root) or Frida hooking."
                    }
                UserCaPolicy.CUSTOM_CONFIG ->
                    if (system.presence != Presence.INSTALLED) {
                        out += "${app.packageName} ships a Network Security Config (targets API ${app.targetSdk}); it trusts user CAs only if that config opts in with <certificates src=\"user\"/>" +
                            (if (app.debuggable) " (debuggable build: <debug-overrides> may also apply)." else ".")
                    }
                UserCaPolicy.TRUSTS_USER_CAS ->
                    out += "${app.packageName} targets API ${app.targetSdk} (< 24) and therefore trusts user-installed CAs."
                UserCaPolicy.UNKNOWN -> {}
            }
        }

        // 3. Hosts that look pinned or require a client certificate.
        for (h in hosts) {
            if (h.mutualTlsRequired) {
                out += "${h.host} requires a client certificate (mutual TLS): the server rejected requestr's upstream handshake with certificate_required. Add the app's client identity under Settings > Client certificates or bypass this host."
            }
            if (h.failureCount > 0) {
                val bypass = if (h.bypassedUntil != null) "; auto-bypassed" else ""
                val reason = h.lastReason?.let { " (last: $it)" } ?: ""
                out += "${h.host} failed ${h.failureCount} TLS handshake(s) right after leaf issuance$reason$bypass -- the app likely pins its certificate or does not trust the CA."
            }
        }

        // 4. QUIC / Chromium facts. Chrome and WebView trust the Android stores (system + user
        // with a per-app NSC caveat for WebView clients), *unlike* desktop Chrome's own root
        // store -- so the polarity question is not the CA, it is HTTP/3: QUIC cannot be MITM'd by
        // this proxy, so UDP/443 must be blocked to force a TCP fallback.
        val blocked = quic[UdpPolicyRouter.Branch.BLOCKED] ?: 0L
        val observed = quic[UdpPolicyRouter.Branch.OBSERVED] ?: 0L
        if (observed > 0 && blocked == 0L) {
            out += "$observed QUIC flow(s) were observed but not blocked; Chrome/Chromium apps that reach a server over HTTP/3 bypass TLS interception until UDP/443 is blocked (quicTeePolicy) and they fall back to TCP."
        } else if (blocked > 0) {
            out += "$blocked QUIC flow(s) were blocked so Chrome/Chromium clients fall back to TCP, where the CA above applies."
        }

        // 5. Runtime signals.
        if (caps.frida == FridaState.RUNNING) {
            out += "frida-server is reachable: pinning bypass / trust hooks can be applied at runtime even when the CA is not trusted by the app."
        }
        if (caps.root == RootState.GRANTED && system.presence != Presence.INSTALLED && fingerprint != null) {
            out += "Root is granted: the CA could be installed into the system store so every app trusts it."
        }
        return out
    }

    companion object {
        private const val TAG = "TrustDiagnostics"
        const val DEFAULT_CACHE_TTL_MS = 30_000L

        /** Matches [com.example.interception.CapabilityProbe.CA_ALIAS_PREFIX] semantics. */
        private const val OUR_MARKER = "requestr"

        internal fun looksLikeOurs(entry: CaEntry): Boolean =
            entry.alias.contains(OUR_MARKER, ignoreCase = true) || entry.subject.contains(OUR_MARKER, ignoreCase = true)

        internal fun policyFor(app: AppTrustInfo?): UserCaPolicy = when {
            app == null -> UserCaPolicy.UNKNOWN
            app.targetSdk < Build.VERSION_CODES.N -> UserCaPolicy.TRUSTS_USER_CAS
            app.hasNetworkSecurityConfig -> UserCaPolicy.CUSTOM_CONFIG
            else -> UserCaPolicy.SYSTEM_STORE_ONLY
        }

        fun sha256Hex(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        @Volatile private var shared: TrustDiagnostics? = null

        /** Process-wide instance backed by the real Android trust stores and package manager. */
        fun get(context: Context): TrustDiagnostics {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                val app = context.applicationContext
                return TrustDiagnostics(
                    ownCaFingerprint = { CertificateAuthority.peekCertificate(app)?.let(::sha256Hex) },
                    stores = AndroidTrustStoreScanner(),
                    appInspector = { pkg -> inspectPackage(app.packageManager, pkg) },
                    quicBranches = { UdpPolicyRouter.latestBranchCounts() },
                ).also { shared = it }
            }
        }

        internal fun inspectPackage(pm: PackageManager, packageName: String): AppTrustInfo? {
            val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return null
            // ApplicationInfo.networkSecurityConfigRes is @hide on every API level; read it
            // reflectively (present since API 24) and treat any failure as "no config".
            val hasNsc = runCatching {
                val field = ApplicationInfo::class.java.getField("networkSecurityConfigRes")
                field.getInt(info) != 0
            }.getOrDefault(false)
            return AppTrustInfo(
                packageName = packageName,
                targetSdk = info.targetSdkVersion,
                hasNetworkSecurityConfig = hasNsc,
                debuggable = (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            )
        }
    }

    /**
     * Real store scan. `AndroidCAStore` exposes both stores with `system:`/`user:` alias
     * prefixes; the on-disk system directories are read as a fallback for the (root-installed)
     * case where the keystore view is stale, and for the APEX-mounted store on Android 14+.
     */
    class AndroidTrustStoreScanner : TrustStoreScanner {
        override fun entries(): List<CaEntry> {
            val out = LinkedHashMap<String, CaEntry>()
            runCatching {
                val ks = KeyStore.getInstance("AndroidCAStore")
                ks.load(null, null)
                val aliases = ks.aliases()
                while (aliases.hasMoreElements()) {
                    val alias = aliases.nextElement()
                    val cert = runCatching { ks.getCertificate(alias) as? X509Certificate }.getOrNull() ?: continue
                    val store = if (alias.startsWith("user:")) Store.USER else Store.SYSTEM
                    val fp = sha256Hex(cert)
                    out.putIfAbsent(fp, CaEntry(store, alias, cert.subjectX500Principal.name, fp))
                }
            }.onFailure { Log.w(TAG, "AndroidCAStore scan failed: ${it.message}") }
            for (dir in SYSTEM_DIRS) {
                val files = runCatching { File(dir).listFiles() }.getOrNull() ?: continue
                for (f in files) {
                    val cert = runCatching {
                        f.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
                    }.getOrNull() ?: continue
                    val fp = sha256Hex(cert)
                    out.putIfAbsent(fp, CaEntry(Store.SYSTEM, "system:${f.name}", cert.subjectX500Principal.name, fp))
                }
            }
            return out.values.toList()
        }

        private companion object {
            val SYSTEM_DIRS = listOf(
                "/apex/com.android.conscrypt/cacerts",
                "/system/etc/security/cacerts",
            )
        }
    }
}

/**
 * Hosts whose upstream handshake failed because the server demanded a client certificate.
 * Recorded by [com.example.interception.MitmProxyEngine] from the upstream `SSLException`;
 * consumed by [TrustDiagnostics] to say "this host needs mTLS" instead of "TLS failed".
 */
object MutualTlsSignals {
    private val hosts = ConcurrentHashMap<String, Long>()

    /** True when [error] is the TLS 1.3 `certificate_required` or the TLS 1.2 `handshake_failure`-after-CertificateRequest shape used by mTLS servers. */
    fun isClientCertificateDemand(error: Throwable): Boolean {
        var t: Throwable? = error
        while (t != null) {
            val msg = t.message?.lowercase() ?: ""
            if ("certificate_required" in msg || "certificate required" in msg || "bad_certificate" in msg && "client" in msg) return true
            t = t.cause
        }
        return false
    }

    fun record(host: String, at: Long = System.currentTimeMillis()) {
        hosts[host.lowercase()] = at
    }

    /** Records [host] iff [error] looks like a client-certificate demand. Returns true when recorded. */
    fun recordIfDemand(host: String, error: Throwable, at: Long = System.currentTimeMillis()): Boolean {
        if (!isClientCertificateDemand(error)) return false
        record(host, at)
        return true
    }

    fun hosts(): Set<String> = hosts.keys.toSet()

    fun clearAll() = hosts.clear()
}
