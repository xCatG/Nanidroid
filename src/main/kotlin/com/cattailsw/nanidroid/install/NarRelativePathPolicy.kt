package com.cattailsw.nanidroid.install

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/** Shared pure relative-path rules for NAR entries and live ghost trees. */
internal object NarRelativePathPolicy {
    const val MAX_DEPTH = 32
    const val MAX_PATH_BYTES = 1024
    const val MAX_COMPONENT_BYTES = 255

    fun normalize(raw: String?): Result {
        if (raw.isNullOrEmpty() || raw.startsWith("/") || '\\' in raw || !validUnicode(raw)) {
            return Result.failure(Error.INVALID_PATH)
        }
        // Kotlin requires a non-negative limit; its default preserves the
        // trailing empty component, matching Java split("/", -1).
        val components = raw.split('/')
        if (components.size > MAX_DEPTH) return Result.failure(Error.PATH_DEPTH_LIMIT)
        val normalized = StringBuilder()
        return try {
            for (component in components) {
                if (component.isEmpty() || component == "." || component == ".."
                    || ':' in component || containsControl(component)
                ) return Result.failure(Error.INVALID_PATH)
                val nfc = Normalizer.normalize(component, Normalizer.Form.NFC)
                if (nfc.toByteArray(StandardCharsets.UTF_8).size > MAX_COMPONENT_BYTES) {
                    return Result.failure(Error.COMPONENT_LENGTH_LIMIT)
                }
                if (normalized.isNotEmpty()) normalized.append('/')
                normalized.append(nfc)
            }
            val path = normalized.toString()
            if (path.toByteArray(StandardCharsets.UTF_8).size > MAX_PATH_BYTES) {
                Result.failure(Error.PATH_LENGTH_LIMIT)
            } else {
                Result.success(path, collisionKey(path))
            }
        } catch (_: RuntimeException) {
            Result.failure(Error.INVALID_PATH)
        }
    }

    fun collisionKey(value: String): String {
        val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
        return Normalizer.normalize(nfc.lowercase(Locale.US), Normalizer.Form.NFC)
    }

    private fun containsControl(value: String): Boolean =
        value.any { it.isISOControl() }

    private fun validUnicode(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current.isHighSurrogate()) {
                if (++index >= value.length || !value[index].isLowSurrogate()) return false
            } else if (current.isLowSurrogate()) {
                return false
            }
            index++
        }
        return true
    }

    enum class Error { INVALID_PATH, PATH_DEPTH_LIMIT, PATH_LENGTH_LIMIT, COMPONENT_LENGTH_LIMIT }

    class Result private constructor(
        val normalized: String?,
        val key: String?,
        val error: Error?,
    ) {
        fun isSuccess(): Boolean = error == null

        companion object {
            internal fun success(normalized: String, key: String) = Result(normalized, key, null)
            internal fun failure(error: Error) = Result(null, null, error)
        }
    }
}
