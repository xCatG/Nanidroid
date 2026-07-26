package com.cattailsw.nanidroid;

/** Immutable SHIORI wire-protocol version; replaces Apache's HTTP-only type. */
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
