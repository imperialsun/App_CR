package com.demeter.speech.core

fun resolveBackendRequestUrl(baseUrl: String, path: String): String {
    val normalizedBase = baseUrl.trim().trimEnd('/')
    val normalizedPath = path.trim()

    val pathWithoutApiPrefix = if (
        normalizedBase.endsWith("/api/v1") &&
        normalizedPath.startsWith("/api/v1")
    ) {
        normalizedPath.removePrefix("/api/v1")
    } else {
        normalizedPath
    }

    val suffix = when {
        pathWithoutApiPrefix.isBlank() -> ""
        pathWithoutApiPrefix.startsWith("/") -> pathWithoutApiPrefix
        else -> "/$pathWithoutApiPrefix"
    }

    return normalizedBase + suffix
}
