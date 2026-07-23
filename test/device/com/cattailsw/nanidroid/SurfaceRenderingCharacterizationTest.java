package com.cattailsw.nanidroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.test.InstrumentationTestCase;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Characterizes deterministic surface pixels through the real Android graphics
 * framework. The fixtures are original synthetic color matrices, encoded once
 * as tiny PNG byte arrays so the decoder input is stable across platform versions.
 */
public final class SurfaceRenderingCharacterizationTest extends InstrumentationTestCase {
    private static final String PADDED_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAGUlEQVR42mP4z/D/"
                    + "/38Ghv8gmgFCg0X+AwDBWA7yYJOYMAAAAABJRU5ErkJggg==";
    private static final String PADDED_PNG_SHA256 =
            "bc7cc462b23cb8bc91f9f9154a95c72dbf3e52673fa864427daafc71c0ebbe50";
    private static final int[] PADDED_RENDERED_PIXELS = {
        Color.TRANSPARENT, 0xffff0000, Color.TRANSPARENT,
        0xff0000ff, 0xff00ff00, 0xffffff00,
    };

    private static final String ELEMENT_BASE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAYAAAC09K7GAAAAGklEQVR42mP4z/D/"
                    + "v4JDAxiD2AwwDgxjCAAAjIUYsb01IzkAAAAASUVORK5CYII=";
    private static final String ELEMENT_BASE_PNG_SHA256 =
            "57d054fb3911ecf5a663fcf7cb7181e61d05a492cc71d5b0597fa672f5f8aa53";
    private static final String ELEMENT_OVERLAY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFUlEQVR42mNg+P//"
                    + "/38GBjACsf4DAFe6Cffw59yNAAAAAElFTkSuQmCC";
    private static final String ELEMENT_OVERLAY_PNG_SHA256 =
            "b2584b961c275a89922cb32f00e11b6c9f637d9761738e097d37ca9d26c4ac19";
    private static final int BLUE = 0xff204080;
    private static final int[] ELEMENT_RENDERED_PIXELS = {
        Color.TRANSPARENT, BLUE, BLUE, Color.TRANSPARENT,
        BLUE, BLUE, 0xffff0000, BLUE,
        BLUE, 0xff00ff00, 0xffffff00, BLUE,
    };

    private File fixtureRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        fixtureRoot = new File(
                getInstrumentation().getTargetContext().getCacheDir(),
                "d7a-surface-rendering-" + System.nanoTime());
        assertTrue("Could not create fixture directory", fixtureRoot.mkdirs());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            deleteRecursively(fixtureRoot);
        } finally {
            super.tearDown();
        }
    }

    public void testRequiredMigrationInvariant_baseSurfaceUsesUpperLeftColorKeyAndPaddedFallback()
            throws Exception {
        File paddedSurface = writeFixture(
                "surface0007.png", PADDED_PNG_BASE64, PADDED_PNG_SHA256);
        ShellSurface surface = new ShellSurface(rootPath(), 7);

        assertEquals(paddedSurface.getAbsolutePath(), surface.selfFilename);
        assertEquals(3, surface.origW);
        assertEquals(2, surface.origH);
        assertPixelMatrix(
                PADDED_RENDERED_PIXELS,
                renderPixels(surface.getSurfaceDrawable(resources()), 3, 2));
    }

    public void testRequiredMigrationInvariant_elementSurfaceComposesDeclaredLayersAtOffsets()
            throws Exception {
        writeFixture("base.png", ELEMENT_BASE_PNG_BASE64, ELEMENT_BASE_PNG_SHA256);
        writeFixture(
                "overlay.png",
                ELEMENT_OVERLAY_PNG_BASE64,
                ELEMENT_OVERLAY_PNG_SHA256);
        ShellSurface surface = new ShellSurface(
                rootPath(),
                8,
                Arrays.asList(
                        "element0,base,base.png,0,0",
                        "element1,overlay,overlay.png,1,1"));

        assertEquals(4, surface.origW);
        assertEquals(3, surface.origH);
        assertPixelMatrix(
                ELEMENT_RENDERED_PIXELS,
                renderPixels(surface.getSurfaceDrawable(resources()), 4, 3));
    }

    private android.content.res.Resources resources() {
        return getInstrumentation().getTargetContext().getResources();
    }

    private String rootPath() {
        return fixtureRoot.getAbsolutePath() + File.separator;
    }

    private File writeFixture(String filename, String encoded, String expectedSha256)
            throws Exception {
        byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
        assertEquals("Synthetic fixture bytes changed", expectedSha256, sha256(bytes));
        File fixture = new File(fixtureRoot, filename);
        FileOutputStream output = new FileOutputStream(fixture);
        try {
            output.write(bytes);
        } finally {
            output.close();
        }
        return fixture;
    }

    private static int[] renderPixels(Drawable drawable, int width, int height) {
        assertNotNull("Production returned no drawable", drawable);
        Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rendered);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        int[] pixels = new int[width * height];
        rendered.getPixels(pixels, 0, width, 0, 0, width, height);
        rendered.recycle();
        return pixels;
    }

    private static void assertPixelMatrix(int[] expected, int[] actual) {
        assertEquals("Pixel count changed", expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(
                    "ARGB pixel changed at row-major index " + index,
                    expected[index],
                    actual[index]);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte part : digest) {
            value.append(String.format("%02x", part & 0xff));
        }
        return value.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        assertTrue("Could not delete " + file, file.delete());
    }
}
