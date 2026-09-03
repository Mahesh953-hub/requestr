package com.example.data.persistence

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Defect G (S0 gaps audit) coverage: [TeeRolloutFlag] must default to enabled and support
 * rollback, mirroring [DbDriverFlag]'s existing rollout contract.
 */
@RunWith(RobolectricTestRunner::class)
class TeeRolloutFlagTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `defaults to enabled`() {
        val prefs = context.getSharedPreferences("tee_rollout_test_default", android.content.Context.MODE_PRIVATE)
        val flag = TeeRolloutFlag.forTest(prefs)
        assertTrue(flag.isEnabled)
    }

    @Test
    fun `setEnabled false disables and persists`() {
        val prefs = context.getSharedPreferences("tee_rollout_test_disable", android.content.Context.MODE_PRIVATE)
        val flag = TeeRolloutFlag.forTest(prefs)

        flag.setEnabled(false)

        assertFalse(flag.isEnabled)
        assertFalse(TeeRolloutFlag.forTest(prefs).isEnabled)
    }

    @Test
    fun `setEnabled true re-enables after rollback`() {
        val prefs = context.getSharedPreferences("tee_rollout_test_reenable", android.content.Context.MODE_PRIVATE)
        val flag = TeeRolloutFlag.forTest(prefs)

        flag.setEnabled(false)
        flag.setEnabled(true)

        assertTrue(flag.isEnabled)
    }

    @Test
    fun `get returns the same singleton instance for the same context`() {
        val a = TeeRolloutFlag.get(context)
        val b = TeeRolloutFlag.get(context)
        assertTrue(a === b)
    }
}
