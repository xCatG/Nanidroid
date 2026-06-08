package com.cattailsw.nanidroid.test

import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.shiori.Shiori
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GhostUnloadTest {

    @Test
    fun unload_calledTwice_unloadsShioriOnce() {
        var unloadCount = 0
        val fakeShiori = object : Shiori {
            override fun getModuleName(): String = "FakeShiori"
            override fun request(req: String): String = ""
            override fun terminate() {}
            override fun unloadShiori() { unloadCount++ }
        }

        val ghost = object : Ghost("testPath") {
            override fun loadGhostInfo() {}
            override fun incrementCreateCount() {}
            override fun getCreateCount(): Long = 0L
        }
        ghost.shiori = fakeShiori

        ghost.unload()
        ghost.unload()

        assertEquals(1, unloadCount)
    }
}
