package com.example

import android.app.Application
import com.example.data.local.RequestrDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide application class that eagerly initializes [RequestrDatabase] on
 * [Dispatchers.IO] before any Activity, Service, or BroadcastReceiver can call
 * [RequestrDatabase.get].
 *
 * This ensures the first-open file I/O (backup/restore, WAL checkpoint, version read)
 * runs off the main thread, and subsequent [RequestrDatabase.get] calls on the main
 * thread hit the fast path (returns the cached singleton immediately, zero I/O).
 */
class RequestrApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Kick off DB init eagerly on IO.  RequestrDatabase.initAsync stores a
        // CompletableDeferred so any caller that races with us can await without
        // blocking the main thread with file I/O.
        appScope.launch {
            RequestrDatabase.initAsync(this@RequestrApplication)
        }
    }
}
