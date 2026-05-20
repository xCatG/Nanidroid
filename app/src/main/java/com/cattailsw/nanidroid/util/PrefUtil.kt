package com.cattailsw.nanidroid.util

import android.content.Context
import android.content.SharedPreferences

object PrefUtil {
    private const val TAG = "PrefUtil"
    private const val SHARED_PREFS = "CATTAILSW_NANIDROID_PREFS"

    @JvmStatic
    fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(SHARED_PREFS, Context.MODE_PRIVATE)
    }

    @JvmStatic
    fun hasKey(ctx: Context, key: String): Boolean {
        val sp = getSharedPreferences(ctx)
        return sp.getString(key, null) != null
    }

    @JvmStatic
    fun setKey(ctx: Context, key: String, value: String) {
        val sp = getSharedPreferences(ctx)
        sp.edit().putString(key, value).apply()
    }

    @JvmStatic
    fun setKey(ctx: Context, key: String, value: Long) {
        val sp = getSharedPreferences(ctx)
        sp.edit().putLong(key, value).apply()
    }

    @JvmStatic
    fun setKey(ctx: Context, key: String, value: Boolean) {
        val sp = getSharedPreferences(ctx)
        sp.edit().putBoolean(key, value).apply()
    }

    @JvmStatic
    fun setKey(ctx: Context, key: String) {
        setKey(ctx, key, "used")
    }

    @JvmStatic
    fun getKeyValue(ctx: Context, key: String): String? {
        val sp = getSharedPreferences(ctx)
        return sp.getString(key, null)
    }

    @JvmStatic
    fun getKeyValueLong(ctx: Context, key: String): Long {
        val sp = getSharedPreferences(ctx)
        return sp.getLong(key, 0L)
    }
}
