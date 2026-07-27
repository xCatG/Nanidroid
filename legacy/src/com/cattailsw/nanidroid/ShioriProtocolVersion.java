package com.cattailsw.nanidroid;

/** Java-only copy used by the frozen Ant build, which cannot compile Kotlin. */
public final class ShioriProtocolVersion {
    private final String protocol;
    private final int major;
    private final int minor;

    public ShioriProtocolVersion(String protocol, int major, int minor) {
        this.protocol = protocol;
        this.major = major;
        this.minor = minor;
    }

    @Override
    public String toString() {
        return protocol + "/" + major + "." + minor;
    }
}
