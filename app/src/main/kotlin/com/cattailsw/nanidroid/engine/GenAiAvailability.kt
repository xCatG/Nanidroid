package com.cattailsw.nanidroid.engine

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation

fun interface GenAiAvailability {
    suspend fun isAvailable(): Boolean
}

class MlKitGenAiAvailability : GenAiAvailability {
    override suspend fun isAvailable(): Boolean {
        val model = Generation.getClient()
        return try {
            model.checkStatus() == FeatureStatus.AVAILABLE
        } finally {
            model.close()
        }
    }
}
