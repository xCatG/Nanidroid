package com.cattailsw.nanidroid.install

import java.util.ArrayList
import java.util.Collections

/** Immutable internal result that exposes only the staged capability. */
internal class NarStagedSourceCopyResult private constructor(
    private val source: NarStagedSource?,
    private val error: NarStagedSourceCopyError?,
    private val detail: String?,
    cleanupErrors: List<NarStagedSourceCopyError>
) {
    private val cleanupErrors = Collections.unmodifiableList(
        ArrayList(cleanupErrors)
    )

    companion object {
        @JvmStatic
        fun success(source: NarStagedSource?): NarStagedSourceCopyResult =
            NarStagedSourceCopyResult(source, null, "", emptyList())

        @JvmStatic
        fun failure(
            error: NarStagedSourceCopyError?,
            detail: String?,
            cleanupErrors: List<NarStagedSourceCopyError>
        ): NarStagedSourceCopyResult =
            NarStagedSourceCopyResult(null, error, detail, cleanupErrors)
    }

    fun isSuccess(): Boolean = source != null

    fun getSource(): NarStagedSource? = source

    fun getError(): NarStagedSourceCopyError? = error

    fun getDetail(): String? = detail

    fun getCleanupErrors(): List<NarStagedSourceCopyError> = cleanupErrors
}
