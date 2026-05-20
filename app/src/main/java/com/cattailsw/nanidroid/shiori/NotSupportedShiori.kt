package com.cattailsw.nanidroid.shiori

import android.content.Context
import com.cattailsw.nanidroid.R

class NotSupportedShiori(private val mCtx: Context?) : EchoShiori() {
    companion object {
        private const val TAG = "NotSupportedShiori"
        private const val RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT"
        private const val RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: "
        private const val RES_END = "\\e\r\n\r\n"
    }

    override fun getModuleName(): String {
        return "NotSupportedShiori"
    }

    override fun genResponse(): String {
        val table = reqTable
        if (mCtx == null || table == null) {
            return super.genResponse()
        }

        val req = table["id"] ?: return RES_NO_CONTENT
        return if (req.equals("OnGhostChanged", ignoreCase = true) ||
            req.equals("OnFirstBoot", ignoreCase = true) ||
            req.equals("OnBoot", ignoreCase = true)
        ) {
            RES_HEADER + mCtx.getString(R.string.unsupported_shiori) + RES_END
        } else if (req.equals("OnGhostChanging", ignoreCase = true)) {
            val fmt = mCtx.getString(R.string.unsupported_shiori_ongchanging)
            val ref0 = table["reference0"] ?: ""
            RES_HEADER + String.format(fmt, ref0) + RES_END
        } else if (req.equals("OnClose", ignoreCase = true)) {
            RES_HEADER + mCtx.getString(R.string.unsupported_shiori_onclose) + RES_END
        } else {
            RES_NO_CONTENT
        }
    }
}
