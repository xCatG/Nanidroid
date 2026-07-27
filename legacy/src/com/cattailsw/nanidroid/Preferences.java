package com.cattailsw.nanidroid;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

/** Frozen Ant-only counterpart for the historical Java reference build. */
public class Preferences extends PreferenceActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(this);
        CheckBoxPreference analytics = new CheckBoxPreference(this);
        analytics.setKey("enable_analytics");
        analytics.setDefaultValue(true);
        analytics.setTitle(R.string.enable_analytic_title);
        analytics.setSummary(R.string.enable_analytic_desc);
        screen.addPreference(analytics);
        setPreferenceScreen(screen);
    }
}
