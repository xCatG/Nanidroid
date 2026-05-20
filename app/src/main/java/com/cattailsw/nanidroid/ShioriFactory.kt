package com.cattailsw.nanidroid

import android.content.Context
import com.cattailsw.nanidroid.shiori.*
import java.io.File

class ShioriFactory private constructor() {
    companion object {
        private var _self: ShioriFactory? = null

        @JvmStatic
        fun getInstance(): ShioriFactory {
            if (_self == null) {
                _self = ShioriFactory()
            }
            return _self!!
        }
    }

    fun getShiori(path: String, masterDesc: Map<String, String>): Shiori {
        return getShiori(path, masterDesc, null)
    }

    private fun checkShioriByPath(path: String, ctx: Context?): Shiori {
        return if (File(path, "kawarirc.kis").exists()) {
            Kawari(path)
        } else if (File(path, "kawari.ini").exists()) {
            NotSupportedShiori(ctx)
        } else if (File(path, "aya5.txt").exists()) {
            NotSupportedShiori(ctx)
        } else {
            NotSupportedShiori(ctx)
        }
    }

    fun getShiori(path: String, masterDesc: Map<String, String>, ctx: Context?): Shiori {
        val sdllname = masterDesc["shiori"]
        return if (sdllname == null) {
            checkShioriByPath(path, ctx)
        } else {
            if (sdllname.matches("Nanidroid".toRegex())) {
                NanidroidShiori(ctx!!, path)
            } else if (sdllname.matches("satori.dll".toRegex())) {
                SatoriPosixShiori(path)
            } else if (sdllname.matches("shiori.dll".toRegex())) {
                checkShioriByPath(path, ctx)
            } else if (sdllname.matches("yaya.dll".toRegex())) {
                NotSupportedShiori(ctx)
            } else {
                NotSupportedShiori(ctx)
            }
        }
    }
}
