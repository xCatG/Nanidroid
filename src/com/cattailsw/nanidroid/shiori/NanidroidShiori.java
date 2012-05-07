package com.cattailsw.nanidroid.shiori;

/**
 * Describe class NanidroidShiori here.
 *
 *
 * Created: Mon May 07 10:53:47 2012
 *
 * @author <a href="mailto:"></a>
 * @version 1.0
 */
public class NanidroidShiori implements Shiori {
    private static final String TAG = "NanidroidShiori";
    public NanidroidShiori() {

    }

    public String getModuleName() {
	return "NanidroidShiori";
    }

    public String request(String req) {
	return "SHIORI/3.0 204 NO CONTENT";
    }

    public void terminate() {

    }    
}
