package com.cattailsw.nanidroid.shiori;

public interface Shiori {
    public String getModuleName();
    public String request(String req);
    public void terminate();
    public void unloadShiori();
}
