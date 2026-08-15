package com.cattailsw.nanidroid.install

/** Immutable diagnostic result of planning or verifying a NAR. */
class NarInstallPlanResult private constructor(
    val plan: NarInstallPlan?,
    private val verifiedSession: NarVerifiedInstallSession?,
    val error: NarInstallError?,
    val detail: String,
) {
    fun isSuccess(): Boolean = plan != null

    @Suppress("ERROR_SUPPRESSION", "EXPOSED_FUNCTION_RETURN_TYPE")
    fun getVerifiedSession(): NarVerifiedInstallSession? = verifiedSession

    companion object {
        @JvmStatic
        fun success(plan: NarInstallPlan?): NarInstallPlanResult =
            NarInstallPlanResult(plan, null, null, "")

        @JvmStatic
        @Suppress("ERROR_SUPPRESSION", "EXPOSED_PARAMETER_TYPE")
        fun stagedSuccess(
            plan: NarInstallPlan?,
            verifiedSession: NarVerifiedInstallSession?,
        ): NarInstallPlanResult = NarInstallPlanResult(plan, verifiedSession, null, "")

        @JvmStatic
        fun failure(error: NarInstallError?, detail: String?): NarInstallPlanResult =
            NarInstallPlanResult(null, null, error, detail ?: "")
    }
}
