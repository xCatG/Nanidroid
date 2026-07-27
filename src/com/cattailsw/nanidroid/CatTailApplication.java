package com.cattailsw.nanidroid;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import com.cattailsw.nanidroid.util.AnalyticsUtils;
import com.cattailsw.nanidroid.util.CrashReporting;

public class CatTailApplication extends Application {

    public final void onCreate() {
	super.onCreate();
	if (isDeviceValidationNoTelemetry()) {
	    AnalyticsUtils.setDeviceValidationNoTelemetry(true);
	    return;
	}
	CrashReporting.initialize(this);
    }

    private boolean isDeviceValidationNoTelemetry() {
	try {
	    ApplicationInfo info = getPackageManager().getApplicationInfo(
		    getPackageName(), PackageManager.GET_META_DATA);
	    Bundle metadata = info.metaData;
	    return metadata != null && metadata.getBoolean(
		    "com.cattailsw.nanidroid.DEVICE_VALIDATION_NO_TELEMETRY", false);
	} catch (PackageManager.NameNotFoundException ignored) {
	    return false;
	}
    }

}
