package com.tokyoxpa3.androidproxy.integration

object PocketBridgeShareNames {
    private const val MAX_FILE_NAME_LENGTH = 180

    fun sanitizeFileName(input: String?, fallback: String = "shared-item"): String {
        val cleaned = input.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .replace('/', '_')
            .replace('\\\\', '_')
            .trim()
            .trim('.')
            .ifBlank { fallback }

        if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned

        val dot = cleaned.lastIndexOf('.')
        if (dot <= 0 || dot >= cleaned.lastIndex) {
            return cleaned.take(MAX_FILE_NAME_LENGTH)
        }

        val extension = cleaned.substring(dot).take(24)
        val baseLength = (MAX_FILE_NAME_LENGTH - extension.length).coerceAtLeast(1)
        return cleaned.substring(0, dot).take(baseLength) + extension
    }

    fun nextAvailableName(desired: String, exists: (String) -> Boolean): String {
        val safe = sanitizeFileName(desired)
        if (!exists(safe)) return safe

        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""

        for (index in 2..9999) {
            val suffix = " ($index)"
            val allowedBase = (MAX_FILE_NAME_LENGTH - extension.length - suffix.length).coerceAtLeast(1)
            val candidate = base.take(allowedBase) + suffix + extension
            if (!exists(candidate)) return candidate
        }

        return sanitizeFileName("${base.take(120)}-${System.currentTimeMillis()}$extension")
    }
}
