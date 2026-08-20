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

                // Real per-app filtering via UID-backed allow-list. Always exclude ourselves so
                // requestr's own traffic does not loop back through the tun.
                builder.addDisallowedApplication(packageName)
                if (filterMode == AppFilterMode.SELECTED_APPS && selected.isNotEmpty()) {
                    selected.forEach { pkg ->
                        if (pkg != packageName) {
                            runCatching { builder.addAllowedApplication(pkg) }
                                .onFailure { Log.w(TAG, "Cannot filter missing package $pkg") }
                        }
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
                pumpJob = scope.launch {
                    PacketPump(
                        input = FileInputStream(tun.fileDescriptor),
                        output = FileOutputStream(tun.fileDescriptor),
                        sessionId = newId,
                        db = db,
                        printUndecoded = printUndecoded,
                    ).run(this)
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
        runCatching { tunInterface?.close() }
        tunInterface = null
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
            .setContentText("Local VPN active on port $port")
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

        const val ACTION_STOP = "com.example.interception.STOP"

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
