package com.example.interception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.CaptureSessionEntity
import com.example.data.local.CaptureTier
import com.example.data.local.RequestrDatabase
import com.example.data.prefs.AppFilterMode
import com.example.data.prefs.SettingsDataStore
import com.example.data.repository.SettingsRepository
import com.example.interception.tls.TlsKeyLogRegistry
import com.example.interception.tun.ProtectHook
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

    private var tunInterface: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pumpJob: Job? = null
    private var filterObserverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionId: Long? = null

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
    private val protectHook = object : ProtectHook {
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
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        if (tunInterface != null) {
            Log.w(TAG, "startCapture ignored; already running")
            return
        }

        scope.launch {
            try {
                val prefs = SettingsDataStore.get(applicationContext)
                val port = prefs.proxyPort.first()
                val filterMode = prefs.appFilterMode.first()
                val selected = prefs.selectedPackages.first()

                startForegroundNotification(port)
                acquireWakeLock()

                val builder = Builder()
                    .setSession("requestr")
                    .setMtu(MTU)
                    .addAddress(TUN_ADDRESS, TUN_PREFIX)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")

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
                when (val plan = resolveFilterPlan(filterMode, selected, packageName)) {
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

                val tun = builder.establish()
                if (tun == null) {
                    fail("Could not establish VPN interface. VPN consent may have been revoked.")
                    return@launch
                }
                tunInterface = tun

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
                    tier = ActiveTier.VPN,
                    port = port,
                    method = CaptureMethod.VPN_TUN,
                )

                val printUndecoded = prefs.printUndecoded.first()
                val dumpToFile = prefs.dumpToFile.first()
                val captureEnabled = SettingsRepository.settings.value.trafficCaptureEnabled
                // Complaint #5 audit fix: Enhanced Mode now feeds CaptureEngine's real
                // ProtocolDetector analysis pass (see CaptureEngine.enhancedDiagnostics) instead
                // of gating nothing but a debug Log.i() line.
                val enhancedMode = prefs.enhancedMode.first()

                // T1: PCAP export — started/stopped alongside the VPN tun so its lifetime
                // matches one capture session exactly. PacketPump.writeToPcap() is a no-op
                // when PacketCaptureEngine.isActive() is false, so this preference read is the
                // only gate; nothing needs to be threaded into PacketPump itself.
                val pcapEnabled = prefs.pcapEnabled.first()
                if (pcapEnabled) {
                    val started = com.example.interception.packet.PacketCaptureEngine.start(applicationContext)
                    Log.i(TAG, if (started) "PCAP export enabled for session=$newId" else "PCAP export failed to start")
                }

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
                        protectHook = protectHook,
                    )
                    activePump = pump
                    pump.run(this)
                }

                Log.i(TAG, "Capture started on tun, session=$newId port=$port")

                // Fix #2 (TODO): Hot-reload app filter. VpnService.Builder doesn't
                // support dynamic app list changes after establish(), so we tear down
                // and re-establish the tun when the user changes the filter.
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
     * Room write uses `runBlocking` on [Dispatchers.IO] -- the same pattern already used by
     * [PacketPump.persistUdpFlow]/`persistQuicConnection` for the same reason.
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
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                RequestrDatabase.get(applicationContext).dnsQueryDao().insert(
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
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist DNS query: ${t.message}")
        }

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
        Log.e(TAG, message)
        InterceptionStateRepository.markError(message)
        cleanup()
        stopSelf()
    }

    private fun stopCapture(reason: String) {
        Log.i(TAG, "Stopping capture: $reason")
        scope.launch {
            sessionId?.let { id ->
                runCatching {
                    RequestrDatabase.get(applicationContext).captureSessionDao().markEnded(id)
                }
            }
            InterceptionStateRepository.markStopped()
            cleanup()
            stopSelf()
        }
    }

    private fun cleanup() {
        filterObserverJob?.cancel()
        filterObserverJob = null
        pumpJob?.cancel()
        pumpJob = null
        // Task 4: stop the relay's selector loop and close every open upstream socket
        // explicitly, alongside cancelling the pump's coroutine (which also calls
        // TunRelay.stop() in its own `finally`, but this covers the case where the tun read
        // loop is already blocked/stuck and never reaches that finally block).
        runCatching { activePump?.relay?.stop() }
        activePump = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        // T1: stop PCAP export with the rest of this session's lifecycle -- flushes and closes
        // the .pcap file. No-op (guarded by its own `active` flag) if it was never started.
        runCatching { com.example.interception.packet.PacketCaptureEngine.stop() }
        // Task 5: secrets are in-memory only and must not outlive a capture session.
        runCatching { TlsKeyLogRegistry.clear() }
        releaseWakeLock()
        stopForegroundCompat()
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

    private fun startForegroundNotification(port: Int) {
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

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("requestr is capturing traffic")
            .setContentText("VPN active — traffic is relayed; HTTPS bodies need proxy mode")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
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
            context.startService(Intent(context, RequestrVpnService::class.java))
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
