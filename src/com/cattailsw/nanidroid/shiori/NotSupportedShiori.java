package com.cattailsw.nanidroid.shiori;

import android.content.Context;
import com.cattailsw.nanidroid.R;

public class NotSupportedShiori extends EchoShiori {
    private static final String TAG = "NotSupportedShiori";
    private static final String RES_NO_CONTENT = "SHIORI/3.0 204 NO CONTENT";
    private static final String RES_HEADER = "SHIORI/3.0 200 OK\r\nSender: EchoShiori\r\nValue: ";
    private static final String RES_END = "\\e\\r\\n\\r\\n";

    private Context mCtx = null;

    public NotSupportedShiori(Context ctx) {
	mCtx = ctx;
    }

    public String getModuleName() {
	return "NotSupportedShiori";
    }

    @Override
    protected String genResponse() {
	if ( mCtx == null )
	    return super.genResponse();

	String req = reqTable.get("id");
	if ( req.equalsIgnoreCase("OnGhostChanged")) {
	    return RES_HEADER + mCtx.getString(R.string.unsupported_shiori) + RES_END;
	}
	else if ( req.equalsIgnoreCase("OnGhostChanging") ) {
	    String fmt = mCtx.getString(R.string.unsupported_shiori_ongchanging);
	    
	    return RES_HEADER + String.format(fmt, reqTable.get("reference0"))  + RES_END;
	}
	else if ( "OnClose".equalsIgnoreCase(req) ) {
	    return RES_HEADER + mCtx.getString(R.string.unsupported_shiori_onclose) + RES_END;
	}

	return RES_NO_CONTENT;
    }    
    
}
