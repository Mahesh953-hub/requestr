package com.example.interception.tun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain-JUnit coverage for [NativeTun2Proxy]'s Kotlin-side guard logic. `librequestr_tun2proxy.so`
 * is never packaged for the JVM unit-test runtime (it only exists for Android ABIs), so
 * [NativeTun2Proxy.isAvailable] is deterministically false here -- exercising exactly the
 * "library never loaded" branch of every public method without needing Robolectric or any
 * native binary on the test classpath. `android.util.Log` calls inside these methods are backed
 * by `isReturnDefaultValues = true` (see `app/build.gradle.kts`), so they run without a stub.
 */
class NativeTun2ProxyTest {

    @Test
    fun `library never loads under the plain jvm unit test runtime`() {
        assertFalse(NativeTun2Proxy.isAvailable())
        assertNotNull(NativeTun2Proxy.loadFailureReason())
    }

    @Test
    fun `start reports not running without touching jni when unavailable`() {
        val rc = NativeTun2Proxy.start("{}", tunFd = 42)
        assertEquals(NativeTun2Proxy.ERR_NOT_RUNNING, rc)
    }

    @Test
    fun `stop reports not running when unavailable`() {
        assertEquals(NativeTun2Proxy.ERR_NOT_RUNNING, NativeTun2Proxy.stop())
    }

    @Test
    fun `state reports idle when unavailable`() {
        assertEquals(NativeTun2Proxy.STATE_IDLE, NativeTun2Proxy.state())
    }

    @Test
    fun `lastError surfaces the load failure message when unavailable`() {
        assertEquals(NativeTun2Proxy.loadFailureReason()?.message, NativeTun2Proxy.lastError())
    }

    @Test
    fun `trafficStats is null when unavailable rather than a fabricated zero`() {
        assertNull(NativeTun2Proxy.trafficStats())
    }
}
