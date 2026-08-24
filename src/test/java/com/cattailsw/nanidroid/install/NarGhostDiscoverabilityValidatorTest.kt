package com.cattailsw.nanidroid.install

import com.cattailsw.nanidroid.HostAndroidStubRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

class NarGhostDiscoverabilityValidatorTest {
    @Rule @JvmField
    val androidStubs = HostAndroidStubRule()

    @Test
    fun missingMasterDescriptorIsNotDiscoverable() {
        val candidate = temporaryDirectory("missing-descriptor")
        File(candidate, "ghost/master").mkdirs()

        assertFalse(NarGhostDiscoverabilityValidator.validate(candidate))
    }

    @Test
    fun emptyMasterDescriptorIsNotDiscoverable() {
        val candidate = temporaryDirectory("empty-descriptor")
        File(candidate, "ghost/master/descript.txt").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(0))
        }

        assertFalse(NarGhostDiscoverabilityValidator.validate(candidate))
    }

    @Test
    fun descriptorWithAnEmptyParsedMapIsDiscoverable() {
        val candidate = temporaryDirectory("empty-parsed-descriptor")
        val descriptor = File(candidate, "ghost/master/descript.txt")
        descriptor.parentFile!!.mkdirs()
        descriptor.writeText("not a descriptor entry\n")

        assertTrue(NarGhostDiscoverabilityValidator.validate(candidate))
    }

    @Test
    fun canonicalDescriptorEscapeIsNotDiscoverable() {
        val candidate = temporaryDirectory("canonical-escape")
        val descriptor = File(candidate, "ghost/master/descript.txt")
        val escaped = File(candidate.parentFile, "outside-descript.txt")
        val files = object : NarDiscoverabilityFileSystem {
            override fun canonical(file: File) = if (file == descriptor) escaped else file
            override fun isRegularFile(file: File) = file == descriptor
            override fun parseDescriptor(file: File) = Unit
        }

        assertFalse(NarGhostDiscoverabilityValidator.validate(candidate, files))
    }

    @Test
    fun validMasterDescriptorIsDiscoverable() {
        val candidate = temporaryDirectory("valid-descriptor")
        val descriptor = File(candidate, "ghost/master/descript.txt")
        descriptor.parentFile!!.mkdirs()
        descriptor.writeText("charset,UTF-8\nname,Test Ghost\nsakura.name,Sakura\n")

        assertTrue(NarGhostDiscoverabilityValidator.validate(candidate))
    }

    @Test
    fun validShiftJisDescriptorIsDiscoverable() {
        val candidate = temporaryDirectory("shift-jis-descriptor")
        val descriptor = File(candidate, "ghost/master/descript.txt")
        descriptor.parentFile!!.mkdirs()
        descriptor.writeBytes("charset,Shift_JIS\nname,\u30c6\u30b9\u30c8\n".toByteArray(Charset.forName("Shift_JIS")))

        assertTrue(NarGhostDiscoverabilityValidator.validate(candidate))
    }

    private fun temporaryDirectory(label: String): File {
        val directory = File.createTempFile(label, "")
        if (!directory.delete() || !directory.mkdir()) throw IOException("temporary root")
        return directory
    }
}
