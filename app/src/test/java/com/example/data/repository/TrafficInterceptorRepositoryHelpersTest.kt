package com.example.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficInterceptorRepositoryHelpersTest {

    @Test
    fun `blank label is unusable`() {
        assertTrue(isUnusableLabel("", "com.example.app"))
        assertTrue(isUnusableLabel("   ", "com.example.app"))
    }

    @Test
    fun `label equal to package name is unusable`() {
        assertTrue(isUnusableLabel("com.example.app", "com.example.app"))
    }

    @Test
    fun `a real display label is usable`() {
        assertFalse(isUnusableLabel("Chrome", "com.android.chrome"))
        assertFalse(isUnusableLabel("com.example.app Beta", "com.example.app"))
    }
}
