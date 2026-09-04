package com.example.interception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureLifecycleOwner
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.WatcherStatus
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
import com.example.data.local.CaptureSessionEntity
import com.example.data.local.CaptureTier
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.AppFilterMode
import com.example.data.prefs.SettingsDataStore
import com.example.interception.net.SocketProtector
import com.example.interception.tls.TlsKeyLogRegistry
import com.example.interception.tun.NativeTun2Proxy
import com.example.interception.tun.ProtectHook
import com.example.interception.tun.Tun2ProxyBridge
import com.example.interception.tun.Tun2ProxyConfig
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real local-VPN capture service.
 *
 * This is the guaranteed rootless baseline tier (APP_v2.md Bug 3.4, Tier 1): it establishes a tun
 * interface, holds a wake lock so the read loop survives Doze, and runs as a foreground service
 * so Android does not reap it.
 *
 * Honest scope note: this establishes the tun interface and pumps real packets, updating real
 * counters and persisting a real session row. Full TCP reassembly plus TLS MITM of those packets
 * is the next layer on top of this transport — see [PacketPump]. Nothing here fabricates traffic;
 * if no packets flow, the counters stay at zero.
 */
class RequestrVpnService : VpnService() {

    // internal (not private): lets the lifecycle-ordering regression test in
    // RequestrVpnServiceTest read/seed this field directly rather than via reflection, without
    // widening the real public API surface (still module-internal).
    internal var tunInterface: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var startJob: Job? = null
    private var pumpJob: Job? = null
    private var filterObserverJob: Job? = null
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    // Test seam (SHOULD-FIX, code review): records real runtime invocation order from inside
    // [stopCapture]'s launched block, instead of reading this file's source text off disk (the
    // prior version of this regression test's brittleness) or trying to observe the ordering via
    // a concurrent StateFlow collector on `Dispatchers.Unconfined` -- empirically that collector
    // approach turned out to be vacuous, because Unconfined's thread-local event loop defers a
    // *nested* resume (the collector waking up from inside markStopped()'s StateFlow emission)
    // until the entire outer launched block finishes, regardless of statement order inside it.
    internal var stopCaptureStepRecorder: (String) -> Unit = {}
    /**
     * S1 task 11 test seam (same shape as [stopCaptureStepRecorder]): records, in real runtime
     * order, `"startForeground"` (the synchronous call at the top of [onStartCommand]) and
     * `"settingsRead"` (the first suspending `SettingsDataStore` read inside [startCapture]) so
     * a test can prove the foreground promotion never waits on DataStore.
     */
    internal var lifecycleStepRecorder: (String) -> Unit = {}
    private var sessionId: Long? = null
    private var healthJob: Job? = null
    private var foregroundStarted = false

    /**
     * S2 task 15: the process-wide single-writer queue. Replaces the S1 task 11 private
     * `Channel<suspend () -> Unit>` mini-queue this service used to own: same never-block-a-relay
     * contract, but shared with every other persist site, with drop/failure counters surfaced via
     * [com.example.data.persistence.PersistenceHealth] instead of a service-local counter.
     */
    private val persistence: com.example.data.persistence.PersistenceQueue by lazy {
        com.example.data.persistence.PersistenceQueue.get(applicationContext)
    }

    /** S1 task 11: Wi-Fi/LTE handover coordinator; null while no capture is running. */
    @Volatile private var networkHandoff: VpnNetworkHandoff? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    /** Fingerprint of the network the current tun was established against (health/diagnostics). */
    @Volatile private var establishedLink: VpnNetworkHandoff.LinkFingerprint? = null
    internal var socketProtectorRegistration: SocketProtector.Registration? = null

    /**
     * Gap #1 Approach A: true once the native tun2proxy bridge has genuinely taken over the
     * tun fd for the current session (feeding [activeMitmEngine] via literal `CONNECT`), i.e.
     * [Tun2ProxyBridge.start] returned [Tun2ProxyBridge.StartResult.Started]. False for the
     * entire session whenever the native path was unavailable/failed to start -- in that case
     * capture falls back to the pre-existing hand-rolled [PacketPump]/[TunRelay] pipeline for
     * that whole session; there is never a silent mid-session switch either way.
     */
    private var usingNativeBridge = false

    /** S1 task 9: per-capture UDP/QUIC policy router (null when the `quicTeePolicy` flag is off). */
    @Volatile private var udpPolicyRouter: com.example.interception.quic.UdpPolicyRouter? = null

    /** S1 task 10: process-wide UID attributor shared with the MITM engine. */
    private val uidAttributor by lazy { com.example.interception.uid.UidAttributor.shared(applicationContext) }

    /**
     * S1 task 12: tee -> PCAPng consumer, registered on the bridge only while a packet export is
     * running in native-bridge mode (the legacy pump feeds [PacketCaptureEngine] directly).
     */
    @Volatile private var teeRecorder: com.example.interception.packet.PacketTeeRecorder? = null

    /**
     * Defect D/E (S0 gaps audit): set true when [Tun2ProxyBridge.start] returned
     * [Tun2ProxyBridge.StartResult.Indeterminate] -- the native side could not confirm it had
     * quiesced after a start-timeout, so it may still be holding/using the tun fd, the MITM
     * engine, and every native callback registration. While this is true, [cleanup] must not
     * touch [tunInterface], [activeMitmEngine], or [socketProtectorRegistration] at all; a
     * later, unrelated capture attempt must not silently reuse or close a fd the native side
     * might still be reading/writing.
     */
    private var tunFdUnsafeToTouch = false

    /**
     * This service's own `MitmProxyEngine` instance (separate from [PreflightController]'s
     * singleton, which serves the PROXY/ROOT/SHIZUKU tiers). Bound to an ephemeral port
     * (`port = 0`) so it never collides with the user's configured proxy port. Only
     * constructed/started when a native-bridge start is actually attempted; stopped from
     * [cleanup] (success path) or immediately inline (failure path, since it would otherwise
     * sit idle for the rest of the session with nothing routing traffic to it).
     */
    private var activeMitmEngine: MitmProxyEngine? = null

    /** Set once the pump is constructed, so [cleanup] can stop [PacketPump.relay] explicitly
     * (in addition to [pumpJob] cancellation) and observe [com.example.interception.tun.TunRelay.stats].
     *
     * Task 14: [TunRelay.stats] / [com.example.interception.tun.IcmpRelay.unrelayableCount] /
     * [PacketPump.quicLiveState] all live on *this instance*, not on a static singleton like
     * [InterceptionStateRepository] -- there is no existing pattern in this codebase for a
     * running-service-instance-scoped StateFlow reaching Compose UI. Mirroring [activePump]
     * into the companion object's [activePumpFlow] on every set/clear is the bridge: the UI
     * binds to [activePumpFlow] (a `StateFlow<PacketPump?>`) via `collectAsStateWithLifecycle`
     * and then reads `.relay.stats` / `.icmpRelay` / `.quicLiveState` off whatever pump (or
     * null, when no capture is running) it currently holds. Safe because [RequestrVpnService]
     * has no `android:process` attribute (confirmed via the manifest), so this always runs in
     * the same process as the UI -- no cross-process IPC needed for what is otherwise a plain
     * in-memory object reference. */
    private var activePump: PacketPump? = null
        set(value) {
            field = value
            activePumpFlow.value = value
        }

    /**
     * Delegates to the real `VpnService.protect(...)` overloads so relay upstream sockets
     * (task 4) — and, in task 6, the ICMP ping socket via [ParcelFileDescriptor.dup] — route
     * outside the tun instead of looping back into it. Must run before connect/send on every
     * relay socket; [com.example.interception.tun.TunRelay] guarantees that ordering.
     */
    private val protectHook = object : ProtectHook, SocketProtector.Backing {
        override fun protectSocket(socket: java.net.Socket): Boolean =
            runCatching { protect(socket) }.getOrDefault(false)

        override fun protectDatagramSocket(socket: java.net.DatagramSocket): Boolean =
            runCatching { protect(socket) }.getOrDefault(false)

        override fun protectFd(fd: Int): Boolean =
            runCatching { protect(fd) }.getOrDefault(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture("Stopped by user")
                return START_NOT_STICKY
            }
            else -> {
                // S1 task 11: promote to foreground synchronously, *before* the first suspending
                // DataStore read in startCapture(). Since the companion start() uses
                // startForegroundService() on API 26+, the system gives us a short window to call
                // startForeground(); a slow DataStore read used to be able to miss it
                // (ForegroundServiceDidNotStartInTimeException) and kill the capture at start.
                if (tunInterface == null) {
                    startForegroundNotification(port = null)
                    lifecycleStepRecorder("startForeground")
                }
                startCapture()
            }
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (tunInterface != null) {
            Log.w(TAG, "startCapture ignored; already running")
            return
        }

        startJob = scope.launch {
            try {
                // Re-entry from the filter observer / network handoff restarts: the foreground
                // notification is already showing, but a fresh start from onStartCommand posted
                // the minimal one above; either way we are in the foreground before this read.
                if (!foregroundStarted) startForegroundNotification(port = null)
                lifecycleStepRecorder("settingsRead")
                val prefs = SettingsDataStore.get(applicationContext)
                val port = prefs.proxyPort.first()
                val filterMode = prefs.appFilterMode.first()
                val selected = prefs.selectedPackages.first()
                val quicObserveOverride = prefs.quicObserveOverride.first()
                val quicBypassPackages = prefs.quicBypassPackages.first()

                updateNotification(port, healthLine(null))
                acquireWakeLock()

                // S1 task 11: DNS. With the native bridge available every query terminates in
                // tun2proxy's virtual resolver (hostnames for SessionStart, DNS rows, SNI-less
                // attribution), so the tun advertises one resolver *inside* the virtual pool.
                // Only the legacy relay path (no native library) still needs real resolvers;
                // there the active network's own come first so captive/enterprise networks
                // keep resolving, with the public pair as the last resort.
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork: Network? = runCatching { cm?.activeNetwork }.getOrNull()
                val activeLink: LinkProperties? = runCatching { activeNetwork?.let { cm?.getLinkProperties(it) } }.getOrNull()
                val virtualDns = NativeTun2Proxy.isAvailable()
                val dnsServers = VpnDnsPlan.resolvers(
                    virtualDns = virtualDns,
                    systemResolvers = activeLink?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
                )

                val builder = Builder()
                    .setSession("requestr")
                    .setMtu(MTU)
                    .addAddress(TUN_ADDRESS, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    // S1 task 11: blocking reads. The pump and the native bridge both read the tun
                    // from dedicated threads; a non-blocking fd made those loops spin on EAGAIN.
                    .setBlocking(true)
                dnsServers.forEach { server ->
                    runCatching { builder.addDnsServer(server) }
                        .onFailure { Log.w(TAG, "Cannot advertise DNS server $server: ${it.message}") }
                }

                // Task 9: IPv6 was previously entirely dark -- no v6 address, no v6 route, so
                // any IPv6-capable network's traffic never even entered the tun. Guarded with
                // runCatching so a device/ROM without v6 support (or one that rejects a second
                // address family on the same interface) degrades to v4-only rather than making
                // establish() fail outright below -- counted via the log line, not hidden.
                val ipv6Added = runCatching {
                    builder.addAddress(TUN_ADDRESS_V6, TUN_PREFIX_V6)
                    builder.addRoute("::", 0)
                }.isSuccess
                if (!ipv6Added) {
                    Log.w(TAG, "IPv6 route/address could not be added to the tun builder; continuing v4-only")
                }

                // Real per-app filtering via UID-backed allow-list. VpnService.Builder permits
                // an allowed set OR a disallowed set, never both -- mixing them throws
                // UnsupportedOperationException at establish() time, which is exactly why
                // SELECTED_APPS mode never worked before this fix (see resolveFilterPlan doc).
                val plan = resolveFilterPlan(filterMode, selected, packageName)
                // S2 task 16: resolve the shared persistence queue before any engine (PacketPump,
                // relay pool, gateway) is built so the `bodySpill` flag is applied -- BodyStore
                // attached (with its startup orphan sweep queued) and the decoder cap mirrored --
                // ahead of the first insert of this session.
                persistence
                // S1 task 10: the allow-list is the only legitimate input to the attributor's
                // single-app inference step; everything else is attributed from the kernel.
                com.example.interception.uid.UidAttributor.captureSelectionSnapshot =
                    (plan as? FilterPlan.Allowlist)?.packages ?: emptySet()
                // S1 task 9 (D6): capture-scoped UDP/QUIC policy. The bridge's own udp_policy stays
                // PASS_ALL so every non-DNS UDP session reaches the gateway's UDP ASSOCIATE relay,
                // where the decision is made per datagram from the owning UID. With the
                // `quicTeePolicy` flag off the gateway refuses UDP ASSOCIATE exactly as before.
                val captureScope = com.example.interception.quic.UdpPolicyRouter.CaptureScope(
                    allApps = plan !is FilterPlan.Allowlist,
                    selectedPackages = (plan as? FilterPlan.Allowlist)?.packages ?: emptySet(),
                    bypassPackages = quicBypassPackages,
                )
                udpPolicyRouter = if (com.example.data.persistence.InterceptorFlags.get(applicationContext).quicTeePolicy) {
                    com.example.interception.quic.UdpPolicyRouter(
                        attributor = uidAttributor,
                        scope = { captureScope },
                        observeOverride = { quicObserveOverride },
                        packagesForUid = { uid -> runCatching { packageManager.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty() },
                        tee = { payload, offset, length, source, destination, _, decision ->
                            val pump = activePump
                            val quic = destination.port == com.example.interception.quic.UdpPolicyRouter.QUIC_PORT ||
                                source.port == com.example.interception.quic.UdpPolicyRouter.QUIC_PORT
                            if (pump != null && quic) {
                                pump.ingestQuicDatagram(
                                    payload.copyOfRange(offset, offset + length),
                                    source.address.hostAddress ?: source.hostString, source.port,
                                    destination.address.hostAddress ?: destination.hostString, destination.port,
                                    isIpv6 = source.address is java.net.Inet6Address,
                                    blockedByPolicy = decision.action == com.example.interception.quic.UdpPolicyRouter.Action.TEE_AND_DROP,
                                )
                            }
                        },
                    )
                } else {
                    null
                }
                when (plan) {
                    is FilterPlan.Allowlist -> {
                        plan.packages.forEach { pkg ->
                            runCatching { builder.addAllowedApplication(pkg) }
                                .onFailure { Log.w(TAG, "Cannot filter missing package $pkg") }
                        }
                    }
                    is FilterPlan.Blocklist -> {
                        runCatching { builder.addDisallowedApplication(packageName) }
                            .onFailure { Log.w(TAG, "Cannot exclude self package $packageName") }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }
                // S1 task 11: pin the tun to the network it will actually egress over so the
                // system routes our protected sockets correctly and hands over cleanly.
                if (activeNetwork != null) {
                    runCatching { builder.setUnderlyingNetworks(arrayOf(activeNetwork)) }
                        .onFailure { Log.w(TAG, "setUnderlyingNetworks on builder failed: ${it.message}") }
                }

                val tun = builder.establish()
                if (tun == null) {
                    fail("Could not establish VPN interface. VPN consent may have been revoked.")
                    return@launch
                }
                tunInterface = tun
                // Baseline for the handoff coordinator: the *same* fingerprint function the
                // callbacks use, so a network whose properties never change can never trigger a
                // re-establish loop. Falls back to what the tun itself advertises when the
                // active link is unknown.
                establishedLink = activeLink?.let { fingerprintOf(it, virtualDns) }
                    ?: VpnNetworkHandoff.LinkFingerprint(dnsServers = dnsServers.sorted(), hasIpv4 = true, hasIpv6 = ipv6Added)
                registerNetworkCallback(cm, activeNetwork, virtualDns)

                // S0 Task 4: install the process-wide socket protector so every egress
                // path (LazyUpstreamConnection, RemoteCertificateMirror, HttpProxyShared,
                // ConnectReplay, and the native-bridge callback) protects before connect.
                socketProtectorRegistration = SocketProtector.install(protectHook)
                SocketProtector.resetMetrics()

                // Persist a real session row; counters are updated from actual traffic.
                val db = RequestrDatabase.get(applicationContext)
                val newId = db.captureSessionDao().insert(
                    CaptureSessionEntity(
                        tier = CaptureTier.VPN,
                        port = port,
                        filteredPackages = if (filterMode == AppFilterMode.SELECTED_APPS) {
                            selected.toList()
                        } else {
                            emptyList()
                        },
                    )
                )
                sessionId = newId

                InterceptionStateRepository.markActive(
                    sessionId = newId,
                    tier = CaptureMethod.VPN_TUN.engineTier(),
                    port = port,
                    method = CaptureMethod.VPN_TUN,
                    lifecycleOwner = CaptureMethod.VPN_TUN.engineLifecycleOwner(),
                )

                // Gap #1 Approach A / S1 task 6: hand the tun fd to the native tun2proxy
                // bridge, which dials this service's own MitmProxyEngine -- now behind a
                // LocalGateway -- over authenticated SOCKS5 with the `+info` username suffix,
                // so every flow arrives with its real source tuple (`|tcp|src_ip|src_port`)
                // for UID attribution instead of a bare `CONNECT host:port`. VPN-captured TCP
                // flows feed the same MITM decrypt pipeline the PROXY/ROOT/SHIZUKU tiers use,
                // instead of the hand-rolled TunRelay selector loop. Never pretends success: on
                // any unavailable/failed native attempt this falls back to the pre-existing
                // TunRelay-based pipeline for the whole session (see [usingNativeBridge]).
                // Credentials are fresh per session so no other local app can drive the
                // gateway's SOCKS side.
                val gatewayCredentials = com.example.interception.gateway.local.Socks5Credentials.random()
                // S1 task 7: the bounded relay pool (flag `relayPoolBounded`) is process-wide;
                // the flag is applied on first creation, which is here for the VPN tier.
                val relayPool = com.example.interception.relay.RelayPool.shared(
                    bounded = com.example.data.persistence.InterceptorFlags.get(applicationContext).relayPoolBounded,
                )
                val mitmEngine = MitmProxyEngine(
                    // S2 task 15: never block a relay thread on Room; see [persistence].
                    // S2 task 18: the measured phases ride along in the same queued op.
                    // S3 task 20: the queue is the CaptureSink -- parent rows, wire-ordered
                    // headers AND the protocol children (WebSocket/SSE/gRPC/h2 frames) all go
                    // through it, so the flat callback seam is no longer wired in production.
                    sink = persistence,
                    relayPool = relayPool,
                    uidAttributor = uidAttributor,
                    udpAssociationHandler = udpPolicyRouter,
                )
                val mitmPort = mitmEngine.start(applicationContext, 0, socks5Credentials = gatewayCredentials)
                // MitmProxyEngine.start() unconditionally calls
                // InterceptionStateRepository.markActive(tier = PROXY, method = MITM_PROXY,
                // ...) itself before returning -- correct that transient global-state stomp
                // back to this session's real tier/session id immediately below, regardless
                // of which branch is taken next.
                // T1 / S1 task 12: packet export -- started BEFORE the bridge so the very first
                // teed frame already has a writer, and stopped alongside the tun so its lifetime
                // matches one capture session exactly. Both packet sources (PacketPump.writeToPcap
                // on the legacy path, PacketTeeRecorder on the native-bridge path) are no-ops
                // while PacketCaptureEngine.isActive() is false, so this preference is the only gate.
                val pcapEnabled = prefs.pcapEnabled.first()
                val pcapngFlag = com.example.data.persistence.InterceptorFlags.get(applicationContext).pcapng
                val pcapFormat = when {
                    !pcapngFlag -> com.example.interception.packet.PacketCaptureEngine.Format.PCAP
                    prefs.pcapFormat.first() == SettingsDataStore.PCAP_FORMAT_PCAP ->
                        com.example.interception.packet.PacketCaptureEngine.Format.PCAP
                    else -> com.example.interception.packet.PacketCaptureEngine.Format.PCAPNG
                }
                if (pcapEnabled) {
                    val started = com.example.interception.packet.PacketCaptureEngine.start(applicationContext, format = pcapFormat)
                    Log.i(TAG, if (started) "${pcapFormat.name} export enabled for session=$newId" else "${pcapFormat.name} export failed to start")
                }
                // The tee is what makes packets exist at all in native-bridge mode; with the
                // `pcapng` flag off the pre-S1 behaviour (rollout flag only) is restored.
                val teeForExport = pcapngFlag && pcapEnabled && com.example.interception.packet.PacketCaptureEngine.isActive()

                val bridgeResult = Tun2ProxyBridge.start(
                    Tun2ProxyConfig.forLocalGateway(
                        gatewayPort = mitmPort,
                        credentials = gatewayCredentials,
                        sessionTag = newId.toString(),
                        teeEnabled = com.example.data.persistence.TeeRolloutFlag.get(applicationContext).isEnabled || teeForExport,
                    ),
                    tun.fd,
                )
                when (bridgeResult) {
                    is Tun2ProxyBridge.StartResult.Started -> {
                        usingNativeBridge = true
                        // S1 task 10: the attributor tracks src->dst per bridge session so the
                        // gateway's +info source tuple can be attributed via the real 4-tuple.
                        Tun2ProxyBridge.addEventListener(uidAttributor)
                        if (teeForExport) {
                            // S1 task 12: registered AFTER the attributor so a frame's session_start
                            // is tracked before the frame itself is annotated.
                            val recorder = com.example.interception.packet.PacketTeeRecorder(annotator = ::annotateTeeFlow)
                            Tun2ProxyBridge.addEventListener(recorder)
                            teeRecorder = recorder
                        }
                        activeMitmEngine = mitmEngine
                        InterceptionStateRepository.markActive(
                            sessionId = newId,
                            // Deliberately NOT CaptureMethod.MITM_PROXY.engineTier()/
                            // .engineLifecycleOwner() -- this is the one genuine, documented
                            // exception where `method` (for row attribution only) and the real
                            // tier/lifecycleOwner (this service, running at VPN tier) legitimately
                            // differ. See CaptureMethod.engineTier()'s KDoc and
                            // CaptureLifecycleOwner's KDoc.
                            tier = ActiveTier.VPN,
                            port = port,
                            method = CaptureMethod.MITM_PROXY,
                            // Row attribution only -- this service (not PreflightController's
                            // singleton MitmProxyEngine) still owns the whole session's
                            // lifecycle. See CaptureLifecycleOwner's KDoc.
                            lifecycleOwner = CaptureLifecycleOwner.VPN_SERVICE,
                        )
                        Log.i(TAG, "Native tun2proxy bridge started; VPN traffic now routed via SOCKS5 +info into LocalGateway/MitmProxyEngine on port $mitmPort")
                    }
                    is Tun2ProxyBridge.StartResult.Unavailable, is Tun2ProxyBridge.StartResult.Failed -> {
                        usingNativeBridge = false
                        runCatching { mitmEngine.stop() }
                        val reason = when (bridgeResult) {
                            is Tun2ProxyBridge.StartResult.Unavailable -> bridgeResult.reason
                            is Tun2ProxyBridge.StartResult.Failed -> "code=${bridgeResult.code} ${bridgeResult.message}"
                            else -> "unknown"
                        }
                        Log.w(TAG, "Native tun2proxy bridge unavailable/failed ($reason); falling back to legacy VPN relay path")
                        // Restore the VPN/VPN_TUN state the un-native-bridged path already
                        // established above (markActive resets watcherStatus to IDLE, so
                        // setWatcherStatus(DEGRADED) below must run strictly after this).
                        InterceptionStateRepository.markActive(
                            sessionId = newId,
                            tier = CaptureMethod.VPN_TUN.engineTier(),
                            port = port,
                            method = CaptureMethod.VPN_TUN,
                            lifecycleOwner = CaptureMethod.VPN_TUN.engineLifecycleOwner(),
                        )
                        InterceptionStateRepository.setWatcherStatus(WatcherStatus.DEGRADED)
                    }
                    is Tun2ProxyBridge.StartResult.Indeterminate -> {
                        // Defect D/E: the native side could not confirm shutdown after a start
                        // timeout. It may still be reading/writing the tun fd right now, so we
                        // must not hand that same fd to the legacy TunRelay/PacketPump pipeline
                        // (double-consuming a fd is worse than failing the attempt), and we must
                        // not close it either. Stop only what is unambiguously safe: this
                        // service's own local-port MitmProxyEngine instance never touched the
                        // tun fd directly (native tun2proxy speaks CONNECT to it over loopback).
                        usingNativeBridge = false
                        runCatching { mitmEngine.stop() }
                        tunFdUnsafeToTouch = true
                        Log.e(TAG, "Native tun2proxy bridge indeterminate after start timeout: ${bridgeResult.message}")
                        fail(
                            "Native capture bridge did not confirm shutdown after a start " +
                                "timeout; capture stopped for safety without touching the tun " +
                                "interface. Try again."
                        )
                        return@launch
                    }
                }

                val printUndecoded = prefs.printUndecoded.first()
                val dumpToFile = prefs.dumpToFile.first()
                // Task 9: read via SettingsDataStore (safe from any process/component) rather
                // than SettingsRepository.settings.value, which silently returns hardcoded
                // `true` defaults when the system starts this service without MainActivity
                // ever having run SettingsRepository.init().
                val captureEnabled = prefs.trafficCapture.first()
                val requestLogging = prefs.requestLogging.first()
                val responseLogging = prefs.responseLogging.first()
                // Complaint #5 audit fix: Enhanced Mode now feeds CaptureEngine's real
                // ProtocolDetector analysis pass (see CaptureEngine.enhancedDiagnostics) instead
                // of gating nothing but a debug Log.i() line.
                val enhancedMode = prefs.enhancedMode.first()

                pumpJob = scope.launch {
                    val pump = PacketPump(
                        context = applicationContext,
                        input = FileInputStream(tun.fileDescriptor),
                        output = FileOutputStream(tun.fileDescriptor),
                        sessionId = newId,
                        db = db,
                        // D2: previously DnsParser.parse() ran on every UDP:53 packet and the
                        // result was only Log.d'd -- dnsCallback was declared on PacketPump but
                        // never set by any caller, so DNS was parsed and discarded. Wiring it
                        // here is what makes DNS a real first hop for ConnectionLineage instead
                        // of a documented gap: every query/response is persisted to
                        // dns_queries and mirrored into CorrelationEngine as a DnsEvent.
                        dnsCallback = { dns, src, sPort, dst, dPort -> onDnsObserved(newId, dns, src, sPort, dst, dPort) },
                        printUndecoded = printUndecoded,
                        dumpToFile = dumpToFile,
                        captureEnabled = captureEnabled,
                        enhancedMode = enhancedMode,
                        requestLogging = requestLogging,
                        responseLogging = responseLogging,
                        protectHook = protectHook,
                        nativeBridgeMode = usingNativeBridge,
                        nativeTrafficStatsPoll = if (usingNativeBridge) {
                            { Tun2ProxyBridge.trafficStats() }
                        } else {
                            null
                        },
                    )
                    activePump = pump
                    pump.run(this)
                }

                Log.i(TAG, "Capture started on tun, session=$newId port=$port dns=$dnsServers virtualDns=$virtualDns")
                startHealthUpdates(port)

                // Hot-reload app filter. VpnService.Builder doesn't support dynamic app list
                // changes after establish(), so we tear down and re-establish the tun when the
                // user changes the filter.
                filterObserverJob?.cancel()
                filterObserverJob = scope.launch {
                    val prefs2 = SettingsDataStore.get(applicationContext)
                    combine(
                        prefs2.selectedPackages,
                        prefs2.appFilterMode
                    ) { pkgs, mode -> Pair(pkgs, mode) }
                        .drop(1) // skip initial emission = current state
                        .collect { (_, _) ->
                            Log.i(TAG, "App filter changed, restarting VPN tunnel")
                            stopCapture("Filter changed — restarting")
                            delay(500)
                            startCapture()
                        }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                fail("Capture failed to start: ${t.message ?: t::class.java.simpleName}")
            } finally {
                startJob = null
            }
        }
    }

    /**
     * D2: callback set on [PacketPump.dnsCallback]. Persists the parsed DNS query/response as a
     * [com.example.data.local.DnsQueryEntity] row (real Room persistence, replacing the previous
     * log-and-discard behaviour) and mirrors it into [CorrelationEngine] as a [DnsEvent] so it can
     * eventually be correlated with runtime/HTTP events on the same host.
     *
     * [connectionId] is left null: a DNS query/response happens before any TCP connection exists
     * for that hostname, so there is no [com.example.interception.TcpStream] key yet to attach it
     * to. [com.example.correlation.graph.ConnectionLineage] (D2) links a DNS *response* row to a
     * later TCP connection by matching [DnsQueryEntity.resolvedIps] against the TCP destination
     * IP instead of by connectionId.
     *
     * Called from [PacketPump]'s tun-read loop (a plain callback, not a suspend context), so the
     * Room write goes through the bounded [persistence] queue (S1 task 11 / S2 task 15) instead
     * of blocking the read loop.
     */
    private fun onDnsObserved(
        sessionId: Long,
        dns: com.example.interception.dns.DnsParser.DnsQuery,
        src: String,
        sPort: Int,
        dst: String,
        dPort: Int,
    ) {
        if (dns.hostname.isEmpty()) return
        // S2 task 15: queued instead of runBlocking on the tun-read thread; see [persistence].
        persistence.insertDns(
            com.example.data.local.DnsQueryEntity(
                sessionId = sessionId,
                transactionId = dns.transactionId,
                hostname = dns.hostname,
                queryType = dns.qtypeName,
                isResponse = dns.isResponse,
                resolvedIps = dns.resolvedIps,
                resolver = "UDP:53",
            )
        )

        com.example.correlation.CorrelationEngine.ingest(
            com.example.correlation.DnsEvent(
                eventId = "dns-${dns.transactionId}-${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                source = com.example.correlation.ObservationEvent.ObserverSource.DNS,
                connectionId = null,
                hostname = dns.hostname,
                recordType = dns.qtypeName,
                resolvedIps = dns.resolvedIps,
                resolver = "UDP:53",
            )
        )
    }

    private fun fail(message: String) {
        // Lifecycle-race fix (matches stopCapture below): cleanup() must run before the state
        // transition, not after, so a UI observer reacting to ERROR (e.g. offering "retry") never
        // sees a state that claims capture is over while tunInterface is still genuinely non-null.
        Log.e(TAG, message)
        cleanup()
        InterceptionStateRepository.markError(message)
        stopSelf()
    }

    /**
     * Lifecycle-race fix: this used to call [InterceptionStateRepository.markStopped] *before*
     * [cleanup], so a collector reacting to the state going idle (e.g. re-enabling the Start
     * button) could race a still-non-null [tunInterface] -- a fast stop-then-start double-tap
     * could hit [startCapture]'s `tunInterface != null` guard and silently no-op the restart,
     * with nothing capturing and no error surfaced. [cleanup] is a plain synchronous function
     * with no suspension point before the [markStopped] call below, so by the time any collector
     * observes [InterceptionStateRepository.state] converge to `CONFIGURED_IDLE`, [tunInterface]
     * is *already* null -- this is what lets [PreflightController] treat this service as owning
     * its own stop convergence (see [PreflightController.stopCapture]'s doc) rather than routing
     * VPN_TUN/ROOT_FRIDA through [InterceptionStateRepository.runStop]'s generic guarantee, which
     * would otherwise fire the same premature transition one layer up instead.
     */
    internal fun stopCapture(reason: String) {
        Log.i(TAG, "Stopping capture: $reason")
        scope.launch {
            sessionId?.let { id ->
                runCatching {
                    RequestrDatabase.get(applicationContext).captureSessionDao().markEnded(id)
                }
            }
            cleanup()
            stopCaptureStepRecorder("cleanup")
            InterceptionStateRepository.markStopped()
            stopCaptureStepRecorder("markStopped")
            stopSelf()
        }
    }

    private fun cleanup() {
        startJob?.cancel()
        startJob = null
        filterObserverJob?.cancel()
        filterObserverJob = null
        // S1 task 11: stop reacting to network handovers before the tun goes away, so a
        // callback racing this teardown cannot schedule a re-establish of a closed tun.
        unregisterNetworkCallback()
        healthJob?.cancel()
        healthJob = null
        pumpJob?.cancel()
        pumpJob = null
        // Task 4: stop the relay's selector loop and close every open upstream socket
        // explicitly, alongside cancelling the pump's coroutine (which also calls
        // TunRelay.stop() in its own `finally`, but this covers the case where the tun read
        // loop is already blocked/stuck and never reaches that finally block).
        runCatching { activePump?.relay?.stop() }
        activePump = null

        if (tunFdUnsafeToTouch) {
            // Defect D/E: an earlier start attempt left the native bridge's shutdown state
            // indeterminate -- the tun fd, MITM engine, and socket protector registration may
            // still be in use by the native side right now. Never touch them here. Everything
            // below this point is independent of the fd and safe to tear down unconditionally.
            Log.w(TAG, "cleanup(): tun fd/MITM engine/socket protector left untouched (indeterminate native shutdown from a prior start attempt)")
            runCatching { com.example.interception.packet.PacketCaptureEngine.stop() }
            runCatching { TlsKeyLogRegistry.clear() }
            releaseWakeLock()
            stopForegroundCompat()
            return
        }

        // Gap #1 Approach A: signal the native bridge to stop and give it a bounded window
        // to actually observe the cancellation before the tun ParcelFileDescriptor is closed
        // out from under it -- stop() itself is cooperative/async (fire-and-forget), not a
        // blocking join, so this ordering is the mitigation for "protect native sockets/fd".
        if (usingNativeBridge) {
            teeRecorder?.let { recorder ->
                runCatching { Tun2ProxyBridge.removeEventListener(recorder) }
                Log.i(TAG, "PCAPng tee: ${recorder.frameCount} frames, ${recorder.annotatedCount} attributed to a bridge session, ${recorder.unparsedCount} unparsed")
            }
            teeRecorder = null
            runCatching { Tun2ProxyBridge.removeEventListener(uidAttributor) }
            runCatching { Tun2ProxyBridge.stop() }
            var awaitRc = Tun2ProxyBridge.awaitStoppedOrTimeout()
            var attempt = 1
            // Defect D/E: finite escalation instead of a single window -- retry the
            // stop-and-await cycle up to STOP_MAX_ATTEMPTS times before giving up.
            while (awaitRc == NativeTun2Proxy.ERR_TIMEOUT && attempt < STOP_MAX_ATTEMPTS) {
                attempt++
                Log.w(
                    TAG,
                    "Native tun2proxy bridge stop attempt $attempt/$STOP_MAX_ATTEMPTS: " +
                        "still not confirmed stopped after ${Tun2ProxyBridge.STOP_QUIESCENCE_TIMEOUT_MS}ms; retrying"
                )
                runCatching { Tun2ProxyBridge.stop() }
                awaitRc = Tun2ProxyBridge.awaitStoppedOrTimeout()
            }

            val nativeBridgeConfirmedStopped =
                awaitRc == NativeTun2Proxy.OK || awaitRc == NativeTun2Proxy.ERR_NOT_RUNNING

            if (!nativeBridgeConfirmedStopped) {
                // Never confirmed after STOP_MAX_ATTEMPTS attempts. Do NOT close the tun fd,
                // stop the MITM engine, or clear the socket protector out from under a native
                // thread that may still be using them. Keep usingNativeBridge=true so a later
                // stopCapture() call retries convergence instead of silently re-arming a second
                // bridge on top of the same fd. Everything below is independent of the fd and
                // still torn down.
                Log.e(
                    TAG,
                    "Native tun2proxy bridge never confirmed shutdown after $STOP_MAX_ATTEMPTS " +
                        "attempts; leaving tun fd, MITM engine, and socket protector registered"
                )
                runCatching { com.example.interception.packet.PacketCaptureEngine.stop() }
                runCatching { TlsKeyLogRegistry.clear() }
                releaseWakeLock()
                stopForegroundCompat()
                return
            }
            usingNativeBridge = false
        }

        runCatching { activeMitmEngine?.stop() }
        activeMitmEngine = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        // All relays have stopped, but clear before slow persistence/backup teardown below so a
        // completed onRevoke/failed start cannot leave process-wide VPN state installed.
        socketProtectorRegistration?.let(SocketProtector::clear)
        socketProtectorRegistration = null
        // T1: stop PCAP export with the rest of this session's lifecycle -- flushes and closes
        // the .pcap file. No-op (guarded by its own `active` flag) if it was never started.
        runCatching { com.example.interception.packet.PacketCaptureEngine.stop() }
        // Task 5: secrets are in-memory only and must not outlive a capture session.
        runCatching { TlsKeyLogRegistry.clear() }
        // S2 task 15: queued Room writes drain on the process-wide persistence actor, which
        // outlives this service on purpose -- nothing to stop here.
        releaseWakeLock()
        stopForegroundCompat()
    }

    /**
     * S1 task 12: resolves a teed frame's client-side tuple to its bridge session and UID via
     * [uidAttributor] (bridge `+info` -> connection owner -> `/proc/net` -> selection inference).
     * Null when the bridge has not reported a session for the tuple -- the frame is still
     * written, just without a flow comment; [PacketTeeRecorder] retries unknown tuples.
     */
    private fun annotateTeeFlow(
        client: java.net.InetSocketAddress,
        @Suppress("UNUSED_PARAMETER") isTcp: Boolean,
    ): com.example.interception.packet.PcapNgWriter.FlowAnnotation? {
        val (session, attribution) = uidAttributor.attributeTracked(client) ?: return null
        val uid = attribution.uidOrNull
        val pkg = uid?.let { runCatching { packageManager.getPackagesForUid(it)?.firstOrNull() }.getOrNull() }
        return com.example.interception.packet.PcapNgWriter.FlowAnnotation(
            flowId = session.sessionId,
            uid = uid,
            packageName = pkg,
            attribution = when (attribution) {
                is com.example.interception.uid.UidAttributor.Attribution.Resolved -> attribution.source.name
                is com.example.interception.uid.UidAttributor.Attribution.Unattributed -> "UNATTRIBUTED(${attribution.reason})"
            },
            destination = session.destination,
            isTcp = session.isTcp,
        )
    }

    // ---- S1 task 11: foreground/notification, network handoff ------------------------------

    /** Health counters shown in the notification (S1 task 11), refreshed every [HEALTH_REFRESH_MS]. */
    internal fun healthLine(relay: com.example.interception.relay.RelayMetrics.Snapshot?): String {
        val parts = mutableListOf<String>()
        val snap = relay ?: runCatching { com.example.interception.relay.RelayMetrics.global.snapshot() }.getOrNull()
        if (snap != null) {
            parts += "${snap.active} active"
            if (snap.queued > 0) parts += "${snap.queued} queued"
            parts += "up ${formatBytes(snap.bytesToUpstream)} / down ${formatBytes(snap.bytesToClient)}"
            if (snap.rejected > 0 || snap.evicted > 0) parts += "${snap.rejected + snap.evicted} shed"
        }
        val persist = com.example.data.persistence.PersistenceHealth.snapshot()
        val lost = persist.droppedWrites + persist.failedWrites
        if (lost > 0) parts += "$lost writes lost"
        if (persist.capturePaused != null) parts += "capture paused: quota"
        if (networkHandoff?.isDegraded == true) parts += "no network -- waiting"
        return parts.joinToString(" \u00b7 ").ifEmpty { "VPN active" }
    }

    private fun startHealthUpdates(port: Int) {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (isActive) {
                delay(HEALTH_REFRESH_MS)
                runCatching { updateNotification(port, healthLine(null)) }
            }
        }
    }

    private fun registerNetworkCallback(cm: ConnectivityManager?, activeNetwork: Network?, virtualDns: Boolean) {
        if (cm == null) return
        unregisterNetworkCallback()
        val handoff = VpnNetworkHandoff(
            scope = scope,
            onUnderlyingNetwork = { network ->
                if (network is Network) {
                    runCatching { setUnderlyingNetworks(arrayOf(network)) }
                        .onFailure { Log.w(TAG, "setUnderlyingNetworks failed: ${it.message}") }
                }
            },
            onReestablish = { reason ->
                Log.i(TAG, "$reason; re-establishing VPN tunnel")
                stopCapture("$reason -- restarting")
                delay(500)
                startCapture()
            },
            onDegraded = { degraded ->
                if (degraded) {
                    InterceptionStateRepository.setWatcherStatus(WatcherStatus.DEGRADED)
                } else {
                    InterceptionStateRepository.setWatcherStatus(WatcherStatus.HEALTHY)
                }
            },
        )
        handoff.onEstablished(establishedLink ?: return, activeNetwork)
        networkHandoff = handoff
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handoff.onNetworkChanged(network, fingerprintOf(cm, network, virtualDns))
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                handoff.onNetworkChanged(network, fingerprintOf(linkProperties, virtualDns))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handoff.onNetworkChanged(network, fingerprintOf(cm, network, virtualDns))
            }

            override fun onLost(network: Network) {
                handoff.onNetworkLost(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            // Never bind the tun to itself.
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "registerNetworkCallback failed: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback
        networkCallback = null
        if (cb != null) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(cb) }
        }
        networkHandoff?.close()
        networkHandoff = null
    }

    /**
     * The tun's dependencies on a network: with virtual DNS the advertised resolver is ours and
     * never changes, so only the IP families matter; on the legacy path the resolver set does too.
     */
    private fun fingerprintOf(cm: ConnectivityManager, network: Network, virtualDns: Boolean): VpnNetworkHandoff.LinkFingerprint? =
        runCatching { cm.getLinkProperties(network) }.getOrNull()?.let { fingerprintOf(it, virtualDns) }

    private fun fingerprintOf(link: LinkProperties, virtualDns: Boolean): VpnNetworkHandoff.LinkFingerprint {
        val addresses = link.linkAddresses.map { it.address }
        val resolvers = VpnDnsPlan.resolvers(virtualDns, link.dnsServers.mapNotNull { it.hostAddress })
        return VpnNetworkHandoff.LinkFingerprint(
            dnsServers = resolvers.sorted(),
            hasIpv4 = addresses.any { it is java.net.Inet4Address },
            hasIpv6 = addresses.any { it is java.net.Inet6Address && !it.isLinkLocalAddress },
        )
    }

    /**
     * Without this the tun read loop is frozen by Doze and traffic is silently dropped.
     * Paired strictly with capture lifetime and always released in [cleanup].
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.i(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    /**
     * S1 task 11: promotes the service to the foreground with a minimal notification. Called
     * synchronously from [onStartCommand] (before any DataStore read); [updateNotification]
     * fills in the port and health counters later without re-calling `startForeground`.
     * Keeps `specialUse` (manifest `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`); never `dataSync`.
     */
    private fun startForegroundNotification(port: Int?) {
        val notification = buildNotification(port, if (port == null) "Starting capture..." else healthLine(null))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(port: Int, health: String) {
        if (!foregroundStarted) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(port, health)) }
    }

    private fun buildNotification(port: Int?, health: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Traffic interception",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while requestr is capturing traffic." }
            )
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RequestrVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("requestr is capturing traffic")
            .setContentText(if (port != null) "Port $port \u00b7 $health" else health)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    override fun onRevoke() {
        // User revoked VPN consent from system settings.
        stopCapture("VPN permission revoked by system")
        super.onRevoke()
    }
    override fun onDestroy() {
        cleanup()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RequestrVpn"
        private const val CHANNEL_ID = "requestr_interception"
        private const val NOTIFICATION_ID = 4201
        private const val WAKE_LOCK_TAG = "requestr:capture"

        // Re-acquired per session; bounded so a crashed session cannot pin the CPU forever.
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L

        /**
         * Defect D/E (S0 gaps audit): bounded escalation for confirming native tun2proxy
         * shutdown in [cleanup] before touching the tun fd / MITM engine / socket protector.
         * Each attempt re-signals [Tun2ProxyBridge.stop] and waits up to
         * [Tun2ProxyBridge.STOP_QUIESCENCE_TIMEOUT_MS] again -- finite, not unbounded, but more
         * forgiving than giving up after a single window.
         */
        private const val STOP_MAX_ATTEMPTS = 3

        /** S1 task 11: notification health-counter refresh cadence. */
        private const val HEALTH_REFRESH_MS = 10_000L
        private const val MTU = 1500
        private const val TUN_ADDRESS = "10.111.222.1"
        private const val TUN_PREFIX = 32

        // Task 9: a ULA (RFC 4193, fc00::/7) address for the tun interface itself, distinct
        // from any real network prefix -- same role TUN_ADDRESS/TUN_PREFIX play for v4.
        private const val TUN_ADDRESS_V6 = "fd00:6112:6222::1"
        private const val TUN_PREFIX_V6 = 64

        const val ACTION_STOP = "com.example.interception.STOP"

        /** Task 14 bridge -- see [activePump]'s doc for why this exists. Null whenever no
         * capture is running; the UI must treat null as "relay down", not as "loading". */
        private val activePumpFlow = kotlinx.coroutines.flow.MutableStateFlow<PacketPump?>(null)
        val activePumpState: kotlinx.coroutines.flow.StateFlow<PacketPump?> = activePumpFlow.asStateFlow()

        fun start(context: Context) {
            // S1 task 11: startForegroundService on API 26+; onStartCommand calls
            // startForeground() synchronously before touching DataStore.
            ContextCompat.startForegroundService(context, Intent(context, RequestrVpnService::class.java))
        }

        internal fun formatBytes(n: Long): String = when {
            n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
            n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
            n >= 1L shl 10 -> "%.1f KB".format(n / (1L shl 10).toDouble())
            else -> "$n B"
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RequestrVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}

/**
 * The tun app-filter decision, pure and independent of [android.net.VpnService] so it can be
 * unit-tested (a tun cannot be established in a JVM unit test).
 */
sealed class FilterPlan {
    data class Allowlist(val packages: Set<String>) : FilterPlan()
    object Blocklist : FilterPlan()
}

/**
 * Chooses exactly one of [android.net.VpnService.Builder.addAllowedApplication] /
 * [android.net.VpnService.Builder.addDisallowedApplication] — never both, since
 * `VpnService.Builder` throws `UnsupportedOperationException` when mixed, which is why
 * `SELECTED_APPS` mode never established a tun before this fix.
 *
 * - [AppFilterMode.ALL_APPS] → blocklist containing only [self] (excludes requestr's own
 *   traffic from looping back through the tun).
 * - [AppFilterMode.SELECTED_APPS] with a non-empty [selected] set (minus [self], which never
 *   needs to route through its own tun) → allowlist of exactly those packages.
 * - [AppFilterMode.SELECTED_APPS] where [selected] is empty, or contains only [self] → falls
 *   back to blocklist rather than establishing a tun that routes nothing.
 */
internal fun resolveFilterPlan(mode: AppFilterMode, selected: Set<String>, self: String): FilterPlan {
    if (mode != AppFilterMode.SELECTED_APPS) return FilterPlan.Blocklist
    val others = selected.filter { it != self }.toSet()
    if (others.isEmpty()) return FilterPlan.Blocklist
    return FilterPlan.Allowlist(others)
}
