package com.cattailsw.nanidroid.shiori

import android.content.Context
import com.cattailsw.nanidroid.R

class NotSupportedShiori(
    private val context: Context?,
) : EchoShiori() {
    override fun getModuleName(): String = "NotSupportedShiori"

    override fun genResponse(): String {
        val supportedContext = context ?: return super.genResponse()
        val request = reqTable!!["id"]!!

        return when {
            request.equals("OnGhostChanged", ignoreCase = true) ||
                request.equals("OnFirstBoot", ignoreCase = true) ||
                request.equals("OnBoot", ignoreCase = true) ->
                RESPONSE_HEADER + supportedContext.getString(R.string.unsupported_shiori) + RESPONSE_END

            request.equals("OnGhostChanging", ignoreCase = true) -> {
                val format = supportedContext.getString(R.string.unsupported_shiori_ongchanging)
                RESPONSE_HEADER + String.format(format, reqTable!!["reference0"]) + RESPONSE_END
            }

            request.equals("OnClose", ignoreCase = true) ->
                RESPONSE_HEADER + supportedContext.getString(R.string.unsupported_shiori_onclose) + RESPONSE_END

            else -> RESPONSE_NO_CONTENT
        }
    }

    private companion object {
        const val RESPONSE_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT"
        const val RESPONSE_HEADER = "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: "
        const val RESPONSE_END = "\\e\r\n\r\n"
    }
}
