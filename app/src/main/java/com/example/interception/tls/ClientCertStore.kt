package com.example.interception.tls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** How [CertificateEngine] must treat TLS to a host matched by a [DomainCertPolicy]. */
enum class DomainCertMode {
    /** Mint a leaf under the requestr CA (default behaviour for every unmatched host). */
    AUTO,
    /** Present the imported server certificate/key verbatim instead of a minted leaf. */
    CUSTOM_SERVER_CERT,
    /** Do not MITM at all -- relay the TLS bytes opaquely. */
    PASSTHROUGH,
}

/**
 * Per-domain certificate override. [hostPattern] is an exact lowercase hostname, a single-label
 * wildcard (`*.example.com` -- matches any depth below `example.com`, not the apex), or `*`
 * (catch-all). [clientP12Ref]/[clientP12Alias] configure mutual TLS towards the upstream: the
 * private key + chain at that alias is offered through a real [KeyManager] on the upstream leg.
 */
data class DomainCertPolicy(
    val hostPattern: String,
    val mode: DomainCertMode = DomainCertMode.AUTO,
    val serverCertRef: String? = null,
    val clientP12Ref: String? = null,
    val clientP12Alias: String? = null,
) {
    val normalizedPattern: String get() = hostPattern.trim().trimEnd('.').lowercase()
}

/** Named, user-actionable failure when configured client-certificate material cannot be used. */
class ClientCertException(message: String) : Exception(message)

/**
 * S1 task 8: file-backed store for per-domain certificate policies and the PKCS#12 material they
 * reference. Deliberately **not** a Room table -- the schema ledger (master plan §4) reserves
 * every migration from v19 on for S2, so S1 keeps this in app-private files:
 *
 * ```
 * <root>/domain_cert_policies.json   -- the DomainCertPolicy list
 * <root>/material/<ref>.p12          -- imported bundles, re-wrapped under a per-store password
 * <root>/material/.store-secret      -- that random password (app-private storage, 0600)
 * ```
 *
 * Imported bundles are re-wrapped so the user's original password never has to be persisted.
 * All operations are synchronous file I/O guarded by a single lock; callers on the hot path only
 * ever hit [resolve], which is served from the in-memory list.
 */
class ClientCertStore(private val root: File) {

    private val lock = Any()
    private val policiesFile = File(root, "domain_cert_policies.json")
    private val materialDir = File(root, "material")
    private var cached: List<DomainCertPolicy>? = null

    /** Every configured policy, in insertion order. */
    fun policies(): List<DomainCertPolicy> = synchronized(lock) { load() }

    /** Adds or replaces the policy whose pattern equals [policy]'s (case-insensitive). */
    fun upsert(policy: DomainCertPolicy) = synchronized(lock) {
        val key = policy.normalizedPattern
        require(key.isNotEmpty()) { "hostPattern must not be blank" }
        if (policy.mode == DomainCertMode.CUSTOM_SERVER_CERT) {
            requireNotNull(policy.serverCertRef) { "CUSTOM_SERVER_CERT needs serverCertRef" }
        }
        val next = load().filterNot { it.normalizedPattern == key } + policy.copy(hostPattern = key)
        save(next)
    }

    fun remove(hostPattern: String): Boolean = synchronized(lock) {
        val key = hostPattern.trim().trimEnd('.').lowercase()
        val current = load()
        val next = current.filterNot { it.normalizedPattern == key }
        if (next.size == current.size) return false
        save(next)
        true
    }

    /**
     * Most specific matching policy for [host], or null: an exact match beats any wildcard, a
     * longer (more specific) wildcard suffix beats a shorter one, and the `*` catch-all comes last.
     */
    fun resolve(host: String): DomainCertPolicy? {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return null
        val all = synchronized(lock) { load() }
        return all.filter { matches(it.normalizedPattern, h) }
            .maxWithOrNull(compareBy({ specificity(it.normalizedPattern) }, { it.normalizedPattern.length }))
    }

    /** Result of a successful [importMaterial]. */
    data class Imported(val ref: String, val aliases: List<String>, val subject: String?)

    /**
     * Parses [p12Bytes] under [password] (any bundle with at least one private-key entry; CA or
     * leaf), re-wraps it under the store password and saves it as `<ref>.p12`. Never persists
     * [password]. Returns a failure with a human-readable reason rather than throwing.
     */
    fun importMaterial(ref: String, p12Bytes: ByteArray, password: CharArray): Result<Imported> = synchronized(lock) {
        val safeRef = sanitizeRef(ref)
        val source = try {
            KeyStore.getInstance("PKCS12").apply { p12Bytes.inputStream().use { load(it, password) } }
        } catch (e: Exception) {
            return Result.failure(ClientCertException("Could not open PKCS#12 bundle: ${e.message}"))
        }
        val aliases = source.aliases().asSequence().filter { source.isKeyEntry(it) }.toList()
        if (aliases.isEmpty()) return Result.failure(ClientCertException("PKCS#12 bundle has no private-key entry"))
        val storePassword = storeSecret()
        val target = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        for (alias in aliases) {
            val key = try {
                source.getKey(alias, password) as? PrivateKey
            } catch (e: Exception) {
                return Result.failure(ClientCertException("Could not read private key for alias '$alias': ${e.message}"))
            } ?: return Result.failure(ClientCertException("Alias '$alias' key is not a usable private key"))
            val chain = source.getCertificateChain(alias)
                ?: return Result.failure(ClientCertException("Alias '$alias' has no certificate chain"))
            target.setKeyEntry(alias, key, storePassword, chain)
        }
        materialDir.mkdirs()
        val file = File(materialDir, "$safeRef.p12")
        val tmp = File(materialDir, "$safeRef.p12.tmp")
        tmp.outputStream().use { target.store(it, storePassword) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) return Result.failure(ClientCertException("Could not persist material '$safeRef'"))
        }
        val subject = (source.getCertificate(aliases.first()) as? X509Certificate)?.subjectX500Principal?.name
        Result.success(Imported(safeRef, aliases, subject))
    }

    fun deleteMaterial(ref: String): Boolean = synchronized(lock) { File(materialDir, "${sanitizeRef(ref)}.p12").delete() }

    fun hasMaterial(ref: String): Boolean = File(materialDir, "${sanitizeRef(ref)}.p12").isFile

    /** Loads the re-wrapped bundle for [ref], or null when it was never imported. */
    fun loadKeyStore(ref: String): KeyStore? = synchronized(lock) {
        val file = File(materialDir, "${sanitizeRef(ref)}.p12")
        if (!file.isFile) return null
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, storeSecret()) } }
    }

    /** Server-side material to present verbatim for a [DomainCertMode.CUSTOM_SERVER_CERT] policy. */
    class ServerMaterial(val chain: Array<X509Certificate>, val privateKey: PrivateKey)

    fun serverMaterial(ref: String): ServerMaterial? {
        val ks = loadKeyStore(ref) ?: return null
        val alias = ks.aliases().asSequence().firstOrNull { ks.isKeyEntry(it) } ?: return null
        val key = ks.getKey(alias, storeSecret()) as? PrivateKey ?: return null
        val chain = ks.getCertificateChain(alias)?.map { it as X509Certificate }?.toTypedArray() ?: return null
        return ServerMaterial(chain, key)
    }

    /**
     * Client [KeyManager]s for the upstream leg of [policy], or null when the policy carries no
     * client material. Throws [ClientCertException] naming the ref/alias when the configured
     * material is missing or the alias is not in the bundle -- a misconfigured mTLS policy must
     * surface as a named failure, never as a silent "no client cert offered".
     */
    fun clientKeyManagers(policy: DomainCertPolicy): Array<KeyManager>? {
        val ref = policy.clientP12Ref ?: return null
        val ks = loadKeyStore(ref)
            ?: throw ClientCertException("client certificate bundle '$ref' for ${policy.hostPattern} is not imported")
        val alias = policy.clientP12Alias
        if (alias != null && !(ks.containsAlias(alias) && ks.isKeyEntry(alias))) {
            val available = ks.aliases().asSequence().filter { ks.isKeyEntry(it) }.joinToString(", ")
            throw ClientCertException("alias '$alias' not found in client bundle '$ref' (available: $available)")
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, storeSecret())
        val managers = kmf.keyManagers
        if (alias == null) return managers
        return managers.map { km -> if (km is X509KeyManager) FixedAliasKeyManager(km, alias) else km }.toTypedArray()
    }

    // ── persistence ──────────────────────────────────────────────────────────────────────────

    private fun load(): List<DomainCertPolicy> {
        cached?.let { return it }
        val parsed = if (policiesFile.isFile) {
            runCatching {
                val arr = JSONArray(policiesFile.readText())
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    DomainCertPolicy(
                        hostPattern = o.getString("hostPattern"),
                        mode = runCatching { DomainCertMode.valueOf(o.optString("mode", "AUTO")) }.getOrDefault(DomainCertMode.AUTO),
                        serverCertRef = o.optString("serverCertRef").ifEmpty { null },
                        clientP12Ref = o.optString("clientP12Ref").ifEmpty { null },
                        clientP12Alias = o.optString("clientP12Alias").ifEmpty { null },
                    )
                }
            }.getOrDefault(emptyList())
        } else emptyList()
        cached = parsed
        return parsed
    }

    private fun save(list: List<DomainCertPolicy>) {
        root.mkdirs()
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("hostPattern", p.normalizedPattern)
                    put("mode", p.mode.name)
                    p.serverCertRef?.let { put("serverCertRef", it) }
                    p.clientP12Ref?.let { put("clientP12Ref", it) }
                    p.clientP12Alias?.let { put("clientP12Alias", it) }
                },
            )
        }
        val tmp = File(root, "domain_cert_policies.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(policiesFile)) {
            policiesFile.delete()
            check(tmp.renameTo(policiesFile)) { "could not persist domain cert policies" }
        }
        cached = list
    }

    private fun storeSecret(): CharArray {
        materialDir.mkdirs()
        val f = File(materialDir, ".store-secret")
        if (!f.isFile) {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            f.writeText(bytes.joinToString("") { "%02x".format(it) })
            runCatching { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true) }
        }
        return f.readText().trim().toCharArray()
    }

    private fun sanitizeRef(ref: String): String {
        val cleaned = ref.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") { "invalid material ref '$ref'" }
        return cleaned
    }

    /** Forces the client leg to use exactly [alias] regardless of what the server's CA hints prefer. */
    private class FixedAliasKeyManager(private val delegate: X509KeyManager, private val alias: String) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
            delegate.chooseServerAlias(keyType, issuers, socket)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            delegate.getServerAliases(keyType, issuers)
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = delegate.getCertificateChain(alias)
        override fun getPrivateKey(alias: String?): PrivateKey? = delegate.getPrivateKey(alias)
    }

    companion object {
        @Volatile private var instance: ClientCertStore? = null

        /** Process-wide store rooted at `filesDir/tls`. */
        fun get(context: Context): ClientCertStore =
            instance ?: synchronized(this) {
                instance ?: ClientCertStore(File(context.applicationContext.filesDir, "tls")).also { instance = it }
            }

        internal fun resetForTest() { instance = null }

        /** `*` matches everything; `*.suffix` matches any host strictly below `suffix`; otherwise exact. */
        fun matches(pattern: String, host: String): Boolean {
            if (pattern == "*") return true
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1) // ".example.com"
                return host.length > suffix.length && host.endsWith(suffix)
            }
            return pattern == host
        }

        private fun specificity(pattern: String): Int = when {
            pattern == "*" -> 0
            pattern.startsWith("*.") -> 1
            else -> 2
        }
    }
}
