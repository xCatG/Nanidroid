package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.shiori.NanidroidShiori
import com.cattailsw.nanidroid.shiori.NotSupportedShiori
import com.cattailsw.nanidroid.shiori.Kawari
import com.cattailsw.nanidroid.shiori.YayaShiori
import com.cattailsw.nanidroid.shiori.SatoriShiori
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
     * Keep unknown historical engines on the compatibility stub. Satori is the
     * one retained native engine and is packaged as a first-party JNI library.
     */
    private fun checkShioriByPath(path: String, ctx: Context?): Shiori =
        NotSupportedShiori(ctx)

    fun getShiori(path: String, masterDesc: Map<String, String>?, ctx: Context?): Shiori =
        when (masterDesc!!["shiori"]) {
            null -> checkShioriByPath(path, ctx)
            "Nanidroid" -> NanidroidShiori(ctx, path)
            "satori.dll" -> SatoriShiori(path, ctx)
            "shiori.dll" -> Kawari(path)
            "yaya.dll" -> YayaShiori(path)
            else -> NotSupportedShiori(ctx)
        }
}
