package com.example.runtime.frida

import android.content.Context
import com.example.runtime.RuntimeInstrumentationEngine

/**
 * One clean entry point for wiring [FridaOkHttpAdapter] into
 * [RuntimeInstrumentationEngine] -- the piece a caller (Settings UI "add a Frida target" action,
 * MainActivity bootstrap, etc.) can invoke later without needing to know
 * [RuntimeInstrumentationEngine.registerAdapter]'s exact contract or [FridaClient.FridaTarget]'s
 * shape. Deliberately not called from anywhere in this codebase yet -- registering it
 * unconditionally at app startup would mean every launch tries to spawn/attach Frida against a
 * real target with no target configured, which is wrong; this is exposed for a caller that has
 * an actual target package/pid to instrument.
 */
object FridaRuntimeWiring {

    /**
     * Registers a fresh [FridaOkHttpAdapter] for [target] with [RuntimeInstrumentationEngine] and
     * starts it (which starts the whole engine's poll loop if it isn't already running). Returns
     * the adapter so a caller can inspect [FridaOkHttpAdapter.status]/[FridaOkHttpAdapter.isActive]
     * directly instead of only through the engine's aggregate maps.
     */
    fun registerAndStart(context: Context, target: FridaClient.FridaTarget): FridaOkHttpAdapter {
        val adapter = FridaOkHttpAdapter(target)
        RuntimeInstrumentationEngine.registerAdapter(adapter)
        RuntimeInstrumentationEngine.start(context)
        return adapter
    }
}
