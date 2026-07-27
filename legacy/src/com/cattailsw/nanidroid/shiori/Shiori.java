package com.cattailsw.nanidroid.shiori;

/** Java-only copy used by the frozen Ant build, which cannot compile Kotlin. */
public interface Shiori {
    String getModuleName();
    String request(String req);
    void terminate();
    void unloadShiori();
}
