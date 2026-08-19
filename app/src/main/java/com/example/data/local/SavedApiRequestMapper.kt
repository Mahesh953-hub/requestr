package com.example.data.local

import com.example.data.models.ApiRequestItem
import com.example.data.models.AuthType
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
    bodyType = runCatching { RequestBodyType.valueOf(bodyType) }.getOrDefault(RequestBodyType.NONE),
    bodyContent = bodyContent,
    formData = formData.map { it.toKeyValueItem() },
    preScript = preScript,
    postScript = postScript,
    isFavorite = isFavorite,
    updatedAt = updatedAt,
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
    bodyType = bodyType.name,
    bodyContent = bodyContent,
    formData = formData.map { it.toSavedKeyValue() },
    preScript = preScript,
    postScript = postScript,
    isFavorite = isFavorite,
    updatedAt = updatedAt,
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
