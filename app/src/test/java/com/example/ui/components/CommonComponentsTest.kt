package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit fix (StatusCodeBadge "0" defect): unit tests for [statusCodeBadgeText], the pure
 * formatting decision behind [StatusCodeBadge]. Pure function, no Android/Compose dependency,
 * so no Robolectric runner is needed here — mirrors the pattern in
 * [com.example.data.models.Http3StateTest] for [http3RowState].
 */
class CommonComponentsTest {

    @Test
    fun `a known status with no message renders the bare numeric code`() {
        assertEquals(
            "200",
            statusCodeBadgeText(
                statusCode = 200,
                statusMessage = null,
                hasKnownStatus = true,
                unknownStatusLabel = "—",
            ),
        )
    }

    @Test
    fun `a known status with a message renders code and message together`() {
        assertEquals(
            "404 Not Found",
            statusCodeBadgeText(
                statusCode = 404,
                statusMessage = "Not Found",
                hasKnownStatus = true,
                unknownStatusLabel = "—",
            ),
        )
    }

    @Test
    fun `a blank message is treated the same as no message`() {
        assertEquals(
            "204",
            statusCodeBadgeText(
                statusCode = 204,
                statusMessage = "   ",
                hasKnownStatus = true,
                unknownStatusLabel = "—",
            ),
        )
    }

    @Test
    fun `an unknown status never renders the numeric placeholder, even when it is nonzero`() {
        // Regression guard: hasKnownStatus is the only signal this function may use. A caller
        // that (incorrectly) leaves a nonzero statusCode on a statusless row must still get the
        // placeholder label, not the number -- there is no magic-number fallback inside this
        // function itself.
        assertEquals(
            "OBSERVED",
            statusCodeBadgeText(
                statusCode = 7,
                statusMessage = "irrelevant",
                hasKnownStatus = false,
                unknownStatusLabel = "OBSERVED",
            ),
        )
    }

    @Test
    fun `an unknown status with the default em-dash label never shows a fabricated zero`() {
        assertEquals(
            "—",
            statusCodeBadgeText(
                statusCode = 0,
                statusMessage = null,
                hasKnownStatus = false,
                unknownStatusLabel = "—",
            ),
        )
    }

    @Test
    fun `an unknown status ignores statusMessage entirely`() {
        // The pre-existing ApiTestr "Network Failure" caller passes hasKnownStatus = true (its
        // default, unchanged) so this branch does not apply to it; a caller that DOES pass
        // hasKnownStatus = false must get only the placeholder, since statusMessage in that case
        // describes a status this row never actually had.
        assertEquals(
            "DETECTED",
            statusCodeBadgeText(
                statusCode = 0,
                statusMessage = "should not appear",
                hasKnownStatus = false,
                unknownStatusLabel = "DETECTED",
            ),
        )
    }
}
