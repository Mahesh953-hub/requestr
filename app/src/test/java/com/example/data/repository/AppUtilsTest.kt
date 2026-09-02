package com.example.data.repository

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUtilsTest {

    @Test
    fun `hasPermission is true when the checker reports granted`() {
        val result = AppUtils.hasPermission("com.example.app", "some.permission") { _, _ ->
            PackageManager.PERMISSION_GRANTED
        }
        assertTrue(result)
    }

    @Test
    fun `hasPermission is false when the checker reports denied`() {
        val result = AppUtils.hasPermission("com.example.app", "some.permission") { _, _ ->
            PackageManager.PERMISSION_DENIED
        }
        assertFalse(result)
    }

    @Test
    fun `hasPermission fails open when the checker throws`() {
        val result = AppUtils.hasPermission("com.example.app", "some.permission") { _, _ ->
            throw IllegalStateException("PackageManager quirk")
        }
        assertTrue(result)
    }

    @Test
    fun `hasInternetPermission delegates the INTERNET permission string`() {
        var seenPermission: String? = null
        AppUtils.hasInternetPermission("com.example.app") { permission, _ ->
            seenPermission = permission
            PackageManager.PERMISSION_GRANTED
        }
        assertTrue(seenPermission == android.Manifest.permission.INTERNET)
    }

    @Test
    fun `hasInternetPermission is false for a package lacking the permission`() {
        val result = AppUtils.hasInternetPermission("com.example.offline") { _, packageName ->
            if (packageName == "com.example.offline") PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
        }
        assertFalse(result)
    }
}
