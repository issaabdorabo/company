package com.example.data.cloud

import org.json.JSONObject

/** HTTP error returned by a Google API request. */
class GoogleApiHttpException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

/** Extracts Google's human-readable error message, falling back to the supplied text. */
internal fun parseGoogleApiError(
    statusCode: Int,
    body: String,
    fallback: String
): String {
    val detail = runCatching {
        val root = JSONObject(body)
        root.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()
    return buildString {
        append(detail ?: fallback)
        if (statusCode > 0) {
            append(" (HTTP ")
            append(statusCode)
            append(')')
        }
    }
}
