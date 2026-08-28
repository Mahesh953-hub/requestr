package com.example.data.local

import com.example.data.models.ApiKeyLocation
import com.example.data.models.ApiRequestItem
import com.example.data.models.Assertion
import com.example.data.models.AssertionOperator
import com.example.data.models.AssertionTarget
import com.example.data.models.AuthType
import com.example.data.models.ChainExtractionRule
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType

/**
 * Mapping between the Room-persisted [SavedApiRequestEntity] and the UI-facing
 * [ApiRequestItem]/[KeyValueItem] models (v3.1 fix A4). Kept in `data.local` so the DB schema
 * doesn't have to depend directly on `data.models` shapes changing.
 */
internal fun SavedApiRequestEntity.toApiRequestItem(): ApiRequestItem = ApiRequestItem(
    id = id,
    name = name,
    folderId = folderId,
    method = runCatching { HttpMethod.valueOf(method) }.getOrDefault(HttpMethod.GET),
    url = url,
    queryParams = queryParams.map { it.toKeyValueItem() },
    headers = headers.map { it.toKeyValueItem() },
    authType = runCatching { AuthType.valueOf(authType) }.getOrDefault(AuthType.NONE),
    bearerToken = bearerToken,
    basicUsername = basicUsername,
    basicPassword = basicPassword,
    apiKeyHeader = apiKeyHeader,
    apiKeyValue = apiKeyValue,
    apiKeyLocation = runCatching { ApiKeyLocation.valueOf(apiKeyLocation) }.getOrDefault(ApiKeyLocation.HEADER),
    bodyType = runCatching { RequestBodyType.valueOf(bodyType) }.getOrDefault(RequestBodyType.NONE),
    bodyContent = bodyContent,
    formData = formData.map { it.toKeyValueItem() },
    preScript = preScript,
    postScript = postScript,
    isFavorite = isFavorite,
    updatedAt = updatedAt,
    chainExtractions = chainExtractions.map { it.toChainExtractionRule() },
    assertions = assertions.map { it.toAssertion() },
    mockEnabled = mockEnabled,
    mockStatusCode = mockStatusCode,
    mockStatusMessage = mockStatusMessage,
    mockHeaders = mockHeaders.map { it.toKeyValueItem() },
    mockBody = mockBody,
    mockDelayMs = mockDelayMs,
    pollingEnabled = pollingEnabled,
    pollingIntervalMs = pollingIntervalMs,
    pollingMaxAttempts = pollingMaxAttempts,
    pollingLocationHeader = pollingLocationHeader,
    pollingAllowCrossOriginRedirect = pollingAllowCrossOriginRedirect,
)

internal fun ApiRequestItem.toEntity(): SavedApiRequestEntity = SavedApiRequestEntity(
    id = id,
    name = name,
    folderId = folderId,
    method = method.name,
    url = url,
    queryParams = queryParams.map { it.toSavedKeyValue() },
    headers = headers.map { it.toSavedKeyValue() },
    authType = authType.name,
    bearerToken = bearerToken,
    basicUsername = basicUsername,
    basicPassword = basicPassword,
    apiKeyHeader = apiKeyHeader,
    apiKeyValue = apiKeyValue,
    apiKeyLocation = apiKeyLocation.name,
    bodyType = bodyType.name,
    bodyContent = bodyContent,
    formData = formData.map { it.toSavedKeyValue() },
    preScript = preScript,
    postScript = postScript,
    isFavorite = isFavorite,
    updatedAt = updatedAt,
    chainExtractions = chainExtractions.map { it.toSavedChainExtractionRule() },
    assertions = assertions.map { it.toSavedAssertion() },
    mockEnabled = mockEnabled,
    mockStatusCode = mockStatusCode,
    mockStatusMessage = mockStatusMessage,
    mockHeaders = mockHeaders.map { it.toSavedKeyValue() },
    mockBody = mockBody,
    mockDelayMs = mockDelayMs,
    pollingEnabled = pollingEnabled,
    pollingIntervalMs = pollingIntervalMs,
    pollingMaxAttempts = pollingMaxAttempts,
    pollingLocationHeader = pollingLocationHeader,
    pollingAllowCrossOriginRedirect = pollingAllowCrossOriginRedirect,
)

private fun SavedKeyValue.toKeyValueItem(): KeyValueItem = KeyValueItem(
    id = id,
    key = key,
    value = value,
    isEnabled = isEnabled,
    description = description,
)

private fun KeyValueItem.toSavedKeyValue(): SavedKeyValue = SavedKeyValue(
    id = id,
    key = key,
    value = value,
    isEnabled = isEnabled,
    description = description,
)

private fun SavedChainExtractionRule.toChainExtractionRule(): ChainExtractionRule = ChainExtractionRule(
    id = id,
    jsonPath = jsonPath,
    variableName = variableName,
    enabled = enabled,
)

private fun ChainExtractionRule.toSavedChainExtractionRule(): SavedChainExtractionRule = SavedChainExtractionRule(
    id = id,
    jsonPath = jsonPath,
    variableName = variableName,
    enabled = enabled,
)

private fun SavedAssertion.toAssertion(): Assertion = Assertion(
    id = id,
    target = runCatching { AssertionTarget.valueOf(target) }.getOrDefault(AssertionTarget.STATUS_CODE),
    targetKey = targetKey,
    operator = runCatching { AssertionOperator.valueOf(operator) }.getOrDefault(AssertionOperator.EQUALS),
    expectedValue = expectedValue,
    enabled = enabled,
)

private fun Assertion.toSavedAssertion(): SavedAssertion = SavedAssertion(
    id = id,
    target = target.name,
    targetKey = targetKey,
    operator = operator.name,
    expectedValue = expectedValue,
    enabled = enabled,
)
