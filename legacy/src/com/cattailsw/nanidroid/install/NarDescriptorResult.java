package com.cattailsw.nanidroid.install;

/** Frozen Ant-build compatibility copy; the Gradle lane is implemented in Kotlin. */
public final class NarDescriptorResult {
    private final NarInstallDescriptor descriptor;
    private final NarInstallError error;
    private final String detail;

    private NarDescriptorResult(
            NarInstallDescriptor descriptor,
            NarInstallError error,
            String detail) {
        this.descriptor = descriptor;
        this.error = error;
        this.detail = detail;
    }

    static NarDescriptorResult success(NarInstallDescriptor descriptor) {
        return new NarDescriptorResult(descriptor, null, "");
    }

    static NarDescriptorResult failure(NarInstallError error, String detail) {
        return new NarDescriptorResult(null, error, detail);
    }

    public boolean isSuccess() { return descriptor != null; }
    public NarInstallDescriptor getDescriptor() { return descriptor; }
    public NarInstallError getError() { return error; }
    public String getDetail() { return detail; }
}
