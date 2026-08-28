package com.example.interception

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.interception.InterceptionStateRepository
import com.example.data.local.RequestrDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gap #1 Approach A coverage for [PacketPump]'s native-bridge branch of [PacketPump.run]
 * (`runNativeBridgeLoop`). This is the only place [PacketPump] behaves differently when the
 * native tun2proxy bridge -- not this pump -- owns the raw tun fd: the pump must not touch
 * [FileInputStream]/[FileOutputStream] itself, and must instead keep session counters honest
 * purely from the injected [nativeTrafficStatsPoll] lambda, reusing the existing
 * [InterceptionStateRepository]/session-row flush machinery unchanged.
 *
 * The poll lambda is a plain `() -> Pair<Long, Long>?`, not a real [com.example.interception.tun.Tun2ProxyBridge]
 * call -- exactly the loose-coupling the constructor parameter was designed for, so this test
 * needs no native binary on the classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class PacketPumpNativeBridgeModeTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = RequestrDatabase.get(context)

    private fun newFilePair(): Pair<FileInputStream, FileOutputStream> {
        val inFile = File.createTempFile("pump-native-in", ".bin")
        val outFile = File.createTempFile("pump-native-out", ".bin")
        inFile.deleteOnExit()
        outFile.deleteOnExit()
        return FileInputStream(inFile) to FileOutputStream(outFile)
    }

    @Test
    fun `native bridge mode updates byteCount from the poller and never fabricates packetCount`() = runBlocking {
        val (input, output) = newFilePair()
        var pollCount = 0
        val pump = PacketPump(
            context = context,
            input = input,
            output = output,
            sessionId = 4242L,
            db = db,
            nativeBridgeMode = true,
            nativeTrafficStatsPoll = {
                pollCount++
                (12_345L to 6_789L)
            },
        )

        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val runJob = scope.launch { pump.run(scope) }

        // The loop polls immediately on entry, before its first delay -- give it a generous
        // but bounded window to do so before cancelling, rather than sleeping for the full
        // 1s FLUSH_INTERVAL_MS the production loop uses between polls.
        var waited = 0L
        while (pollCount == 0 && waited < 2000L) {
            delay(20L)
            waited += 20L
        }
        assertTrue("poller was never invoked", pollCount > 0)

        scope.cancel()
        runJob.join()

        val state = InterceptionStateRepository.state.value
        assertEquals(12_345L + 6_789L, state.bytesTransferred)
        // Native mode intentionally never fabricates a packet count -- tun2proxy's
        // traffic-status callback reports byte counters only.
        assertEquals(0, state.packetCount)

        input.close()
        output.close()
    }

    @Test
    fun `native bridge mode with a null poller leaves byteCount untouched`() = runBlocking {
        val (input, output) = newFilePair()
        var pollInvoked = false
        val pump = PacketPump(
            context = context,
            input = input,
            output = output,
            sessionId = 4243L,
            db = db,
            nativeBridgeMode = true,
            nativeTrafficStatsPoll = {
                pollInvoked = true
                null
            },
        )

        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val runJob = scope.launch { pump.run(scope) }

        var waited = 0L
        while (!pollInvoked && waited < 2000L) {
            delay(20L)
            waited += 20L
        }
        assertTrue("poller was never invoked", pollInvoked)

        scope.cancel()
        runJob.join()

        // Never having received a non-null stat, byteCount stays at its initial 0 -- this is
        // "record nothing rather than a plausible-looking value", not a bug.
        assertEquals(0L, InterceptionStateRepository.state.value.bytesTransferred)

        input.close()
        output.close()
    }
}
