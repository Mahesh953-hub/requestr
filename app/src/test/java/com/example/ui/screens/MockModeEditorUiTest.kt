package com.example.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.example.data.models.KeyValueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for the device smoke-test finding: on the Mock tab, the "Mock Body"
 * label and its BasicTextField never rendered because [MockModeEditor]'s outer `Column` was an
 * unbounded `Modifier.fillMaxSize()` and the "Mock Headers" `KeyValueEditorTable` (whose
 * internal `LazyColumn` has no height bound of its own) came before it, so the table claimed
 * 100% of the available height and starved everything below it to zero.
 *
 * Fix under test (see MockModeEditor in ApiTestrScreen.kt): the outer Column now scrolls
 * (`verticalScroll`), and the Mock Headers table call is wrapped in a `Box(Modifier.heightIn(max
 * = 280.dp))` so its LazyColumn has a bounded height to fill instead of devouring the rest of
 * the editor. This is the first Compose UI test in this module (uses the existing Robolectric +
 * `androidx.compose.ui.test.junit4` infra already wired in app/build.gradle.kts —
 * `isIncludeAndroidResources = true` plus `testImplementation(libs.androidx.compose.ui.test.junit4)`
 * — no new dependency was needed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MockModeEditorUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `mock body label and field render and accept input, alongside status code, status message and delay fields`() {
        var capturedBody: String? = null
        var capturedStatusCode: Int? = null
        var capturedStatusMessage: String? = null
        var capturedDelay: Long? = null
        var capturedHeaders: List<KeyValueItem>? = null

        composeTestRule.setContent {
            MaterialTheme {
                MockModeEditor(
                    mockEnabled = true,
                    mockStatusCode = 200,
                    mockStatusMessage = "OK",
                    mockHeaders = listOf(KeyValueItem(key = "X-Mock", value = "1")),
                    mockBody = "",
                    mockDelayMs = 0L,
                    isDark = false,
                    onMockEnabledChanged = {},
                    onMockStatusCodeChanged = { capturedStatusCode = it },
                    onMockStatusMessageChanged = { capturedStatusMessage = it },
                    onMockHeadersChanged = { capturedHeaders = it },
                    onMockBodyChanged = { capturedBody = it },
                    onMockDelayMsChanged = { capturedDelay = it }
                )
            }
        }

        // Regression assertion: before the fix, this label and field were never laid out
        // (zero height), so this node would not exist in the semantics tree at all.
        composeTestRule.onNodeWithText("Mock Body").assertExists()
        composeTestRule.onNodeWithTag("mockBodyField").assertExists()

        composeTestRule.onNodeWithTag("mockBodyField").performTextInput("{\"ok\":true}")
        assertEquals("{\"ok\":true}", capturedBody)

        // The fields above the (previously height-starving) Mock Headers table must remain
        // reachable and interactive after the layout change.
        composeTestRule.onNodeWithTag("mockStatusCodeField").performTextInput("1")
        assertTrue(capturedStatusCode != null)

        composeTestRule.onNodeWithTag("mockStatusMessageField").performTextInput("!")
        // performTextInput inserts at the field's default cursor position (start of the
        // existing text for a freshly-focused field, per Compose's test framework), hence
        // "!OK" rather than "OK!" -- the point of this assertion is just that the callback
        // fired at all with the new value, not the exact cursor placement semantics.
        assertEquals("!OK", capturedStatusMessage)

        composeTestRule.onNodeWithTag("mockDelayMsField").performTextInput("1")
        assertTrue(capturedDelay != null)

        // Mock Headers table itself must still exist (unrelated to the fix, but confirms the
        // bounded-height Box wrapper didn't silently break it).
        composeTestRule.onNodeWithText("Mock Headers").assertExists()
        assertTrue(capturedHeaders == null)
    }
}
