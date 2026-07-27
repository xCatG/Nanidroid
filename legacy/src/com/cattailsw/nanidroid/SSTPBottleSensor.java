package com.cattailsw.nanidroid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;

import android.content.Context;
import android.util.Log;

import com.cattailsw.nanidroid.util.NetworkUtil;

public class SSTPBottleSensor {
    private static final String TAG = "SSTPBottleSensor";
    private static final String BOTTLE_LOG = "https://bottle.mikage.to/fetchlog.cgi?recent=10&encoding=utf8";
    public static class ApiException extends Exception {
	public ApiException(String detailMessage, Throwable throwable) {
	    super(detailMessage, throwable);
	}
	        
	public ApiException(String detailMessage) {
	    super(detailMessage);
	}
    }

    public static class ParseException extends Exception {
	public ParseException(String detailMessage, Throwable throwable) {
	    super(detailMessage, throwable);
	}
    }
    
    public static LinkedList<String> getPageContent(Context ctx)
	throws ApiException, ParseException {

	LinkedList<String> results = getUrlContent(BOTTLE_LOG, ctx);

	return results;

    }

    protected static synchronized LinkedList<String> getUrlContent(String url, Context ctx) throws ApiException {
	        
	Log.d(TAG, "getUrlContent: url = " + url);

	try {
	    BufferedReader br = new BufferedReader(
		    new InputStreamReader(NetworkUtil.getURLStream(ctx, url), "UTF-8"));
	    try {
		return parseBuffer(br);
	    } finally {
		br.close();
	    }
	} catch (IOException e) {
	    throw new ApiException("Problem communicating with API", e);
	}
    }

    protected static LinkedList<String> parseBuffer(BufferedReader br) throws IOException {
	    while (true) {
		if (br.readLine().equals("")) break;
	    }
	            
	    LinkedList<String> results = new LinkedList<String>();
	    for (String line = br.readLine(); line != null; line = br.readLine()) {
		String[] column = line.split("\\t");
		results.add(column[7]);
	    }
	    return results;
    }
}
