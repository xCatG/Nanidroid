package com.cattailsw.nanidroid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.test.InstrumentationTestCase;
import android.util.Base64;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Hashtable;

/**
 * Characterizes deterministic animation assembly and view dispatch through the
 * real Android graphics framework. Wall-clock advancement and random animation
 * selection are intentionally outside this boundary.
 */
public final class SurfaceAnimationExecutionCharacterizationTest
        extends InstrumentationTestCase {
    private static final String BASE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAQAAAADCAYAAAC09K7GAAAAGklEQVR42mP4z/D/"
                    + "v4JDAxiD2AwwDgxjCAAAjIUYsb01IzkAAAAASUVORK5CYII=";
    private static final String BASE_PNG_SHA256 =
            "57d054fb3911ecf5a663fcf7cb7181e61d05a492cc71d5b0597fa672f5f8aa53";
    private static final String OVERLAY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAFUlEQVR42mNg+P//"
                    + "/38GBjACsf4DAFe6Cffw59yNAAAAAElFTkSuQmCC";
    private static final String OVERLAY_PNG_SHA256 =
            "b2584b961c275a89922cb32f00e11b6c9f637d9761738e097d37ca9d26c4ac19";
    private static final int BLUE = 0xff204080;
    private static final int[] BASE_PIXELS = {
        Color.TRANSPARENT, BLUE, BLUE, Color.TRANSPARENT,
        BLUE, BLUE, BLUE, BLUE,
        BLUE, BLUE, BLUE, BLUE,
    };
    private static final int[] OVERLAY_FRAME_PIXELS = {
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
                "d7b-animation-execution-" + System.nanoTime());
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

    public void testRequiredMigrationInvariant_animationAssemblesFramesInOrderWithExactDurationsAndPixels()
            throws Exception {
        File baseFile = writeFixture("surface0.png", BASE_PNG_BASE64, BASE_PNG_SHA256);
        writeFixture("surface1.png", OVERLAY_PNG_BASE64, OVERLAY_PNG_SHA256);
        TestShellSurface surface = new TestShellSurface(rootPath(), 0);
        ShellSurface referencedSurface = new ShellSurface(rootPath(), 1);
        SurfaceManager manager = new SurfaceManager("test");
        manager.addSurface("0", surface);
        manager.addSurface("1", referencedSurface);
        configureTalkAnimation(surface, baseFile);

        AnimationDrawable animation = (AnimationDrawable) surface.getAnimation(
                "3", resources(), manager);

        assertNotNull("Production returned no animation", animation);
        assertEquals(2, animation.getNumberOfFrames());
        assertEquals(37, animation.getDuration(0));
        assertEquals(83, animation.getDuration(1));
        assertPixelMatrix(
                OVERLAY_FRAME_PIXELS,
                renderPixels(animation.getFrame(0), 4, 3));
        assertPixelMatrix(
                BASE_PIXELS,
                renderPixels(animation.getFrame(1), 4, 3));
    }

    public void testRequiredMigrationInvariant_viewBindsResetsAndDispatchesSingleTalkingAnimation()
            throws Exception {
        final File baseFile = writeFixture(
                "surface0.png", BASE_PNG_BASE64, BASE_PNG_SHA256);
        writeFixture("surface1.png", OVERLAY_PNG_BASE64, OVERLAY_PNG_SHA256);
        final TestShellSurface surface = new TestShellSurface(rootPath(), 0);
        final ShellSurface nextSurface = new ShellSurface(rootPath(), 1);
        final SurfaceManager manager = new SurfaceManager("test");
        manager.addSurface("0", surface);
        manager.addSurface("1", nextSurface);
        configureTalkAnimation(surface, baseFile);

        getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                AnimationDrawable started = null;
                try {
                    SakuraView view = new SakuraView(
                            getInstrumentation().getTargetContext());
                    view.setMgr(manager);
                    view.changeSurface("0");

                    assertEquals("0", view.currentSurfaceId);
                    assertSame(surface, view.currentSurface);
                    assertEquals(View.VISIBLE, view.getVisibility());
                    assertSame(
                            surface.getSurfaceDrawable(resources()),
                            view.getDrawable());
                    assertNull(view.animation);
                    assertNull(view.currentAnimationId);

                    view.startTalkingAnimation();
                    started = view.animation;

                    assertNotNull("Talking animation was not loaded", started);
                    assertEquals("3", view.currentAnimationId);
                    assertSame(
                            surface.getAnimation("3", resources(), manager),
                            started);
                    assertSame(started, view.getDrawable());
                    assertTrue("Talking animation was not started", started.isRunning());
                    assertSame(started.getFrame(0), started.getCurrent());

                    view.changeSurface("1");

                    assertEquals("1", view.currentSurfaceId);
                    assertSame(nextSurface, view.currentSurface);
                    assertSame(
                            nextSurface.getSurfaceDrawable(resources()),
                            view.getDrawable());
                    assertNull(view.animation);
                    assertNull(view.currentAnimationId);
                } finally {
                    if (started != null) {
                        started.stop();
                    }
                }
            }
        });
    }

    private void configureTalkAnimation(TestShellSurface surface, File poisonFile) {
        surface.animationTable = new Hashtable<String, ShellSurface.Animation>();
        surface.animationTypeTable = new Hashtable<Integer, String>();

        ShellSurface.Animation animation = surface.createAnimation(
                "3", ShellSurface.A_TYPE_TALK);
        ShellSurface.AnimationFrame overlay = surface.createAnimationFrame();
        overlay.sid = "1";
        overlay.filePath = poisonFile.getAbsolutePath();
        overlay.time = 37;
        overlay.frameType = ShellSurface.TYPE_OVERLAY;
        overlay.startX = 1;
        overlay.startY = 1;
        animation.addFrame(0, overlay);

        ShellSurface.AnimationFrame reset = surface.createAnimationFrame();
        reset.time = 83;
        reset.frameType = ShellSurface.TYPE_RESET;
        animation.addFrame(1, reset);

        surface.animationTable.put("3", animation);
        surface.animationTypeTable.put(ShellSurface.A_TYPE_TALK, "3");
    }

    /**
     * Avoids javac lowering a qualified inner-class construction through
     * java.util.Objects, which is newer than the app's API 15 compile surface.
     */
    private static final class TestShellSurface extends ShellSurface {
        TestShellSurface(String path, int id) {
            super(path, id);
        }

        Animation createAnimation(String id, int interval) {
            return new Animation(id, interval);
        }

        AnimationFrame createAnimationFrame() {
            return new AnimationFrame();
        }
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
