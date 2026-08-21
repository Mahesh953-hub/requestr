package com.example.data.analysis

import com.example.data.local.CapturedTrafficEntity

/**
 * API Discovery — aggregates captured traffic into host/endpoint patterns.
 *
 * Groups traffic by host, then by base path, then by method. Shows
 * request counts, status distributions, content types, and auth patterns.
 * This is the foundation for the Discovery screen (#45).
 */
object ApiDiscovery {

    data class EndpointPattern(
        val host: String,
        val pathPattern: String,
        val method: String,
        val requestCount: Int,
        val statusCodes: Map<Int, Int>,
        val contentTypes: Set<String>,
        val hasAuth: Boolean,
        val avgLatencyMs: Long,
    )

    data class HostSummary(
        val host: String,
        val totalRequests: Int,
        val endpoints: List<EndpointPattern>,
        val statusDistribution: Map<Int, Int>,
        val uniquePaths: Int,
        val firstSeen: Long,
        val lastSeen: Long,
    )

    /**
     * Aggregates a list of captured traffic entities into discovery patterns.
     * Groups by host → base path → method.
     */
    fun discover(entities: List<CapturedTrafficEntity>): List<HostSummary> {
        val byHost = entities.groupBy { it.host }
        return byHost.map { (host, hostEntities) ->
            val endpoints = discoverEndpoints(hostEntities)
            HostSummary(
                host = host,
                totalRequests = hostEntities.size,
                endpoints = endpoints,
                statusDistribution = hostEntities
                    .mapNotNull { it.statusCode }
                    .groupingBy { it }
                    .eachCount(),
                uniquePaths = hostEntities.map { normalizePath(it.path) }.distinct().size,
                firstSeen = hostEntities.minOf { it.timestamp },
                lastSeen = hostEntities.maxOf { it.timestamp },
            )
        }.sortedByDescending { it.totalRequests }
    }

    /** Groups a host's traffic into endpoint patterns. */
    private fun discoverEndpoints(entities: List<CapturedTrafficEntity>): List<EndpointPattern> {
        val byPathMethod = entities.groupBy { normalizePath(it.path) to it.method }
        return byPathMethod.map { (pathMethod, group) ->
            val (path, method) = pathMethod
            EndpointPattern(
                host = group.first().host,
                pathPattern = path,
                method = method,
                requestCount = group.size,
                statusCodes = group.mapNotNull { it.statusCode }.groupingBy { it }.eachCount(),
                contentTypes = group.mapNotNull {
                    it.responseHeaders["content-type"]?.substringBefore(";")?.trim()
                }.toSet(),
                hasAuth = group.any { hasAuthHeaders(it) },
                avgLatencyMs = if (group.any { it.latencyMs > 0 }) {
                    group.filter { it.latencyMs > 0 }.map { it.latencyMs }.average().toLong()
                } else 0,
            )
        }.sortedByDescending { it.requestCount }
    }

    /**
     * Normalizes a path by replacing numeric segments and UUIDs with placeholders.
     * /api/users/123 → /api/users/{id}
     * /api/items/550e8400-e29b-41d4-a716-446655440000 → /api/items/{uuid}
     */
    fun normalizePath(path: String): String {
        return path
            .replace(Regex("/\\d+"), "/{id}")
            .replace(Regex("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE), "/{uuid}")
            .replace(Regex("/[0-9a-f]{24,}", RegexOption.IGNORE_CASE), "/{token}")
    }

    /** True if the request has authentication headers. */
    private fun hasAuthHeaders(entity: CapturedTrafficEntity): Boolean {
        return entity.requestHeaders.keys.any {
            it.lowercase() in setOf("authorization", "x-api-key", "api-key", "x-auth-token", "cookie", "x-access-token")
        }
    }
}
