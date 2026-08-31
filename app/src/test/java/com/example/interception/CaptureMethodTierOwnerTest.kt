package com.example.interception

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.interception.ActiveTier
import com.example.data.interception.CaptureLifecycleOwner
import com.example.data.interception.CaptureMethod
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.engineLifecycleOwner
import com.example.data.interception.engineTier
import java.io.File
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the fix for the tier/lifecycle-owner mismatch this same round's audit surfaced:
 * [HttpProxyEngine] used to hardcode `tier = ActiveTier.VPN` in its own `markActive()` call while
 * [PreflightController.run] resolved `ActiveTier.PROXY` for the identical [CaptureMethod.HTTP_PROXY]
 * -- the same "one fact, two writers, no guarantee they agree" defect [CaptureLifecycleOwner]
 * exists to prevent for lifecycle ownership, just for [ActiveTier], and just as capable of sending
 * a stop down the wrong path (`ActiveTier` is exactly the kind of field [CaptureController] and
 * [PreflightController.stopCapture] read).
 *
 * Two layers, deliberately:
 *  - [`CaptureMethod dot engineTier and engineLifecycleOwner match the hand-authored truth table`]
 *    pins [CaptureMethod.engineTier]/[CaptureMethod.engineLifecycleOwner] themselves against an
 *    expected table written independently in this test (not by calling the same production
 *    function back at itself, which would be tautological) -- so a future edit to that shared
 *    mapping cannot silently change what any method reports.
 *  - [`HttpProxyEngine really starts publishing PROXY tier not a stale VPN literal`] and
 *    [`MitmProxyEngine really starts publishing the same PROXY tier HttpProxyEngine does`]
 *    actually start the real engine and read the real [InterceptionStateRepository.state] it
 *    publishes -- this is the part that would have caught the original bug, since the bug was in
 *    the engine's own hardcoded call site, not in any shared function (there wasn't one yet).
 *    [RootIptablesEngine]/[ShizukuTunEngine] are not driven for real here (both require `su`/
 *    Shizuku binder access unavailable on this sandbox/emulator -- the same documented
 *    limitation noted elsewhere in this test suite); the truth-table test above is still real
 *    coverage of what they *should* publish, since both were changed by this fix to derive their
 *    `markActive()` arguments from the exact same [CaptureMethod.engineTier]/
 *    [CaptureMethod.engineLifecycleOwner] functions this test pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CaptureMethodTierOwnerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * [MitmProxyEngine.start] calls [CertificateAuthority.getOrCreate], which needs a CA
     * keystore password provider. Robolectric ships no real `AndroidKeyStore`, so -- same as
     * [MitmProxyEngineMitmHandshakeTest] -- swap in an in-memory fake for the duration of the
     * test rather than exercising the real `AndroidKeyStore`-backed provider.
     */
    private class FakePasswordProvider : CertificateAuthority.CaKeystorePasswordProvider {
        var stored: CharArray? = null
        override suspend fun read(context: Context): CharArray? = stored?.copyOf()
        override suspend fun rotate(context: Context): CharArray {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP).toCharArray()
            stored = encoded.copyOf()
            return encoded
        }
    }

    @After
    fun tearDown() {
        InterceptionStateRepository.resetAll()
        CertificateAuthority.passwordProviderForTest = null
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()
    }

    /**
     * Hand-authored independently of [CaptureMethod.engineTier]/[CaptureMethod.engineLifecycleOwner]
     * -- this is the spec those functions must satisfy, not a mirror of their own implementation.
     * [CaptureMethod.ROOT_FRIDA] is deliberately absent: it has no single true tier/owner (see
     * [CaptureMethod.engineTier]'s KDoc) and is covered by its own throwing test below instead.
     */
    private val expected: Map<CaptureMethod, Pair<ActiveTier, CaptureLifecycleOwner>> = mapOf(
        CaptureMethod.VPN_TUN to (ActiveTier.VPN to CaptureLifecycleOwner.VPN_SERVICE),
        CaptureMethod.MITM_PROXY to (ActiveTier.PROXY to CaptureLifecycleOwner.MITM_PROXY_ENGINE),
        CaptureMethod.HTTP_PROXY to (ActiveTier.PROXY to CaptureLifecycleOwner.HTTP_PROXY_ENGINE),
        CaptureMethod.ROOT_IPTABLES to (ActiveTier.ROOT to CaptureLifecycleOwner.ROOT_IPTABLES_ENGINE),
        CaptureMethod.SHIZUKU_TUN to (ActiveTier.SHIZUKU to CaptureLifecycleOwner.SHIZUKU_TUN_ENGINE),
    )

    @Test
    fun `CaptureMethod dot engineTier and engineLifecycleOwner match the hand-authored truth table`() {
        expected.forEach { (method, tierAndOwner) ->
            val (tier, owner) = tierAndOwner
            assertEquals("wrong tier for $method", tier, method.engineTier())
            assertEquals("wrong lifecycle owner for $method", owner, method.engineLifecycleOwner())
        }
    }

    @Test
    fun `ROOT_FRIDA has no single true tier or owner and both functions throw rather than guess`() {
        assertThrows(IllegalStateException::class.java) { CaptureMethod.ROOT_FRIDA.engineTier() }
        assertThrows(IllegalStateException::class.java) { CaptureMethod.ROOT_FRIDA.engineLifecycleOwner() }
    }

    @Test
    fun `HttpProxyEngine really starts publishing PROXY tier not a stale VPN literal`() {
        val engine = HttpProxyEngine()
        try {
            engine.start(context, 0)

            val state = InterceptionStateRepository.state.value
            val (expectedTier, expectedOwner) = expected.getValue(CaptureMethod.HTTP_PROXY)
            assertEquals(
                "HttpProxyEngine used to hardcode ActiveTier.VPN here -- see " +
                    "CaptureMethod.engineTier()'s KDoc for the audit fix",
                expectedTier,
                state.tier,
            )
            assertEquals(CaptureMethod.HTTP_PROXY, state.captureMethod)
            assertEquals(expectedOwner, state.lifecycleOwner)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `MitmProxyEngine really starts publishing the same PROXY tier HttpProxyEngine does`() {
        CertificateAuthority.passwordProviderForTest = FakePasswordProvider()
        File(context.filesDir, CertificateAuthority.KEYSTORE_FILE).delete()

        val engine = MitmProxyEngine(onDecryptedTransaction = {})
        try {
            engine.start(context, 0)

            val state = InterceptionStateRepository.state.value
            val (expectedTier, expectedOwner) = expected.getValue(CaptureMethod.MITM_PROXY)
            assertEquals(expectedTier, state.tier)
            assertEquals(CaptureMethod.MITM_PROXY, state.captureMethod)
            assertEquals(expectedOwner, state.lifecycleOwner)
        } finally {
            engine.stop()
        }
    }
}
