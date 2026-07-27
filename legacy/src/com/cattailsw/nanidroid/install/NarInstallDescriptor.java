package com.cattailsw.nanidroid.install;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Frozen Ant-build compatibility copy; the Gradle lane is implemented in Kotlin. */
public final class NarInstallDescriptor {
    private final String type;
    private final String name;
    private final String descriptorDirectory;
    private final String targetId;
    private final String accept;
    private final Map<String, String> metadata;

    NarInstallDescriptor(
            String type,
            String name,
            String descriptorDirectory,
            String targetId,
            String accept,
            Map<String, String> metadata) {
        this.type = type;
        this.name = name;
        this.descriptorDirectory = descriptorDirectory;
        this.targetId = targetId;
        this.accept = accept;
        this.metadata = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(metadata));
    }

    public String getType() { return type; }
    public String getName() { return name; }
    public String getDescriptorDirectory() { return descriptorDirectory; }
    public String getTargetId() { return targetId; }
    public String getAccept() { return accept; }
    public boolean isRefreshEnabled() { return false; }
    public Map<String, String> getMetadata() { return metadata; }
}
