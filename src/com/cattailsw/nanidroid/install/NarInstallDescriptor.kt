package com.cattailsw.nanidroid.install

import java.util.Collections
import java.util.LinkedHashMap

/** Immutable normalized metadata from a validated ghost install descriptor. */
class NarInstallDescriptor internal constructor(
    private val type: String,
    private val name: String,
    private val descriptorDirectory: String,
    private val targetId: String,
    private val accept: String?,
    metadata: Map<String, String>,
) {
    private val metadata: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(metadata))

    fun getType(): String = type

    fun getName(): String = name

    fun getDescriptorDirectory(): String = descriptorDirectory

    fun getTargetId(): String = targetId

    fun getAccept(): String? = accept

    fun isRefreshEnabled(): Boolean = false

    fun getMetadata(): Map<String, String> = metadata
}
