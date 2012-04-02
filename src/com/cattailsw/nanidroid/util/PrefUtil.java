package com.cattailsw.nanidroid.util;

import android.content.SharedPreferences;
import android.content.Context;

public class PrefUtil {
    private static final String TAG = "PrefUtil";

    private static final String SHARED_PREFS = "CATTAILSW_NANIDROID_PREFS";
    

    public static SharedPreferences getSharedPreferences(Context context) {
	return context.getSharedPreferences(SHARED_PREFS, 0);
    }

    public static boolean hasKey(Context ctx, String key) {
	SharedPreferences sp = getSharedPreferences(ctx);
	return (sp.getString(key, null) != null);
    }

    public static void setKey(Context ctx, String key, String value) {
	SharedPreferences sp = getSharedPreferences(ctx);
	sp.edit().putString(key, value).commit();
    }

    public static void setKey(Context ctx, String key, long value) {
	SharedPreferences sp = getSharedPreferences(ctx);
	sp.edit().putLong(key, value).commit();
    }

    public static void setKey(Context ctx, String key, boolean value) {
	SharedPreferences sp = getSharedPreferences(ctx);
	sp.edit().putBoolean(key, value).commit();
    }

    public static void setKey(Context ctx, String key) {
	setKey(ctx, key, "used");
    }

    public static String getKeyValue(Context ctx, String key) {
	SharedPreferences sp = getSharedPreferences(ctx);
	return sp.getString(key, null);
    }

}
