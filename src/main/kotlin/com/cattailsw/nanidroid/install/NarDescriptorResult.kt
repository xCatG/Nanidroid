package com.cattailsw.nanidroid.install

/** Immutable result of parsing one snapshotted install descriptor. */
class NarDescriptorResult private constructor(
    private val descriptor: NarInstallDescriptor?,
    private val error: NarInstallError?,
    private val detail: String,
) {
    fun isSuccess(): Boolean = descriptor != null

    fun getDescriptor(): NarInstallDescriptor? = descriptor

    fun getError(): NarInstallError? = error

    fun getDetail(): String = detail

    companion object {
        @JvmStatic
        fun success(descriptor: NarInstallDescriptor): NarDescriptorResult =
            NarDescriptorResult(descriptor, null, "")

        @JvmStatic
        fun failure(
            error: NarInstallError,
            detail: String,
        ): NarDescriptorResult = NarDescriptorResult(null, error, detail)
    }
}
