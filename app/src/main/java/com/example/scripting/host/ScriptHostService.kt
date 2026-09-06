package com.example.scripting.host

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * S4 task 32: the isolated script host. Runs in `android:process=":scripthost"` (manifest,
 * `exported=false`) so a script that hangs, recurses into an OOM or otherwise misbehaves can be
 * killed by [ScriptHostClient] without touching the VPN/relay process (gate G20).
 *
 * Protocol (Messenger, JSON in a String extra -- no Parcelable coupling across the boundary):
 *  - `MSG_HELLO` -> replies `MSG_HELLO` with `KEY_PID` so the client knows whom to kill.
 *  - `MSG_RUN` with `KEY_JSON` = [ScriptContract.ScriptRequestEnvelope] and `KEY_CALL_ID` ->
 *    replies `MSG_RESULT` with the same call id and `KEY_JSON` = [ScriptContract.ScriptResponseEnvelope].
 *
 * Each script runs on a small worker pool; the Rhino deadline in [com.example.scripting.JsScriptEngine]
 * ends well-behaved overruns, and the client's own deadline + `killProcess` ends everything else.
 */
class ScriptHostService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var messenger: Messenger
    private val workers: ExecutorService = Executors.newFixedThreadPool(WORKER_THREADS) { r ->
        Thread(r, "scripthost-worker").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        handlerThread = HandlerThread("scripthost-messenger").apply { start() }
        messenger = Messenger(IncomingHandler(handlerThread))
        Log.i(TAG, "script host up, pid=${Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        workers.shutdownNow()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private inner class IncomingHandler(thread: HandlerThread) : Handler(thread.looper) {
        override fun handleMessage(msg: Message) {
            val replyTo = msg.replyTo ?: return
            when (msg.what) {
                ScriptContract.MSG_HELLO -> {
                    val reply = Message.obtain(null, ScriptContract.MSG_HELLO)
                    reply.data = Bundle().apply { putInt(ScriptContract.KEY_PID, Process.myPid()) }
                    runCatching { replyTo.send(reply) }
                }
                ScriptContract.MSG_RUN -> {
                    val json = msg.data.getString(ScriptContract.KEY_JSON) ?: return
                    val callId = msg.data.getLong(ScriptContract.KEY_CALL_ID)
                    workers.execute {
                        val response = try {
                            ScriptContract.execute(ScriptContract.decodeRequest(json), pid = Process.myPid())
                        } catch (t: Throwable) {
                            ScriptContract.ScriptResponseEnvelope(ok = false, error = "script host failure: ${t.message ?: t.javaClass.simpleName}", pid = Process.myPid())
                        }
                        val reply = Message.obtain(null, ScriptContract.MSG_RESULT)
                        reply.data = Bundle().apply {
                            putLong(ScriptContract.KEY_CALL_ID, callId)
                            putString(ScriptContract.KEY_JSON, ScriptContract.encodeResponse(response))
                        }
                        runCatching { replyTo.send(reply) }.onFailure { Log.w(TAG, "reply failed: ${it.message}") }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "ScriptHostService"
        const val WORKER_THREADS = 4
    }
}
