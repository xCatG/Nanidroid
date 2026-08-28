package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.shiori.NanidroidShiori
import com.cattailsw.nanidroid.shiori.ShioriLoadResult
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pins the built-in content.txt SHIORI adapter before its Kotlin migration.  */
class NanidroidShioriCharacterizationTest {
    @Rule
    @JvmField
    val androidStubs: com.cattailsw.nanidroid.HostAndroidStubRule =
        com.cattailsw.nanidroid.HostAndroidStubRule()
    private var originalLocale: Locale? = null
    private var root: File? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        originalLocale = Locale.getDefault()
        root = createTempDirectory()
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale!!)
        deleteRecursively(root)
    }

    @Test
    @Throws(Exception::class)
    fun languageDirectoryFallsBackToJapaneseAndCustomEventsUseExactEnvelope() {
        Locale.setDefault(Locale.forLanguageTag("zz"))
        writeContent(
            "ja",
            ("; comment\n"
                    + "OnBoot,\\hboot\\e\n"
                    + "CustomEvent,custom value\n")
        )
        val shiori = createFixtureShiori()

        Assert.assertEquals(
            "SHIORI/3.0 200 OK\r\nSender: NanidroidShiori\r\nValue: \\hboot\\e"
                    + "\r\nCharset: UTF-8\r\n",
            shiori.request(request("OnBoot"))
        )
        Assert.assertEquals(
            "SHIORI/3.0 200 OK\r\nSender: NanidroidShiori\r\nValue: custom value"
                    + "\r\nCharset: UTF-8\r\n",
            shiori.request(request("CustomEvent"))
        )
    }

    @Test
    @Throws(Exception::class)
    fun ghostTransitionEventsUseJavaFormatReferenceZeroSubstitution() {
        Locale.setDefault(Locale.forLanguageTag("zz"))
        writeContent(
            "ja",
            "OnGhostChanging,switch to %s\n"
                    + "OnGhostChanged,now %s\n"
        )
        val shiori = createFixtureShiori()

        Assert.assertEquals(
            response("switch to Alice"),
            shiori.request(request("OnGhostChanging") + "Reference0: Alice\r\n\r\n")
        )
        Assert.assertEquals(
            response("now Bob"),
            shiori.request(request("OnGhostChanged") + "Reference0: Bob\r\n\r\n")
        )
    }

    @Test
    @Throws(Exception::class)
    fun onCloseHasContentOverrideAndLiteralFallback() {
        Locale.setDefault(Locale.forLanguageTag("zz"))
        writeContent("ja", "Malformed line without a separator\n")
        val fallback = createFixtureShiori()
        Assert.assertEquals(response("OnClose"), fallback.request(request("OnClose")))

        writeContent("ja", "OnClose,goodbye\n")
        val override = createFixtureShiori()
        Assert.assertEquals(response("goodbye"), override.request(request("OnClose")))
    }

    @Test
    @Throws(Exception::class)
    fun malformedContentCreatesAnEmptyTableAndUnknownEventIsNoContent() {
        Locale.setDefault(Locale.forLanguageTag("zz"))
        writeContent("ja", "; comment\nmissing separator\n")
        val shiori = createFixtureShiori()

        Assert.assertEquals(NanidroidShiori.RES_NO_CONTENT, shiori.request(request("NoSuchEvent")))
    }

    @Test
    @Throws(Exception::class)
    fun missingContentLeavesEventTableNullAndRequestCrashes() {
        Locale.setDefault(Locale.forLanguageTag("zz"))
        val shiori = createFixtureShiori()

        Assert.assertThrows<NullPointerException?>(
            NullPointerException::class.java,
            ThrowingRunnable { shiori.request(request("NoSuchEvent")) })
    }

    @Test
    @Throws(Exception::class)
    fun missingIdCrashesAfterTheRequestParserAcceptsTheHeader() {
        Locale.setDefault(Locale.forLanguageTag("zz"))
        writeContent("ja", "OnBoot,boot\n")
        val shiori = createFixtureShiori()

        Assert.assertThrows<NullPointerException?>(
            NullPointerException::class.java,
            ThrowingRunnable { shiori.request("GET SHIORI/3.0\r\n\r\n") })
    }

    @Throws(Exception::class)
    private fun writeContent(language: String, contents: String) {
        val directory = File(root, language)
        if (!directory.exists() && !directory.mkdirs()) {
            throw AssertionError("Could not create " + directory)
        }
        val content = File(directory, "content.txt")
        FileOutputStream(content).use { output ->
            output.write(contents.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun createFixtureShiori(): NanidroidShiori =
        NanidroidShiori.createContentFixture(root!!.path).also {
            Assert.assertEquals(ShioriLoadResult.Loaded, it.load())
        }

    companion object {
        private fun request(id: String): String {
            return "GET SHIORI/3.0\r\nID: " + id + "\r\n"
        }

        private fun response(value: String): String {
            return ("SHIORI/3.0 200 OK\r\nSender: NanidroidShiori\r\nValue: " + value
                    + "\r\nCharset: UTF-8\r\n")
        }

        @Throws(Exception::class)
        private fun createTempDirectory(): File {
            val file = File.createTempFile("nanidroid-shiori", "")
            if (!file.delete() || !file.mkdir()) {
                throw AssertionError("Could not create " + file)
            }
            return file
        }

        private fun deleteRecursively(file: File?) {
            if (file == null || !file.exists()) {
                return
            }
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    deleteRecursively(child)
                }
            }
            file.delete()
        }
    }
}
