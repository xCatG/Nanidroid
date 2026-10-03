package com.cattailsw.nanidroid.ghost

import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptorReaderTest {
    @Test fun descriptorKeepsCommasInValues() {
        val bytes = "\uFEFFcharset,UTF-8\r\nname,Hello, friend\r\n".toByteArray(Charsets.UTF_8)
        assertEquals("Hello, friend", DescriptorReader.read(bytes)["name"])
    }

    @Test fun descriptorDefaultsToCp932() {
        val bytes = "name,ドロイド君\r\n".toByteArray(charset("windows-31j"))
        assertEquals("ドロイド君", DescriptorReader.read(bytes)["name"])
    }

    @Test fun utf8BomDefaultsToUtf8WithoutCharsetDeclaration() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "name,ゴースト\r\n".toByteArray(Charsets.UTF_8)
        assertEquals("ゴースト", DescriptorReader.read(bytes)["name"])
    }

    @Test fun charsetDeclarationAfterFirstLineDoesNotOverrideCp932() {
        val bytes = "name,ドロイド君\r\ncharset,UTF-8\r\n".toByteArray(charset("windows-31j"))
        assertEquals("ドロイド君", DescriptorReader.read(bytes)["name"])
    }
}
