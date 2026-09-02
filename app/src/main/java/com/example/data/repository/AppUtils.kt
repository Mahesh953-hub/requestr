package com.example.data.repository

import android.content.pm.PackageManager

/**
 * Master plan §3 LOW-ADD (C8) — pure, unit-testable helpers backing the VPN app-picker's
 * `INTERNET`-permission prefilter.
 *
 * An app that never declared `android.permission.INTERNET` cannot originate any network
 * traffic, so it has nothing for a traffic interceptor to ever capture — showing it in the
 * VPN app-picker just adds noise. [TrafficInterceptorRepository.loadInstalledApps] uses this
 * object to decide which installed apps to keep.
 *
 * The permission check itself is expressed against an injected [checkPermissionResult]
 * function rather than a live [android.content.pm.PackageManager] so it can be exercised in a
 * plain JVM unit test without Robolectric or any Android framework dependency — see
 * `AppUtilsTest`.
 */
object AppUtils {

    /**
     * True if [packageName] holds [permission], per [checkPermissionResult] — a caller-supplied
     * stand-in for [android.content.pm.PackageManager.checkPermission], which returns
     * [PackageManager.PERMISSION_GRANTED] or [PackageManager.PERMISSION_DENIED] for
     * `(permission, packageName)`.
     *
     * Fails **open** (returns `true`) if [checkPermissionResult] throws, so a PackageManager
     * quirk on some OEM build never silently hides a real app from the picker — the safer
     * failure mode for a "reduce noise" convenience filter is to show too much, not to hide an
     * app the user actually wanted to intercept.
     */
    fun hasPermission(
        packageName: String,
        permission: String,
        checkPermissionResult: (permission: String, packageName: String) -> Int
    ): Boolean =
        runCatching { checkPermissionResult(permission, packageName) == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(true)

    /** [hasPermission] specialised to `android.permission.INTERNET`. */
    fun hasInternetPermission(
        packageName: String,
        checkPermissionResult: (permission: String, packageName: String) -> Int
    ): Boolean = hasPermission(packageName, android.Manifest.permission.INTERNET, checkPermissionResult)
}
