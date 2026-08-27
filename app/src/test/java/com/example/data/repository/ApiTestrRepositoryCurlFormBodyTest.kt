package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code-review fix (§2.9 follow-up) — [ApiTestrRepository.generateCodeSnippet]'s cURL branch used
 * to always serialize `bodyContent` regardless of [RequestBodyType], so a FORM/MULTIPART
 * request's exported command silently omitted every `formData` field the user actually
 * configured (which [ApiTestrRepository.executeRequest] sends via real
 * `FormBody`/`MultipartBody`, see [ApiTestrRepositoryFormBodyTest]). Worse, MULTIPART hand-set a
 * `Content-Type: multipart/form-data` header with no `boundary=` parameter and paired it with
 * `-d`, producing a command no server could parse. These lock the fix: FORM fields become
 * `--data-urlencode 'key=value'`, MULTIPART fields become `--form 'key=value'` with no
 * hand-written multipart Content-Type header at all (curl derives its own valid boundary),
 * disabled fields are skipped, and [ShellEscape.singleQuote] is still applied to every value.
 */
class ApiTestrRepositoryCurlFormBodyTest {

    @Test
    fun `FORM export uses --data-urlencode per enabled field and skips disabled ones`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.FORM,
            formData = listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )

        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")

        assertTrue(curl.contains("--data-urlencode 'a=1'"))
        assertTrue(curl.contains("--data-urlencode 'b=two words'"))
        assertFalse(curl.contains("c=disabled"))
        // The old bug always rendered the whole request body via a single `-d '...'` --
        // confirm that's gone for FORM.
        assertFalse(curl.contains("-d '"))
    }

    @Test
    fun `MULTIPART export uses --form per enabled field and never hand-sets a boundary-less Content-Type`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.MULTIPART,
            formData = listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )

        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")

        assertTrue(curl.contains("--form 'field1=value1'"))
        assertFalse(curl.contains("field2"))
        assertFalse(curl.contains("-d '"))
        // The core of the bug: no hand-written multipart/form-data Content-Type header (it has
        // no boundary=, which makes the command unparseable by any server) -- curl's own --form
        // generates a real one.
        assertFalse(curl.contains("Content-Type", ignoreCase = true))
        assertFalse(curl.contains("multipart/form-data"))
    }

    @Test
    fun `MULTIPART export still shows a user-set non-multipart Content-Type header untouched`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = listOf(KeyValueItem(key = "Accept", value = "application/json", isEnabled = true)),
            bodyType = RequestBodyType.MULTIPART,
            formData = listOf(KeyValueItem(key = "field1", value = "value1", isEnabled = true))
        )

        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")

        // Unrelated headers the user configured must still come through -- only the
        // auto-added multipart Content-Type is special-cased away.
        assertTrue(curl.contains("-H 'Accept: application/json'"))
        assertFalse(curl.contains("Content-Type", ignoreCase = true))
    }

    @Test
    fun `FORM field key or value with shell-special characters is still safely single-quoted`() {
        val dangerous = "c`d\$(e)\"f"
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.FORM,
            formData = listOf(KeyValueItem(key = "a'b", value = dangerous, isEnabled = true))
        )

        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")

        assertTrue(curl.contains("--data-urlencode ${ShellEscape.singleQuote("a'b=$dangerous")}"))
    }

    @Test
    fun `MULTIPART field key or value with shell-special characters is still safely single-quoted`() {
        val dangerous = "c`d\$(e)\"f"
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.MULTIPART,
            formData = listOf(KeyValueItem(key = "a'b", value = dangerous, isEnabled = true))
        )

        val curl = ApiTestrRepository.generateCodeSnippet(item, "cURL")

        assertTrue(curl.contains("--form ${ShellEscape.singleQuote("a'b=$dangerous")}"))
    }
}
