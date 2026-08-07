package com.cattailsw.nanidroid.llmghost.model

import kotlinx.serialization.Serializable

@Serializable
enum class CaseStatus {
    PASSED,
    FAILED,
}

@Serializable
data class SpikeCaseReport(
    val caseId: String,
    val status: CaseStatus,
    val rawResponse: String,
    val compiledSakuraScript: String? = null,
)
