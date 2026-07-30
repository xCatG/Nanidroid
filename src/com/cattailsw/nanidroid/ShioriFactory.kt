package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.shiori.NanidroidShiori
import com.cattailsw.nanidroid.shiori.NotSupportedShiori
import com.cattailsw.nanidroid.shiori.Kawari
import com.cattailsw.nanidroid.shiori.YayaShiori
import com.cattailsw.nanidroid.shiori.SatoriShiori
import com.cattailsw.nanidroid.shiori.Shiori
import java.io.File

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
     * `shiori.dll` is a generic descriptor. Only identify it as Kawari 8 when
     * its required configuration is present; otherwise preserve the stub.
     */
    private fun checkShioriByPath(path: String, ctx: Context?): Shiori =
        if (File(path, "kawarirc.kis").isFile) Kawari(path) else NotSupportedShiori(ctx)

    fun getShiori(path: String, masterDesc: Map<String, String>?, ctx: Context?): Shiori =
        when (masterDesc!!["shiori"]) {
            null -> checkShioriByPath(path, ctx)
            "Nanidroid" -> NanidroidShiori(ctx, path)
            "satori.dll" -> SatoriShiori(path, ctx)
            "shiori.dll" -> checkShioriByPath(path, ctx)
            "yaya.dll" -> YayaShiori(path, ctx)
            else -> NotSupportedShiori(ctx)
        }
}
