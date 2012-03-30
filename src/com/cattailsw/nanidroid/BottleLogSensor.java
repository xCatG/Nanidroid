package com.cattailsw.nanidroid;

import java.util.LinkedList;
import android.content.res.AssetManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import android.content.Context;


public class BottleLogSensor extends SSTPBottleSensor {
    protected static synchronized LinkedList<String> getUrlContent(Context ctx, String url) throws ApiException {
	try {

            AssetManager assetManager = ctx.getAssets();
            InputStream is = assetManager.open("sstpbottlelog.log");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
	    LinkedList<String> results = parseBuffer(reader);
	    return results;
	}
	catch(Exception e) {
	    throw new ApiException("Problem communicating with API", e);
	}
    }
    
}
