package com.cattailsw.nanidroid.util

import android.content.Context
import android.content.SharedPreferences

/** Shared-preference helpers retained as Java-callable static APIs for existing callers. */
object PrefUtil {
    private const val SHARED_PREFS = "CATTAILSW_NANIDROID_PREFS"

    @JvmStatic
    fun getSharedPreferences(context: Context?): SharedPreferences =
        context!!.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun hasKey(ctx: Context?, key: String): Boolean =
        getSharedPreferences(ctx).getString(key, null) != null

    @JvmStatic
    fun setKey(ctx: Context?, key: String, value: String?) {
        getSharedPreferences(ctx).edit().putString(key, value).commit()
    }

    @JvmStatic
    fun setKey(ctx: Context?, key: String, value: Long) {
        getSharedPreferences(ctx).edit().putLong(key, value).commit()
    }

    @JvmStatic
    fun setKeyAsync(ctx: Context?, key: String, value: Long) {
        getSharedPreferences(ctx).edit().putLong(key, value).apply()
    }

    @JvmStatic
    fun setKey(ctx: Context?, key: String, value: Boolean) {
        getSharedPreferences(ctx).edit().putBoolean(key, value).commit()
    }

    @JvmStatic
    fun setKey(ctx: Context?, key: String) {
        setKey(ctx, key, "used")
    }

    @JvmStatic
    fun getKeyValue(ctx: Context?, key: String): String? =
        getSharedPreferences(ctx).getString(key, null)

    @JvmStatic
    fun getKeyValueLong(ctx: Context?, key: String): Long =
        getSharedPreferences(ctx).getLong(key, 0)
}
