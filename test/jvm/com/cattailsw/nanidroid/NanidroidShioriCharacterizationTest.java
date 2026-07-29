package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;


import com.cattailsw.nanidroid.shiori.NanidroidShiori;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Pins the built-in content.txt SHIORI adapter before its Kotlin migration. */
public class NanidroidShioriCharacterizationTest {
    @Rule
    public final HostAndroidStubRule androidStubs = new HostAndroidStubRule();
    private Locale originalLocale;
    private File root;

    @Before
    public void setUp() throws Exception {
        originalLocale = Locale.getDefault();
        root = createTempDirectory();
    }

    @After
    public void tearDown() {
        Locale.setDefault(originalLocale);
        deleteRecursively(root);
    }

    @Test
    public void languageDirectoryFallsBackToJapaneseAndCustomEventsUseExactEnvelope()
            throws Exception {
        Locale.setDefault(new Locale("zz"));
        writeContent("ja",
                "; comment\n"
                        + "OnBoot,\\hboot\\e\n"
                        + "CustomEvent,custom value\n");
        NanidroidShiori shiori = NanidroidShiori.createContentFixture(root.getPath());

        assertEquals(
                "SHIORI/3.0 200 OK\r\nSender: NanidroidShiori\r\nValue: \\hboot\\e"
                        + "\r\nCharset: UTF-8\r\n",
                shiori.request(request("OnBoot")));
        assertEquals(
                "SHIORI/3.0 200 OK\r\nSender: NanidroidShiori\r\nValue: custom value"
                        + "\r\nCharset: UTF-8\r\n",
                shiori.request(request("CustomEvent")));
    }

    @Test
    public void ghostTransitionEventsUseJavaFormatReferenceZeroSubstitution() throws Exception {
        Locale.setDefault(new Locale("zz"));
        writeContent("ja",
                "OnGhostChanging,switch to %s\n"
                        + "OnGhostChanged,now %s\n");
        NanidroidShiori shiori = NanidroidShiori.createContentFixture(root.getPath());

        assertEquals(response("switch to Alice"),
                shiori.request(request("OnGhostChanging") + "Reference0: Alice\r\n\r\n"));
        assertEquals(response("now Bob"),
                shiori.request(request("OnGhostChanged") + "Reference0: Bob\r\n\r\n"));
    }

    @Test
    public void onCloseHasContentOverrideAndLiteralFallback() throws Exception {
        Locale.setDefault(new Locale("zz"));
        writeContent("ja", "Malformed line without a separator\n");
        NanidroidShiori fallback = NanidroidShiori.createContentFixture(root.getPath());
        assertEquals(response("OnClose"), fallback.request(request("OnClose")));

        writeContent("ja", "OnClose,goodbye\n");
        NanidroidShiori override = NanidroidShiori.createContentFixture(root.getPath());
        assertEquals(response("goodbye"), override.request(request("OnClose")));
    }

    @Test
    public void malformedContentCreatesAnEmptyTableAndUnknownEventIsNoContent() throws Exception {
        Locale.setDefault(new Locale("zz"));
        writeContent("ja", "; comment\nmissing separator\n");
        NanidroidShiori shiori = NanidroidShiori.createContentFixture(root.getPath());

        assertEquals(NanidroidShiori.RES_NO_CONTENT, shiori.request(request("NoSuchEvent")));
    }

    @Test
    public void missingContentLeavesEventTableNullAndRequestCrashes() throws Exception {
        Locale.setDefault(new Locale("zz"));
        NanidroidShiori shiori = NanidroidShiori.createContentFixture(root.getPath());

        assertThrows(NullPointerException.class, () -> shiori.request(request("NoSuchEvent")));
    }

    @Test
    public void missingIdCrashesAfterTheRequestParserAcceptsTheHeader() throws Exception {
        Locale.setDefault(new Locale("zz"));
        writeContent("ja", "OnBoot,boot\n");
        NanidroidShiori shiori = NanidroidShiori.createContentFixture(root.getPath());

        assertThrows(NullPointerException.class,
                () -> shiori.request("GET SHIORI/3.0\r\n\r\n"));
    }

    private void writeContent(String language, String contents) throws Exception {
        File directory = new File(root, language);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new AssertionError("Could not create " + directory);
        }
        File content = new File(directory, "content.txt");
        try (FileOutputStream output = new FileOutputStream(content)) {
            output.write(contents.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String request(String id) {
        return "GET SHIORI/3.0\r\nID: " + id + "\r\n";
    }

    private static String response(String value) {
        return "SHIORI/3.0 200 OK\r\nSender: NanidroidShiori\r\nValue: " + value
                + "\r\nCharset: UTF-8\r\n";
    }

    private static File createTempDirectory() throws Exception {
        File file = File.createTempFile("nanidroid-shiori", "");
        if (!file.delete() || !file.mkdir()) {
            throw new AssertionError("Could not create " + file);
        }
        return file;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
