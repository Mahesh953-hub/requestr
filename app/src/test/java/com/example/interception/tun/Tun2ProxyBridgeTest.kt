package com.example.interception.tun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JUnit coverage for [Tun2ProxyBridge]'s "fail loud, never fake success" contract. The
 * native library never loads under the JVM unit-test runtime (see [NativeTun2ProxyTest]), so
 * [Tun2ProxyBridge.start] deterministically takes the [Tun2ProxyBridge.StartResult.Unavailable]
 * branch here -- this is exactly the branch [RequestrVpnService] falls back on in production
 * when a device/build variant never packaged the native bridge for its ABI.
 */
class Tun2ProxyBridgeTest {

    private val config = Tun2ProxyConfig.forMitmProxyEngine(mitmPort = 12345)

    @Test
    fun `start reports unavailable and never claims success without the native lib`() {
        val result = Tun2ProxyBridge.start(config, tunFd = 7)

        assertTrue(result is Tun2ProxyBridge.StartResult.Unavailable)
        val reason = (result as Tun2ProxyBridge.StartResult.Unavailable).reason
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun `isRunning is false when the bridge never started`() {
        assertFalse(Tun2ProxyBridge.isRunning())
    }

    @Test
    fun `trafficStats is null when the bridge never started`() {
        assertEquals(null, Tun2ProxyBridge.trafficStats())
    }

    @Test
    fun `stop is a safe no-op when nothing is running`() {
        assertEquals(NativeTun2Proxy.ERR_NOT_RUNNING, Tun2ProxyBridge.stop())
    }

    @Test
    fun `awaitStoppedOrTimeout reports not running without a native bridge`() {
        val start = System.currentTimeMillis()
        val rc = Tun2ProxyBridge.awaitStoppedOrTimeout(timeoutMs = 300L)
        val elapsed = System.currentTimeMillis() - start
        // Without the native lib there is no thread to join: this must report ERR_NOT_RUNNING
        // (an explicit "nothing to wait on" signal, not success and not a timeout) and return
        // promptly rather than sleeping out the bound.
        assertEquals(NativeTun2Proxy.ERR_NOT_RUNNING, rc)
        assertTrue("expected a prompt return, took ${elapsed}ms", elapsed < 300L)
    }
}
