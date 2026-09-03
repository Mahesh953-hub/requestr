package com.example.interception.net

import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-backed coverage for the framework path that JVM fakes cannot validate. */
@RunWith(AndroidJUnit4::class)
class VpnServiceSocketProtectionInstrumentedTest {
    private class RealProtectService : VpnService() {
        fun attachForTest(context: android.content.Context) = attachBaseContext(context)
        fun protectPrepared(socket: Socket): Boolean = protect(socket)
    }

    @Test
    fun preparedPlainSocketHasFdAndRealVpnServiceProtectDoesNotThrow() {
        val service = RealProtectService().apply {
            attachForTest(ApplicationProvider.getApplicationContext())
        }
        val socket = Socket()
        assertFalse(socket.isBound)

        val registration = SocketProtector.install(object : SocketProtector.Backing {
            override fun protectSocket(socket: Socket): Boolean = service.protectPrepared(socket)
            override fun protectDatagramSocket(socket: java.net.DatagramSocket): Boolean = service.protect(socket)
            override fun protectFd(fd: Int): Boolean = service.protect(fd)
        })
        try {
            // The framework may return false when VPN preparation is not granted to the test app;
            // this test's contract is that our production API materializes a real fd before the
            // real VpnService overload accesses it, without connecting or throwing.
            SocketProtector.protectSocket(socket)
            assertTrue(socket.isBound)
            assertFalse(socket.isConnected)
        } finally {
            SocketProtector.clear(registration)
            socket.close()
            service.onDestroy()
        }
    }
}
