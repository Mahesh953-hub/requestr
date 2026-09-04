package com.example.interception

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.example.data.interception.ActiveTier
import com.example.data.interception.CapabilityState
import com.example.data.interception.CaptureLifecycleOwner
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
import com.example.data.prefs.AppFilterMode
import com.example.data.prefs.CaptureTierPreference
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.flow.first

/**
 * Enforces the strict pre-flight sequence before capture may start (APP_v2.md Bug 3.4).
 *
 * Order is non-negotiable:
 *  1. CA certificate must genuinely be installed — re-probed, never trusted from cache.
 *  2. Capture tier resolved by real capability, priority Root > Shizuku > VPN.
 *  3. An app selection must exist (explicit list, or "all apps" chosen).
 *  4. Only then may VPN consent be requested and capture begin.
 */
object PreflightController {

    sealed interface Result {
        /** Everything passed; [consentIntent] is non-null when the system dialog is still needed. */
        data class Ready(
            val tier: ActiveTier,
            val method: CaptureMethod,
            val port: Int,
            val consentIntent: Intent?,
        ) : Result

        /** CA missing — caller must run the install flow and re-probe. */
        data class CaRequired(val installIntent: Intent) : Result

        /** No apps chosen and "all apps" not selected. */
        data object AppSelectionRequired : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Runs the full gate. Returns the first blocking condition rather than starting capture,
     * so the UI can surface exactly what is missing.
     */
    suspend fun run(context: Context): Result {
        val prefs = SettingsDataStore.get(context)
        val recordMode = prefs.captureRecordMode.first() // "VPN" or "PROXY"
        val enhanced = prefs.enhancedMode.first()
        val tierPreference = prefs.captureTierPreference.first()

        // Step 1 — app selection check (before consent so we never prompt then fail)
        val filterMode = prefs.appFilterMode.first()
        val selected = prefs.selectedPackages.first()
        if (filterMode == AppFilterMode.SELECTED_APPS && selected.isEmpty()) {
            return Result.AppSelectionRequired
        }

        // Step 2 — probe capabilities
        CapabilityProbe.probeAll(context)
        val capabilities: CapabilityState = InterceptionStateRepository.capabilities.value

        // CA check: only required for MITM_PROXY mode (TLS decryption)
        val caState = CapabilityProbe.probeCaInstalled()
        InterceptionStateRepository.updateCapabilities(ca = caState)
        val caInstalled = caState.let {
            it == com.example.data.interception.CaState.USER_STORE ||
                it == com.example.data.interception.CaState.SYSTEM_STORE
        }
        prefs.setCaInstalledHint(caInstalled)

        // Resolve capture method and port. Tier and lifecycle owner are deliberately *not*
        // restated per-branch below (each used to hardcode its own ActiveTier/CaptureLifecycleOwner
        // literal that happened to agree with CaptureMethod.engineTier()/.engineLifecycleOwner());
        // they are derived once, right after this `when`, from the single canonical function so
        // this resolver and every engine's own markActive() call can no longer independently drift
        // apart -- see CaptureMethod.engineTier()'s KDoc for the HttpProxyEngine bug this closes.
        val method: CaptureMethod
        val port: Int

        when {
            // FORCE_VPN_ONLY (Settings > Default Capture Tier) must win over everything else,
            // including genuine Root/Shizuku availability -- that is the entire point of the
            // preference (APP_v2.md Bug: FORCE_VPN_ONLY was persisted by SettingsScreen but
            // never read here, so choosing it had zero effect on which tier actually ran).
            tierPreference == CaptureTierPreference.FORCE_VPN_ONLY -> {
                method = CaptureMethod.VPN_TUN
                port = 0
            }
            // Root/Shizuku always win
            capabilities.isRootUsable -> {
                method = CaptureMethod.ROOT_IPTABLES
                port = prefs.proxyPort.first()
                if (!caInstalled) return Result.CaRequired(CertificateAuthority.buildInstallIntent(context))
            }
            capabilities.isShizukuUsable -> {
                method = CaptureMethod.SHIZUKU_TUN
                port = prefs.proxyPort.first()
                if (!caInstalled) return Result.CaRequired(CertificateAuthority.buildInstallIntent(context))
            }
            recordMode == "PROXY" -> {
                port = prefs.proxyPort.first()
                if (caInstalled) { method = CaptureMethod.MITM_PROXY }
                else { method = CaptureMethod.HTTP_PROXY }
            }
            else -> {
                if (caInstalled) {
                    method = CaptureMethod.MITM_PROXY
                    port = prefs.proxyPort.first()
                } else {
                    method = CaptureMethod.VPN_TUN
                    port = 0
                }
            }
        }
        // Safe: none of the branches above can ever resolve `method` to ROOT_FRIDA (it has no
        // single true tier -- see engineTier()'s KDoc), so this cannot throw in practice. If a
        // future branch ever adds ROOT_FRIDA here, this throwing loudly is exactly the right
        // failure mode -- silently picking a tier for it would be the same "copy-pasted value
        // nobody deliberately chose" defect this refactor exists to close.
        val tier: ActiveTier = method.engineTier()

        if (method == CaptureMethod.MITM_PROXY && !caInstalled) {
            return Result.CaRequired(CertificateAuthority.buildInstallIntent(context))
        }

        val consent = if (method == CaptureMethod.VPN_TUN) VpnService.prepare(context) else null

        // Which engine's stop() will actually own this session once armed -- see
        // CaptureLifecycleOwner's KDoc for why this must not be derived from `method` at the
        // point of stopping (this is the one place it is safe to derive it, since `method` and
        // `tier` were both *just* resolved together above, fresh, for this exact capture attempt).
        // Same ROOT_FRIDA note as `tier` above applies here.
        val lifecycleOwner: CaptureLifecycleOwner = method.engineLifecycleOwner()

        InterceptionStateRepository.markArmed(tier = tier, port = port, method = method, lifecycleOwner = lifecycleOwner)
        if (enhanced) {
            android.util.Log.i("Preflight", "Enhanced: mode=$recordMode method=$method port=$port tier=$tier")
        }

        return Result.Ready(tier = tier, method = method, port = port, consentIntent = consent)
    }

    // ── Engine instances (singleton per method; lazy so they don't allocate until needed) ──
    //
    // MitmProxyEngine is constructed on first use with the caller's context so its
    // onDecryptedTransaction callback can persist decrypted HTTP transactions into
    // Room (they then show up live in the capture list UI, which observes the DAO).
    private var mitmProxy: MitmProxyEngine? = null

    @Synchronized
    private fun mitmProxy(context: Context): MitmProxyEngine =
        mitmProxy ?: MitmProxyEngine(
            // S2 task 15: queued; the old runBlocking(Dispatchers.IO) parked the MITM relay
            // thread on Room for every transaction. S2 task 18: measured phases ride along.
            // S3 task 20: the queue is the CaptureSink (parent rows + protocol children).
            sink = com.example.data.persistence.PersistenceQueue.get(context.applicationContext),
        ).also { mitmProxy = it }

    private val rootEngine by lazy { RootIptablesEngine() }
    private val shizukuEngine by lazy { ShizukuTunEngine() }
    private val httpProxy by lazy { HttpProxyEngine() }

    /**
     * Starts capture using the engine appropriate for the resolved [CaptureMethod].
     * Only call after [run] returned [Result.Ready] with consent satisfied.
     *
     * Previously this unconditionally called `RequestrVpnService.start()` regardless of
     * the resolved method — Root/Shizuku/MITM tiers were cosmetic (AUDIT_REPORT Fix #1/#2).
     *
     * Audit fix (capture start/stop state desync): every branch below is wrapped by
     * [InterceptionStateRepository.runStart], which guarantees a clean `markError` transition
     * if the branch throws (e.g. `MitmProxyEngine`/`HttpProxyEngine`'s `ServerSocket(port)`
     * binding a port already in use) or returns `false` (e.g. `RootIptablesEngine`/
     * `ShizukuTunEngine` reporting a real `su`/binder failure) — see that function's KDoc for
     * why this used to leave the FAB stuck on the `ARMED` state [run] had already set, forever.
     * VPN_TUN/ROOT_FRIDA return `true` unconditionally because `RequestrVpnService` is async and
     * already guarantees its own `markActive`/`markError` from inside `startCapture()`/`fail()`.
     */
    fun startCapture(context: Context, method: CaptureMethod = CaptureMethod.VPN_TUN, port: Int = 8080) {
        InterceptionStateRepository.runStart(method.name) {
            when (method) {
                CaptureMethod.VPN_TUN -> {
                    RequestrVpnService.start(context)
                    true
                }
                CaptureMethod.MITM_PROXY -> {
                    // MITM proxy runs as a local CONNECT proxy. For full capture, VPN still
                    // routes traffic to it — but the MITM engine handles TLS termination.
                    // Decrypted transactions are persisted to Room via the engine's
                    // onDecryptedTransaction callback (see mitmProxy(context)).
                    mitmProxy(context).start(context, port)
                    true
                }
                CaptureMethod.ROOT_IPTABLES -> {
                    // Root: iptables redirect to MITM proxy port, no VPN needed
                    val mitmPort = mitmProxy(context).start(context, port)
                    val uid = android.os.Process.myUid()
                    val ok = rootEngine.start(mitmPort, uid)
                    if (!ok) {
                        // The MITM proxy socket is already listening at this point but iptables
                        // never got installed -- don't leave it orphaned behind a clean ERROR.
                        mitmProxy?.stop()
                    }
                    ok
                }
                CaptureMethod.SHIZUKU_TUN -> {
                    // Shizuku: same approach as root but via Shizuku shell
                    val mitmPort = mitmProxy(context).start(context, port)
                    val ok = shizukuEngine.start(mitmPort)
                    if (!ok) {
                        mitmProxy?.stop()
                    }
                    ok
                }
                CaptureMethod.HTTP_PROXY -> {
                    httpProxy.start(context, port)
                    true
                }
                CaptureMethod.ROOT_FRIDA -> {
                    // Frida requires external frida-server; start VPN as fallback for traffic
                    // capture, Frida scripts handled separately
                    RequestrVpnService.start(context)
                    true
                }
            }
        }
    }

    /**
     * Stops capture for whichever engine actually owns the active session's lifecycle.
     *
     * Audit fix (stop routes to the wrong engine for native-bridge VPN sessions): this used to
     * be keyed by [CaptureMethod], which doubles as row-attribution -- a native-bridge VPN
     * session is owned by [RequestrVpnService] but genuinely attributes captured rows to
     * [CaptureMethod.MITM_PROXY], so routing the *stop* off that same field tore down this
     * controller's unrelated singleton [mitmProxy] while the real owning service (and its tun
     * interface) stayed alive -- the FAB then confidently showed "stopped" while capture kept
     * running. [owner] (see [CaptureLifecycleOwner]) is a field that exists for exactly one
     * purpose -- which `.stop()` to call -- so this class of bug cannot recur for a future tier.
     *
     * Audit fix (capture start/stop state desync): every tier converges to `CONFIGURED_IDLE`
     * exactly once, but *how* differs by whether the tier already owns a real completion signal:
     *
     * - VPN_SERVICE is handled by [RequestrVpnService], which now (lifecycle-race fix) runs its
     *   own `cleanup()` before calling [InterceptionStateRepository.markStopped] itself from
     *   inside its internal `stopCapture(reason)` -- so by the time the shared state converges,
     *   the tun interface really is torn down, not just "an intent got sent". This tier is
     *   deliberately called *outside* [InterceptionStateRepository.runStop] here: wrapping it
     *   too would make `runStop`'s `finally` fire `markStopped` immediately after dispatching the
     *   (fire-and-forget) stop `Intent`, i.e. before the service's real teardown even starts --
     *   reintroducing the exact "stopped shown before the tun is actually torn down" race this
     *   whole fix exists to close, at an even earlier point than before.
     * - MITM_PROXY_ENGINE/ROOT_IPTABLES_ENGINE/SHIZUKU_TUN_ENGINE/HTTP_PROXY_ENGINE have no
     *   equivalent owned completion signal -- their `.stop()` calls just tear down a
     *   socket/process synchronously with nothing to guarantee the transition -- so they route
     *   through [InterceptionStateRepository.runStop], which unconditionally converges to
     *   `markStopped` right after that synchronous teardown returns, even if it throws.
     * - NONE/FRIDA_ENGINE own nothing reachable through this controller (no active session, or
     *   [FridaCaptureEngine] which is never wired into [startCapture]/[stopCapture] and manages
     *   its own lifecycle directly) -- a no-op here is correct.
     *
     * A future tier added to this `when` should default to the `runStop` branch unless it gains
     * its own service-owned convergence signal the way VPN_SERVICE has.
     */
    fun stopCapture(context: Context, owner: CaptureLifecycleOwner = CaptureLifecycleOwner.VPN_SERVICE) {
        when (owner) {
            CaptureLifecycleOwner.VPN_SERVICE -> RequestrVpnService.stop(context)
            CaptureLifecycleOwner.MITM_PROXY_ENGINE -> InterceptionStateRepository.runStop { mitmProxy?.stop() }
            CaptureLifecycleOwner.ROOT_IPTABLES_ENGINE -> InterceptionStateRepository.runStop {
                rootEngine.stop(android.os.Process.myUid())
                mitmProxy?.stop()
            }
            CaptureLifecycleOwner.SHIZUKU_TUN_ENGINE -> InterceptionStateRepository.runStop {
                shizukuEngine.stop(mitmProxy?.port ?: 0)
                mitmProxy?.stop()
            }
            CaptureLifecycleOwner.HTTP_PROXY_ENGINE -> InterceptionStateRepository.runStop { httpProxy.stop() }
            CaptureLifecycleOwner.NONE, CaptureLifecycleOwner.FRIDA_ENGINE -> {
                // Nothing reachable through this controller owns an active session.
            }
        }
    }
}
