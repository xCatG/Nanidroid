package com.cattailsw.nanidroid.install

/** Immutable result of structural NAR inventory validation. */
class NarArchiveInventoryResult private constructor(
    private val inventory: NarArchiveInventory?,
    private val error: NarInstallError?,
    private val detail: String,
) {
    fun isSuccess(): Boolean = inventory != null

    fun getInventory(): NarArchiveInventory? = inventory

    fun getError(): NarInstallError? = error

    fun getDetail(): String = detail

    companion object {
        @JvmStatic
        fun success(
            inventory: NarArchiveInventory,
        ): NarArchiveInventoryResult = NarArchiveInventoryResult(
            inventory,
            null,
            "",
        )

        @JvmStatic
        fun failure(
            error: NarInstallError,
            detail: String,
        ): NarArchiveInventoryResult = NarArchiveInventoryResult(
            null,
            error,
            detail,
        )
    }
}
