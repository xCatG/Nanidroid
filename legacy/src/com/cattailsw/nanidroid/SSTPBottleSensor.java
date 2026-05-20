package com.cattailsw.nanidroid;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedList;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;


import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

import com.cattailsw.nanidroid.util.NetworkUtil;

public class SSTPBottleSensor {
    private static final String TAG = "SSTPBottleSensor";
    private static final String BOTTLE_LOG = "http://bottle.mikage.to/fetchlog.cgi?recent=10&encoding=utf8";
    private static final int HTTP_STATUS_OK = 200;
    /**
     * Thrown when there were problems contacting the remote API server, either
     * because of a network error, or the server returned a bad status code.
     */
    public static class ApiException extends Exception {
	public ApiException(String detailMessage, Throwable throwable) {
	    super(detailMessage, throwable);
	}
	        
	public ApiException(String detailMessage) {
	    super(detailMessage);
	}
    }

    /**
     * Thrown when there were problems parsing the response to an API call,
     * either because the response was empty, or it was malformed.
     */
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

	HttpClient client = NetworkUtil.getHttpClient(ctx, url.startsWith("https"));
	HttpGet request = new HttpGet(url);

	try {
	    HttpResponse response = client.execute(request);
	            
	    // Check if server response is valid
	    StatusLine status = response.getStatusLine();
	    if (status.getStatusCode() != HTTP_STATUS_OK) {
		throw new ApiException("Invalid response from server: " +
				       status.toString());
	    }
	    
	    // Pull content stream from response
	    HttpEntity entity = response.getEntity();
	    BufferedReader br = new BufferedReader(new InputStreamReader(entity.getContent(), "UTF-8"));

	    LinkedList<String> results = parseBuffer(br);	            
	    br.close();
	            
	    // Return result from buffered stream
	    return results;
	} catch (IOException e) {
	    throw new ApiException("Problem communicating with API", e);
	}
    }

    protected static LinkedList<String> parseBuffer(BufferedReader br) throws IOException {
	    // skip status lines on top
	    while (true) {
		if (br.readLine().equals("")) break;
	    }
	            
	    LinkedList<String> results = new LinkedList<String>();
	    for (String line = br.readLine(); line != null; line = br.readLine()) {
		String[] column = line.split("\t");
// 		Log.d(TAG, "column[7] = " + column[7]);
		results.add(column[7]);
	    }
	    return results;
    }

}
