package com.cattailsw.nanidroid.install;

/** Immutable diagnostic result of planning or verifying a NAR. */
public final class NarInstallPlanResult {
    private final NarInstallPlan plan;
    private final NarVerifiedInstallSession verifiedSession;
    private final NarInstallError error;
    private final String detail;

    private NarInstallPlanResult(
            NarInstallPlan plan,
            NarVerifiedInstallSession verifiedSession,
            NarInstallError error,
            String detail) {
        this.plan = plan;
        this.verifiedSession = verifiedSession;
        this.error = error;
        this.detail = detail;
    }

    static NarInstallPlanResult success(NarInstallPlan plan) {
        return new NarInstallPlanResult(plan, null, null, "");
    }

    static NarInstallPlanResult stagedSuccess(
            NarInstallPlan plan,
            NarVerifiedInstallSession verifiedSession) {
        return new NarInstallPlanResult(
                plan, verifiedSession, null, "");
    }

    static NarInstallPlanResult failure(
            NarInstallError error, String detail) {
        return new NarInstallPlanResult(null, null, error, detail);
    }

    public boolean isSuccess() { return plan != null; }
    public NarInstallPlan getPlan() { return plan; }
    public NarInstallError getError() { return error; }
    public String getDetail() { return detail; }
    NarVerifiedInstallSession getVerifiedSession() {
        return verifiedSession;
    }
}
