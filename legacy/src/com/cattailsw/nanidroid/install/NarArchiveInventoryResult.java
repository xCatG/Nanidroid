package com.cattailsw.nanidroid.install;

/** Frozen Ant-build compatibility copy; the Gradle lane is implemented in Kotlin. */
public final class NarArchiveInventoryResult {
    private final NarArchiveInventory inventory;
    private final NarInstallError error;
    private final String detail;

    private NarArchiveInventoryResult(
            NarArchiveInventory inventory, NarInstallError error, String detail) {
        this.inventory = inventory;
        this.error = error;
        this.detail = detail;
    }

    static NarArchiveInventoryResult success(NarArchiveInventory inventory) {
        return new NarArchiveInventoryResult(inventory, null, "");
    }

    static NarArchiveInventoryResult failure(
            NarInstallError error, String detail) {
        return new NarArchiveInventoryResult(null, error, detail);
    }

    public boolean isSuccess() { return inventory != null; }
    public NarArchiveInventory getInventory() { return inventory; }
    public NarInstallError getError() { return error; }
    public String getDetail() { return detail; }
}
