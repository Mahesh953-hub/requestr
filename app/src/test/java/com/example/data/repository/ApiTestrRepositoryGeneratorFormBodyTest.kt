package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Code-review fix (follow-up to the cURL-only §2.9 fix locked by
 * [ApiTestrRepositoryCurlFormBodyTest]) — the user asked for the same FORM/MULTIPART
 * formData-vs-bodyContent mismatch to be fixed in the remaining four [ApiTestrRepository
 * .generateCodeSnippet] branches too, each using that language's own idiomatic body-building
 * mechanism (matching what [ApiTestrRepository.executeRequest] actually sends via real
 * `FormBody`/`MultipartBody`) instead of a hand-rolled string, and per-language string-literal
 * escaping (never [ShellEscape.singleQuote], which is POSIX-shell-specific and wrong for any of
 * these) for every field value.
 *
 * Also locks the separate, non-FORM/MULTIPART-specific bugs the same review flagged:
 *  - Kotlin - OkHttp: the branch used to reference an undefined `body` variable for every
 *    non-GET request, so the emitted snippet didn't compile at all.
 *  - Go - net/http: the branch used to never send a body, for ANY body type (including
 *    JSON/RAW/GRAPHQL, not just FORM/MULTIPART).
 */
class ApiTestrRepositoryGeneratorFormBodyTest {

    private val dangerous = "a'b\"c\\d\$e\nf"

    private fun itemWithFormData(bodyType: RequestBodyType, formData: List<KeyValueItem>, method: HttpMethod = HttpMethod.POST) =
        ApiRequestItem(
            method = method,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = bodyType,
            formData = formData
        )

    // ---------------------------------------------------------------- Python - Requests

    @Test
    fun `Python FORM export builds a data dict from formData and skips disabled fields`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )
        val python = ApiTestrRepository.generateCodeSnippet(item, "Python - Requests")

        assertTrue(python.contains("data = {"))
        assertTrue(python.contains("\"a\": \"1\""))
        assertTrue(python.contains("\"b\": \"two words\""))
        assertFalse(python.contains("disabled"))
        assertTrue(python.contains("data=data"))
    }

    @Test
    fun `Python MULTIPART export builds a files dict of (None, value) tuples and drops the boundary-less Content-Type`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )
        val python = ApiTestrRepository.generateCodeSnippet(item, "Python - Requests")

        assertTrue(python.contains("files = {"))
        assertTrue(python.contains("\"field1\": (None, \"value1\")"))
        assertFalse(python.contains("field2"))
        assertTrue(python.contains("files=files"))
        assertFalse(python.contains("Content-Type", ignoreCase = true))
        assertFalse(python.contains("multipart/form-data"))
    }

    @Test
    fun `Python FORM field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val python = ApiTestrRepository.generateCodeSnippet(item, "Python - Requests")

        assertTrue(python.contains("\"k\": ${SourceLiteralEscape.doubleQuoted(dangerous)}"))
    }

    @Test
    fun `Python MULTIPART field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val python = ApiTestrRepository.generateCodeSnippet(item, "Python - Requests")

        assertTrue(python.contains("\"k\": (None, ${SourceLiteralEscape.doubleQuoted(dangerous)})"))
    }

    // ---------------------------------------------------------------- JavaScript - fetch

    @Test
    fun `JS FORM export builds a URLSearchParams body from formData and skips disabled fields`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )
        val js = ApiTestrRepository.generateCodeSnippet(item, "JavaScript - fetch")

        assertTrue(js.contains("new URLSearchParams({"))
        assertTrue(js.contains("'a': '1'"))
        assertTrue(js.contains("'b': 'two words'"))
        assertFalse(js.contains("disabled"))
        assertTrue(js.contains("body: body"))
        assertFalse(js.contains("JSON.stringify"))
    }

    @Test
    fun `JS MULTIPART export builds a FormData body via append calls and drops the boundary-less Content-Type`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )
        val js = ApiTestrRepository.generateCodeSnippet(item, "JavaScript - fetch")

        assertTrue(js.contains("new FormData()"))
        assertTrue(js.contains("body.append('field1', 'value1')"))
        assertFalse(js.contains("field2"))
        assertTrue(js.contains("body: body"))
        assertFalse(js.contains("Content-Type", ignoreCase = true))
        assertFalse(js.contains("multipart/form-data"))
    }

    @Test
    fun `JS FORM field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val js = ApiTestrRepository.generateCodeSnippet(item, "JavaScript - fetch")

        assertTrue(js.contains("'k': ${SourceLiteralEscape.jsQuoted(dangerous)}"))
    }

    @Test
    fun `JS MULTIPART field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val js = ApiTestrRepository.generateCodeSnippet(item, "JavaScript - fetch")

        assertTrue(js.contains("body.append(${SourceLiteralEscape.jsQuoted("k")}, ${SourceLiteralEscape.jsQuoted(dangerous)})"))
    }

    // ---------------------------------------------------------------- Kotlin - OkHttp

    @Test
    fun `Kotlin non-GET NONE body declares a body val instead of referencing an undefined one`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.NONE
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains("val body ="))
        assertTrue(kotlin.contains(".method(\"POST\", body)"))
    }

    @Test
    fun `Kotlin JSON body declares a body val built via toRequestBody`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.JSON,
            bodyContent = "{\"x\":1}"
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains("val body = "))
        assertTrue(kotlin.contains(".toRequestBody("))
        assertTrue(kotlin.contains("{\\\"x\\\":1}"))
        assertTrue(kotlin.contains(".method(\"POST\", body)"))
    }

    @Test
    fun `Kotlin GET request still uses get() and never declares an unused body val`() {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.NONE
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains(".get()"))
        assertFalse(kotlin.contains("val body ="))
    }

    @Test
    fun `Kotlin FORM export builds a body val via FormBody Builder and skips disabled fields`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains("val body = FormBody.Builder()"))
        assertTrue(kotlin.contains(".add(\"a\", \"1\")"))
        assertTrue(kotlin.contains(".add(\"b\", \"two words\")"))
        assertFalse(kotlin.contains("disabled"))
        assertTrue(kotlin.contains(".method(\"POST\", body)"))
    }

    @Test
    fun `Kotlin MULTIPART export builds a body val via MultipartBody Builder and drops the boundary-less Content-Type`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains("val body = MultipartBody.Builder()"))
        assertTrue(kotlin.contains(".setType(MultipartBody.FORM)"))
        assertTrue(kotlin.contains(".addFormDataPart(\"field1\", \"value1\")"))
        assertFalse(kotlin.contains("field2"))
        assertFalse(kotlin.contains("Content-Type", ignoreCase = true))
        assertFalse(kotlin.contains("multipart/form-data"))
    }

    @Test
    fun `Kotlin FORM field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains(".add(${SourceLiteralEscape.kotlinQuoted("k")}, ${SourceLiteralEscape.kotlinQuoted(dangerous)})"))
    }

    @Test
    fun `Kotlin MULTIPART field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val kotlin = ApiTestrRepository.generateCodeSnippet(item, "Kotlin - OkHttp")

        assertTrue(kotlin.contains(".addFormDataPart(${SourceLiteralEscape.kotlinQuoted("k")}, ${SourceLiteralEscape.kotlinQuoted(dangerous)})"))
    }

    // ---------------------------------------------------------------- Go - net/http

    @Test
    fun `Go NONE body still sends a request even though it has no body`() {
        val item = ApiRequestItem(
            method = HttpMethod.GET,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.NONE
        )
        val go = ApiTestrRepository.generateCodeSnippet(item, "Go")

        assertTrue(go.contains("http.NewRequest(\"GET\", url, nil)"))
    }

    @Test
    fun `Go JSON RAW GRAPHQL body is now actually sent as a byte reader instead of nil`() {
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = "https://example.com/api",
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.JSON,
            bodyContent = "{\"x\":1}"
        )
        val go = ApiTestrRepository.generateCodeSnippet(item, "Go")

        assertTrue(go.contains("bytes.NewReader(payload)"))
        assertTrue(go.contains("{\\\"x\\\":1}"))
        assertFalse(go.contains("http.NewRequest(\"POST\", urlStr, nil)"))
    }

    @Test
    fun `Go FORM export builds a url Values body from formData and skips disabled fields`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )
        val go = ApiTestrRepository.generateCodeSnippet(item, "Go")

        assertTrue(go.contains("form := url.Values{}"))
        assertTrue(go.contains("form.Set(\"a\", \"1\")"))
        assertTrue(go.contains("form.Set(\"b\", \"two words\")"))
        assertFalse(go.contains("disabled"))
        assertTrue(go.contains("strings.NewReader(form.Encode())"))
    }

    @Test
    fun `Go MULTIPART export builds a multipart Writer body and uses FormDataContentType instead of a literal`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )
        val go = ApiTestrRepository.generateCodeSnippet(item, "Go")

        assertTrue(go.contains("multipart.NewWriter(&buf)"))
        assertTrue(go.contains("writer.WriteField(\"field1\", \"value1\")"))
        assertFalse(go.contains("field2"))
        assertTrue(go.contains("writer.FormDataContentType()"))
        // The real Content-Type comes from the writer, not a hand-written literal string.
        assertFalse(go.contains("\"Content-Type\", \"multipart/form-data\""))
    }

    @Test
    fun `Go FORM field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.FORM,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val go = ApiTestrRepository.generateCodeSnippet(item, "Go")

        assertTrue(go.contains("form.Set(\"k\", ${SourceLiteralEscape.doubleQuoted(dangerous)})"))
    }

    @Test
    fun `Go MULTIPART field with a hostile value stays contained inside its own string literal`() {
        val item = itemWithFormData(
            RequestBodyType.MULTIPART,
            listOf(KeyValueItem(key = "k", value = dangerous, isEnabled = true))
        )
        val go = ApiTestrRepository.generateCodeSnippet(item, "Go")

        assertTrue(go.contains("writer.WriteField(\"k\", ${SourceLiteralEscape.doubleQuoted(dangerous)})"))
    }
}
