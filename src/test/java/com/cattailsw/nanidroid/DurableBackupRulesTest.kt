package com.cattailsw.nanidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class DurableBackupRulesTest {
    @Test
    fun `durable operation bindings are excluded from every backup transport`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))

        assertExcludedSharedPreferences(
            File("src/main/res/xml/backup_rules.xml"),
            setOf("durable_operations_v1.xml", "nar-download-queue.xml"),
        )
        assertExcludedSharedPreferences(
            File("src/main/res/xml/data_extraction_rules.xml"),
            setOf("durable_operations_v1.xml", "nar-download-queue.xml"),
            "cloud-backup",
            "device-transfer",
        )
    }

    private fun assertExcludedSharedPreferences(
        rulesFile: File,
        expectedPaths: Set<String>,
        vararg sections: String,
    ) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rulesFile)
        val roots = if (sections.isEmpty()) listOf(document.documentElement) else sections.map { section ->
            document.documentElement.childNodes.asSequence()
                .filterIsInstance<Element>()
                .single { it.tagName == section }
        }
        roots.forEach { root ->
            val excludedPaths = root.childNodes.asSequence()
                .filterIsInstance<Element>()
                .filter { it.tagName == "exclude" && it.getAttribute("domain") == "sharedpref" }
                .map { it.getAttribute("path") }
                .toSet()
            assertEquals(expectedPaths, excludedPaths)
        }
    }

    private fun org.w3c.dom.NodeList.asSequence() = sequence {
        for (index in 0 until length) yield(item(index))
    }
}
