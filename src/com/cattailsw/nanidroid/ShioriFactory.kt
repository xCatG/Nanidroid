package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.shiori.Kawari
import com.cattailsw.nanidroid.shiori.NanidroidShiori
import com.cattailsw.nanidroid.shiori.NotSupportedShiori
import com.cattailsw.nanidroid.shiori.SatoriPosixShiori
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

    private fun checkShioriByPath(path: String, ctx: Context?): Shiori = when {
        File(path, "kawarirc.kis").exists() -> Kawari(path)
        File(path, "kawari.ini").exists() -> NotSupportedShiori(ctx)
        File(path, "aya5.txt").exists() -> NotSupportedShiori(ctx)
        else -> NotSupportedShiori(ctx)
    }

    fun getShiori(path: String, masterDesc: Map<String, String>?, ctx: Context?): Shiori =
        when (masterDesc!!["shiori"]) {
            null -> checkShioriByPath(path, ctx)
            "Nanidroid" -> NanidroidShiori(ctx, path)
            "satori.dll" -> SatoriPosixShiori(path)
            "shiori.dll" -> checkShioriByPath(path, ctx)
            "yaya.dll" -> NotSupportedShiori(ctx)
            else -> NotSupportedShiori(ctx)
        }
}
