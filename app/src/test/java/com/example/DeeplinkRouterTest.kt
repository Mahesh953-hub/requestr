package com.example

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uses Robolectric for the same reason [com.example.flow.FlowSerializerRoundTripTest] does:
 * the plain android.jar stub on the unit-test classpath throws/returns nulls from
 * [Uri.parse]/[Uri.getQueryParameter] rather than actually parsing, so a real implementation
 * is needed to exercise [DeeplinkRouter.parse] under JVM unit tests.
 */
@RunWith(RobolectricTestRunner::class)
class DeeplinkRouterTest {

    @Test
    fun `each plain host maps to its route`() {
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.APIS),
            DeeplinkRouter.parse(Uri.parse("requestr://apis"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.NOTES),
            DeeplinkRouter.parse(Uri.parse("requestr://notes"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.TOOLS),
            DeeplinkRouter.parse(Uri.parse("requestr://tools"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.MUSIC),
            DeeplinkRouter.parse(Uri.parse("requestr://music"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.SETTINGS),
            DeeplinkRouter.parse(Uri.parse("requestr://settings"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.CHAT),
            DeeplinkRouter.parse(Uri.parse("requestr://chat"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.SAVED_APIS),
            DeeplinkRouter.parse(Uri.parse("requestr://saved"))
        )
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.HOME),
            DeeplinkRouter.parse(Uri.parse("requestr://home"))
        )
    }

    @Test
    fun `intercept host without toggle segment is navigation-only`() {
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.INTERCEPT),
            DeeplinkRouter.parse(Uri.parse("requestr://intercept"))
        )
    }

    @Test
    fun `intercept toggle segment is also navigation-only, not a distinct action`() {
        // intercept/toggle was intentionally scoped down to plain navigation: driving
        // start/stop needs an Activity-scoped consent launcher a top-level parser can't reach.
        assertEquals(
            DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.INTERCEPT),
            DeeplinkRouter.parse(Uri.parse("requestr://intercept/toggle"))
        )
    }

    @Test
    fun `api run with id yields RunApi carrying the id`() {
        val action = DeeplinkRouter.parse(Uri.parse("requestr://api/run?id=req-1"))
        assertEquals(DeeplinkRouter.DeeplinkAction.RunApi("req-1"), action)
    }

    @Test
    fun `api run without id falls back to plain navigation`() {
        val action = DeeplinkRouter.parse(Uri.parse("requestr://api/run"))
        assertEquals(DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.APIS), action)
    }

    @Test
    fun `flow execute yields ExecuteFlow with the optional id`() {
        val withId = DeeplinkRouter.parse(Uri.parse("requestr://flow/execute?id=flow-1"))
        assertEquals(DeeplinkRouter.DeeplinkAction.ExecuteFlow("flow-1"), withId)

        val withoutId = DeeplinkRouter.parse(Uri.parse("requestr://flow/execute"))
        assertEquals(DeeplinkRouter.DeeplinkAction.ExecuteFlow(null), withoutId)
    }

    @Test
    fun `note create with title yields CreateNote`() {
        val action = DeeplinkRouter.parse(Uri.parse("requestr://note/create?title=Log"))
        assertEquals(DeeplinkRouter.DeeplinkAction.CreateNote("Log"), action)
    }

    @Test
    fun `note create without title falls back to plain navigation`() {
        val action = DeeplinkRouter.parse(Uri.parse("requestr://note/create"))
        assertEquals(DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.NOTES), action)
    }

    @Test
    fun `unknown host returns null`() {
        assertNull(DeeplinkRouter.parse(Uri.parse("requestr://not-a-real-host")))
    }

    @Test
    fun `path-form fallback still resolves a known route`() {
        val action = DeeplinkRouter.parse(Uri.parse("requestr:///notes"))
        assertEquals(DeeplinkRouter.DeeplinkAction.Navigate(DeeplinkRouter.Routes.NOTES), action)
    }

    @Test
    fun `path-form fallback with unknown segment returns null`() {
        assertNull(DeeplinkRouter.parse(Uri.parse("requestr:///not-a-real-route")))
    }
}
