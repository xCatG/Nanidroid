package com.cattailsw.nanidroid.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PreferencesLastGhostStoreTest {
    @Test fun readsOnlyExplicitlyCommittedLastGhost() {
        var stored: String? = null
        var commits = 0
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { stored = args!![1] as String; proxy }
                "commit" -> { commits++; true }
                else -> proxy
            }
        } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getString" -> stored
                "edit" -> editor
                else -> error("Unexpected ${method.name}")
            }
        } as SharedPreferences
        val store = PreferencesLastGhostStore(preferences)
        assertNull(store.read())
        assertEquals(0, commits)
        store.write("nanidroid")
        assertEquals("nanidroid", store.read())
        assertEquals(1, commits)
    }
}
