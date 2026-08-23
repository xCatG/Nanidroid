package com.cattailsw.nanidroid

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class NativeLibraryPackagingInstrumentationTest {
    @Test
    fun apkOmitsNarfsAndRetainsAllShioriLibrariesForRunningAbi() {
        val abi = Build.SUPPORTED_ABIS.first()
        val apk = File(
            InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationInfo.sourceDir,
        )

        ZipFile(apk).use { zip ->
            RETAINED_SHIORI_LIBRARIES.forEach { library ->
                val path = "lib/$abi/$library"
                val entry = zip.getEntry(path)
                assertNotNull("Retained SHIORI library is missing: $path", entry)
                zip.getInputStream(entry).use { input ->
                    val header = ByteArray(20)
                    DataInputStream(input).readFully(header)
                    assertEquals(0x7f, header[0].toInt() and 0xff)
                    assertEquals('E'.code, header[1].toInt())
                    assertEquals('L'.code, header[2].toInt())
                    assertEquals('F'.code, header[3].toInt())
                    assertEquals("Expected a 64-bit ELF: $path", 2, header[4].toInt())
                    assertEquals("Expected little-endian ELF: $path", 1, header[5].toInt())
                    val machine = (header[18].toInt() and 0xff) or
                        ((header[19].toInt() and 0xff) shl 8)
                    assertEquals("Wrong ELF machine: $path", expectedElfMachine(abi), machine)
                }
            }

            assertNull(
                "Obsolete NARFS library remains packaged for $abi",
                zip.getEntry("lib/$abi/libnarfs.so"),
            )
        }
    }

    private fun expectedElfMachine(abi: String): Int = when (abi) {
        "arm64-v8a" -> 183
        "x86_64" -> 62
        else -> error("Unsupported runtime ABI: $abi")
    }

    private companion object {
        val RETAINED_SHIORI_LIBRARIES = listOf(
            "libsatoriya.so",
            "libssu.so",
            "libkawari8.so",
            "libyaya.so",
        )
    }
}
