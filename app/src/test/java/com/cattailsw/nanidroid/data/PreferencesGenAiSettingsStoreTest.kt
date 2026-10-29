package com.cattailsw.nanidroid.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesGenAiSettingsStoreTest {
    @Test fun defaultsOffAndPersistsExplicitOptInAcrossStoreRecreation() {
        var stored: Boolean? = null
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) {
                proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { stored = args!![1] as Boolean; proxy }
                "commit" -> true
                else -> error("Unexpected ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) {
                _, method, args ->
            when (method.name) {
                "getBoolean" -> stored ?: args!![1]
                "edit" -> editor
                else -> error("Unexpected ${method.name}")
            }
        } as SharedPreferences
        val store = PreferencesGenAiSettingsStore(preferences)
        assertFalse(store.read())
        store.write(true)
        assertTrue(PreferencesGenAiSettingsStore(preferences).read())
        store.write(false)
        assertFalse(PreferencesGenAiSettingsStore(preferences).read())
    }
}
