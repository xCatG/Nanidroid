package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.shiori.NanidroidShiori
import com.cattailsw.nanidroid.shiori.NotSupportedShiori
import com.cattailsw.nanidroid.shiori.Shiori

/** Chooses the SHIORI engine described by an installed ghost. */
class ShioriFactory private constructor() {
    companion object {
        private val instance = ShioriFactory()

        @JvmStatic
        fun getInstance(): ShioriFactory = instance
    }

    fun getShiori(path: String, masterDesc: Map<String, String>?): Shiori =
        getShiori(path, masterDesc, null)

    /**
     * Native SHIORI engines are intentionally unsupported on the modern Android
     * product.  Keep the historical descriptor detection at this boundary, but
     * route every native or unknown engine to the established compatibility stub
     * rather than loading a JNI library.
     */
    private fun checkShioriByPath(path: String, ctx: Context?): Shiori =
        NotSupportedShiori(ctx)

    fun getShiori(path: String, masterDesc: Map<String, String>?, ctx: Context?): Shiori =
        when (masterDesc!!["shiori"]) {
            null -> checkShioriByPath(path, ctx)
            "Nanidroid" -> NanidroidShiori(ctx, path)
            "satori.dll" -> NotSupportedShiori(ctx)
            "shiori.dll" -> checkShioriByPath(path, ctx)
            "yaya.dll" -> NotSupportedShiori(ctx)
            else -> NotSupportedShiori(ctx)
        }
}
