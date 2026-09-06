package com.example.scripting.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * S4 task 32: the app-process side of the isolated script host (gate G20).
 *
 *  - Binds lazily; one round trip per script call with a hard deadline of the rule's
 *    `timeoutMs` (ceiling [MAX_TIMEOUT_MS]) plus [DEADLINE_GRACE_MS] for IPC.
 *  - A missed deadline means the host is wedged: [Transport.kill] unbinds and
 *    `Process.killProcess(remotePid)`; the next call re-binds a fresh process. The calling flow
 *    gets a failure and continues unmodified -- a script can never hang a relay.
 *  - Circuit breaker: [CIRCUIT_BREAKER_FAILURES] consecutive *host* failures (timeouts / dead
 *    binder -- not script errors) open the circuit; every call fails fast until [reset].
 *  - [killSwitch] (task 33 safety control) kills the host and opens the circuit.
 *
 * The Messenger plumbing sits behind [Transport] so the deadline / breaker / kill logic is unit
 * tested with a fake transport, and the real one ([MessengerTransport]) stays thin.
 */
class ScriptHostClient(
    private val transport: Transport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One request/response exchange with the remote host. */
    interface Transport {
        /** Blocks up to [deadlineMs]; null means the deadline passed (the host is presumed wedged). */
        fun call(requestJson: String, deadlineMs: Long): String?

        /** Unbind and kill the remote process; the next [call] must start a fresh one. */
        fun kill(reason: String)
    }

    enum class State { CLOSED, OPEN }

    sealed interface Outcome {
        data class Ok(val response: ScriptContract.ScriptResponseEnvelope) : Outcome
        /** The host could not run the script at all (deadline, dead binder, circuit open). */
        data class HostFailure(val reason: String) : Outcome
    }

    private val consecutiveFailures = AtomicInteger(0)
    private val _state = MutableStateFlow(State.CLOSED)
    val state: StateFlow<State> get() = _state
    val isOpen: Boolean get() = _state.value == State.OPEN
    private val _lastFailure = MutableStateFlow<String?>(null)
    /** Why the circuit opened / the host was killed last -- the banner text. */
    val lastFailure: StateFlow<String?> get() = _lastFailure
    private val kills = AtomicInteger(0)
    val killCount: Int get() = kills.get()

    fun run(request: ScriptContract.ScriptRequestEnvelope): Outcome {
        if (isOpen) return Outcome.HostFailure("script host circuit open: ${_lastFailure.value ?: "too many host failures"}")
        val deadline = request.timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS) + DEADLINE_GRACE_MS
        val json = try {
            transport.call(ScriptContract.encodeRequest(request), deadline)
        } catch (t: Throwable) {
            return hostFailed("script host call failed: ${t.message ?: t.javaClass.simpleName}", kill = true)
        }
        if (json == null) return hostFailed("script host missed its ${deadline}ms deadline", kill = true)
        val response = try {
            ScriptContract.decodeResponse(json)
        } catch (t: Throwable) {
            return hostFailed("script host returned an unreadable reply: ${t.message}", kill = false)
        }
        consecutiveFailures.set(0)
        return Outcome.Ok(response)
    }

    private fun hostFailed(reason: String, kill: Boolean): Outcome {
        Log.w(TAG, reason)
        _lastFailure.value = reason
        if (kill) {
            kills.incrementAndGet()
            runCatching { transport.kill(reason) }
        }
        if (consecutiveFailures.incrementAndGet() >= CIRCUIT_BREAKER_FAILURES) {
            _state.value = State.OPEN
            Log.w(TAG, "script host circuit OPEN after $CIRCUIT_BREAKER_FAILURES consecutive failures")
        }
        return Outcome.HostFailure(reason)
    }

    /** Global safety control: kill the host, open the circuit. Scripts stop running until [reset]. */
    fun killSwitch(reason: String = "kill switch") {
        _lastFailure.value = reason
        kills.incrementAndGet()
        runCatching { transport.kill(reason) }
        _state.value = State.OPEN
    }

    /** Closes the circuit (user re-enabled scripts / acknowledged the banner). */
    fun reset() {
        consecutiveFailures.set(0)
        _lastFailure.value = null
        _state.value = State.CLOSED
    }

    companion object {
        private const val TAG = "ScriptHostClient"
        const val MAX_TIMEOUT_MS = com.example.interception.rules.RuleLimits.MAX_SCRIPT_TIMEOUT_MS
        const val DEADLINE_GRACE_MS = 500L
        const val CIRCUIT_BREAKER_FAILURES = 3

        @Volatile
        private var INSTANCE: ScriptHostClient? = null

        /** Process-wide client over the real bound service. */
        fun get(context: Context): ScriptHostClient = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ScriptHostClient(MessengerTransport(context.applicationContext)).also { INSTANCE = it }
        }
    }
}

/**
 * The real transport: binds [ScriptHostService] (its own process), learns the remote pid from
 * `MSG_HELLO`, correlates replies by call id, and kills/unbinds on demand.
 */
class MessengerTransport(private val context: Context) : ScriptHostClient.Transport {
    private val lock = Any()
    private var service: Messenger? = null
    private var bound = false
    private var remotePid = 0
    private var bindFuture: CompletableFuture<Messenger>? = null
    private val pending = ConcurrentHashMap<Long, CompletableFuture<String>>()
    private val nextCallId = AtomicLong(1)
    private val replyThread = HandlerThread("scripthost-client").apply { start() }
    private val replyMessenger = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                ScriptContract.MSG_HELLO -> synchronized(lock) { remotePid = msg.data.getInt(ScriptContract.KEY_PID) }
                ScriptContract.MSG_RESULT -> {
                    val id = msg.data.getLong(ScriptContract.KEY_CALL_ID)
                    val json = msg.data.getString(ScriptContract.KEY_JSON) ?: ""
                    pending.remove(id)?.complete(json)
                }
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = Messenger(binder)
            synchronized(lock) {
                service = m
                bindFuture?.complete(m)
            }
            runCatching { m.send(Message.obtain(null, ScriptContract.MSG_HELLO).apply { replyTo = replyMessenger }) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { service = null }
            failAllPending("script host disconnected")
        }

        override fun onBindingDied(name: ComponentName?) {
            synchronized(lock) { service = null; bound = false }
            failAllPending("script host binding died")
        }
    }

    private fun failAllPending(reason: String) {
        val all = pending.values.toList(); pending.clear()
        all.forEach { it.completeExceptionally(IllegalStateException(reason)) }
    }

    private fun connect(deadlineMs: Long): Messenger {
        val future: CompletableFuture<Messenger>
        synchronized(lock) {
            service?.let { return it }
            future = bindFuture ?: CompletableFuture<Messenger>().also { bindFuture = it }
            if (!bound) {
                val ok = context.bindService(Intent(context, ScriptHostService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
                if (!ok) {
                    bindFuture = null
                    throw IllegalStateException("bindService(ScriptHostService) returned false")
                }
                bound = true
            }
        }
        return try {
            future.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("script host did not bind within ${deadlineMs}ms")
        } finally {
            synchronized(lock) { if (bindFuture === future) bindFuture = null }
        }
    }

    override fun call(requestJson: String, deadlineMs: Long): String? {
        val startedAt = System.currentTimeMillis()
        val messenger = connect(deadlineMs)
        val id = nextCallId.getAndIncrement()
        val future = CompletableFuture<String>()
        pending[id] = future
        val msg = Message.obtain(null, ScriptContract.MSG_RUN).apply {
            replyTo = replyMessenger
            data = Bundle().apply {
                putLong(ScriptContract.KEY_CALL_ID, id)
                putString(ScriptContract.KEY_JSON, requestJson)
            }
        }
        try {
            messenger.send(msg)
        } catch (t: Throwable) {
            pending.remove(id)
            throw IllegalStateException("send to script host failed: ${t.message}", t)
        }
        val remaining = (deadlineMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1L)
        return try {
            future.get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.remove(id)
            null
        } catch (e: Exception) {
            pending.remove(id)
            throw IllegalStateException(e.cause?.message ?: e.message ?: "script host call failed", e)
        }
    }

    override fun kill(reason: String) {
        val pid: Int
        synchronized(lock) {
            pid = remotePid
            remotePid = 0
            service = null
            if (bound) {
                runCatching { context.unbindService(connection) }
                bound = false
            }
            bindFuture?.completeExceptionally(IllegalStateException(reason))
            bindFuture = null
        }
        failAllPending(reason)
        if (pid > 0 && pid != Process.myPid()) {
            Log.w(TAG, "killing script host pid=$pid: $reason")
            runCatching { Process.killProcess(pid) }
        }
    }

    companion object {
        private const val TAG = "MessengerTransport"
    }
}
