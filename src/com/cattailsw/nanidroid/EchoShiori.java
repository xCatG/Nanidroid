package com.cattailsw.nanidroid;

public class EchoShiori implements Shiori {
    public String getModuleName() {
	return "EchoShiori";
    }
    public String request(String req) {
	// need to parse the header and return request in sakura script
	return req + "\\e";
    }

    public void terminate() {
	// do nothing
    }
}
