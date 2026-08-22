package com.example

import android.net.Uri

/**
 * Task 5 — pure URI→action mapping for `requestr://` deep links, extracted out of
 * `MainActivity.handleRequestrDeeplink` so the mapping is unit-testable without Compose/Android
 * framework classes beyond [Uri] itself.
 *
 * Covers both host-form links (`requestr://flow/execute?id=flow-1`) and the legacy path-form
 * fallback (`requestr:///notes`, i.e. an empty/blank authority with the route in the path)
 * that older links may still use.
 */
object DeeplinkRouter {

    /** A parsed, actionable deep link. Every case below is a real action, not just a route. */
    sealed interface DeeplinkAction {
        /** Just navigate — no side effect beyond changing screens. */
        data class Navigate(val route: String) : DeeplinkAction

        /** `requestr://api/run?id=<requestId>` — load and execute a saved request. */
        data class RunApi(val requestId: String) : DeeplinkAction

        /** `requestr://flow/execute?id=<flowId>` — run the automation graph. [flowId] is
         * carried through for diagnostics/logging even though the app currently has only one
         * (unnamed) flow graph, so any id just runs that one graph. */
        data class ExecuteFlow(val flowId: String?) : DeeplinkAction

        /** `requestr://note/create?title=<title>` — create a new note with this title. */
        data class CreateNote(val title: String) : DeeplinkAction
    }

    /** Route constants, kept in sync with `Screen.*.route` in MainActivity.kt. */
    object Routes {
        const val HOME = "home"
        const val INTERCEPT = "intercept"
        const val APIS = "apis"
        const val TOOLS = "tools"
        const val NOTES = "notes"
        const val FLOW = "flow"
        const val MUSIC = "music"
        const val SETTINGS = "settings"
        const val CHAT = "chat"
        const val SAVED_APIS = "saved_apis"
    }

    /** Parses [uri] into a [DeeplinkAction], or null if the host/path is not recognized. */
    fun parse(uri: Uri): DeeplinkAction? {
        val host = uri.host
        if (host.isNullOrBlank()) {
            // Path-form fallback: e.g. requestr:///notes — authority is empty, route lives in
            // the path instead. Navigation-only; the host-form actions below all require a
            // real host to disambiguate action vs plain route (e.g. "flow" vs "flow/execute").
            val path = uri.path?.trim('/')?.takeIf { it.isNotBlank() } ?: return null
            val firstSegment = path.split("/").first()
            return routeForSegment(firstSegment)?.let { DeeplinkAction.Navigate(it) }
        }

        val segments = uri.pathSegments

        return when (host) {
            // "intercept/toggle" is intentionally NOT a distinct action here: driving
            // start/stop needs the Activity-scoped VPN consent launcher that only
            // `rememberCaptureController` (used inside InterceptScreen) can provide. A
            // second top-level CaptureController would duplicate PreflightController's
            // gate and CaptureMethodWatcher's method-switch observer and could race the
            // real one. Per plan, this route is navigation-only; the "toggle" row was
            // removed from the Settings deep-link list to match.
            "intercept" -> DeeplinkAction.Navigate(Routes.INTERCEPT)
            "api" -> {
                val id = uri.getQueryParameter("id")
                if (segments.contains("run") && !id.isNullOrBlank()) {
                    DeeplinkAction.RunApi(id)
                } else {
                    DeeplinkAction.Navigate(Routes.APIS)
                }
            }
            "flow" -> {
                if (segments.contains("execute")) {
                    DeeplinkAction.ExecuteFlow(uri.getQueryParameter("id"))
                } else {
                    DeeplinkAction.Navigate(Routes.FLOW)
                }
            }
            "note" -> {
                val title = uri.getQueryParameter("title")
                if (segments.contains("create") && !title.isNullOrBlank()) {
                    DeeplinkAction.CreateNote(title)
                } else {
                    DeeplinkAction.Navigate(Routes.NOTES)
                }
            }
            "apis" -> DeeplinkAction.Navigate(Routes.APIS)
            "notes" -> DeeplinkAction.Navigate(Routes.NOTES)
            "tools" -> DeeplinkAction.Navigate(Routes.TOOLS)
            "music" -> DeeplinkAction.Navigate(Routes.MUSIC)
            "settings" -> DeeplinkAction.Navigate(Routes.SETTINGS)
            "chat" -> DeeplinkAction.Navigate(Routes.CHAT)
            "saved" -> DeeplinkAction.Navigate(Routes.SAVED_APIS)
            "home" -> DeeplinkAction.Navigate(Routes.HOME)
            else -> routeForSegment(host)?.let { DeeplinkAction.Navigate(it) }
        }
    }

    private fun routeForSegment(segment: String): String? = when (segment) {
        "flow" -> Routes.FLOW
        "intercept" -> Routes.INTERCEPT
        "apis", "api" -> Routes.APIS
        "tools" -> Routes.TOOLS
        "notes" -> Routes.NOTES
        "music" -> Routes.MUSIC
        "settings" -> Routes.SETTINGS
        "saved" -> Routes.SAVED_APIS
        "home" -> Routes.HOME
        else -> null
    }
}
