package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.ApiResponseData
import com.example.data.models.Assertion
import com.example.data.models.AssertionOperator
import com.example.data.models.AssertionTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Master plan §2.12 — [ApiCollectionsRepository.runCollectionTests]'s halt-on-failure execution
 * control. No Robolectric needed: [ApiCollectionsRepository.init] is never called here, so it
 * never touches Room, and the injectable `executor` seam avoids any real network I/O.
 *
 * [ApiCollectionsRepository] is a JVM-wide singleton `object`, so each test uses its own unique
 * folderId (unshared with any other test in the suite) and cleans up its own requests in
 * [tearDown], to avoid cross-test state leakage within the same test run.
 */
class ApiCollectionsRepositoryHaltOnFailureTest {

    private val createdIds = mutableListOf<String>()

    private fun statusAssertion(expected: String) = Assertion(
        target = AssertionTarget.STATUS_CODE,
        operator = AssertionOperator.EQUALS,
        expectedValue = expected,
        enabled = true,
    )

    /**
     * [ApiCollectionsRepository.saveRequest] always overwrites `updatedAt` with
     * `System.currentTimeMillis()` (irrespective of what's passed in), and [runCollectionTests]
     * runs candidates `sortedBy { it.updatedAt }`. A `Thread.sleep` between saves is what
     * actually guarantees execution order here -- passing an explicit `updatedAt` would be
     * silently discarded and, on a tie, sortedBy's stability would order candidates by their
     * position in `_requests` (which prepends on save, i.e. reversed insertion order), not save
     * order.
     */
    private fun saveTestable(id: String, folderId: String, expectedStatus: String): ApiRequestItem {
        val item = ApiRequestItem(
            id = id,
            name = "req-$id",
            folderId = folderId,
            assertions = listOf(statusAssertion(expectedStatus)),
        )
        ApiCollectionsRepository.saveRequest(item)
        createdIds.add(id)
        Thread.sleep(5)
        return item
    }

    @After
    fun tearDown() {
        createdIds.forEach { ApiCollectionsRepository.deleteRequest(it) }
        createdIds.clear()
    }

    @Test
    fun `haltOnFailure stops after the first failing request and marks the rest skipped`() = runBlocking {
        val folderId = "halt-test-folder-1"
        saveTestable("halt-1", folderId, expectedStatus = "200") // will pass
        saveTestable("halt-2", folderId, expectedStatus = "999") // will fail (real status is 200)
        saveTestable("halt-3", folderId, expectedStatus = "200") // would pass, but should be skipped

        val report = ApiCollectionsRepository.runCollectionTests(
            folderId = folderId,
            haltOnFailure = true,
            executor = { ApiResponseData(statusCode = 200, isSuccess = true, body = "") },
        )

        assertEquals(3, report.requestResults.size)
        val byId = report.requestResults.associateBy { it.request.id }

        assertFalse(byId["halt-1"]!!.requestFailed)
        assertTrue(byId["halt-1"]!!.results.all { it.passed })
        assertFalse(ApiCollectionsRepository.isSkippedResult(byId["halt-1"]!!))

        assertTrue(byId["halt-2"]!!.results.any { !it.passed })
        assertFalse(ApiCollectionsRepository.isSkippedResult(byId["halt-2"]!!))

        assertTrue(ApiCollectionsRepository.isSkippedResult(byId["halt-3"]!!))
        assertNull(byId["halt-3"]!!.response)
        assertFalse(byId["halt-3"]!!.requestFailed)
        assertTrue(byId["halt-3"]!!.results.isEmpty())
    }

    @Test
    fun `haltOnFailure stops after a request that fails to execute at all`() = runBlocking {
        val folderId = "halt-test-folder-2"
        saveTestable("halt-exec-1", folderId, expectedStatus = "200")
        saveTestable("halt-exec-2", folderId, expectedStatus = "200")

        var calls = 0
        val report = ApiCollectionsRepository.runCollectionTests(
            folderId = folderId,
            haltOnFailure = true,
            executor = {
                calls++
                if (calls == 1) null else ApiResponseData(statusCode = 200, isSuccess = true)
            },
        )

        val byId = report.requestResults.associateBy { it.request.id }
        assertTrue(byId["halt-exec-1"]!!.requestFailed)
        assertTrue(ApiCollectionsRepository.isSkippedResult(byId["halt-exec-2"]!!))
        assertEquals(1, calls) // the second request's executor should never have been invoked
    }

    @Test
    fun `default behavior (haltOnFailure=false) runs every candidate regardless of failures`() = runBlocking {
        val folderId = "halt-test-folder-3"
        saveTestable("nohalt-1", folderId, expectedStatus = "999") // fails
        saveTestable("nohalt-2", folderId, expectedStatus = "200") // passes

        val report = ApiCollectionsRepository.runCollectionTests(
            folderId = folderId,
            // haltOnFailure omitted -- must default to false and match pre-§2.12 behavior.
            executor = { ApiResponseData(statusCode = 200, isSuccess = true) },
        )

        assertEquals(2, report.requestResults.size)
        val byId = report.requestResults.associateBy { it.request.id }
        assertFalse(ApiCollectionsRepository.isSkippedResult(byId["nohalt-1"]!!))
        assertFalse(ApiCollectionsRepository.isSkippedResult(byId["nohalt-2"]!!))
        assertTrue(byId["nohalt-2"]!!.results.all { it.passed })
    }

    @Test
    fun `isSkippedResult is false for both a normal pass and a normal requestFailed result`() = runBlocking {
        val folderId = "halt-test-folder-4"
        saveTestable("skip-check-1", folderId, expectedStatus = "200")

        val passingReport = ApiCollectionsRepository.runCollectionTests(
            folderId = folderId,
            executor = { ApiResponseData(statusCode = 200, isSuccess = true) },
        )
        assertFalse(ApiCollectionsRepository.isSkippedResult(passingReport.requestResults.first()))

        val failingReport = ApiCollectionsRepository.runCollectionTests(
            folderId = folderId,
            executor = { null },
        )
        assertFalse(ApiCollectionsRepository.isSkippedResult(failingReport.requestResults.first()))
    }
}
