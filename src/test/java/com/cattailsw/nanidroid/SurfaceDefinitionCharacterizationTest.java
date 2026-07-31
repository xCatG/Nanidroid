package com.cattailsw.nanidroid;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cattailsw.nanidroid.compose.ComposeSurfaceImagePolicy;

/** Characterizes structural surface-definition loading without rendering resources. */
public class SurfaceDefinitionCharacterizationTest {
    @Rule
    public final HostAndroidStubRule androidStubs = new HostAndroidStubRule();
    private static final String GROUPED_SURFACES_FIXTURE =
            "surface0,surface10\n"
                    + "{\n"
                    + "collision0,1,2,11,22,Head\n"
                    + "0interval,talk\n"
                    + "0pattern0,-1,50,overlay,3,-4\n"
                    + "0pattern1,-1,75,overlay,-6,7\n"
                    + "}\n"
                    + "surface2\n"
                    + "{\n"
                    + "collision0,1,2,11,22,Head\n"
                    + "animation0.interval,talk\n"
                    + "animation0.pattern0,overlay,-1,50\n"
                    + "animation0.pattern1,overlay,-1,75\n"
                    + "}\n";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int fixtureIndex;

    @Test
    public void requiredMigrationInvariant_groupedAndAlternateSyntaxesLoadEquivalentModels()
            throws Exception {
        LoadedFixture loaded = loadGroupedSurfacesFixture();

        assertFalse(loaded.reader.error);
        assertArrayEquals(new String[] {"surfaces.txt"}, loaded.shellRoot.list());
        assertEquals(Arrays.asList("0", "2", "10"), sortedSurfaceIds(loaded.manager));

        ShellSurface surface0 = loaded.manager.getSurface("0");
        ShellSurface surface10 = loaded.manager.getSurface("10");
        ShellSurface surface2 = loaded.manager.getSurface("2");
        assertNotSame(surface0, surface10);

        List<String> expectedModel = Arrays.asList(
                "collision:0:Head:start=1,2:size=10x20",
                "animation-type:2=0",
                "animation:0:interval=2:exclusive=false",
                "frame:0:sid=null:type=-1:wait=50",
                "frame:1:sid=null:type=-1:wait=75");
        assertEquals(expectedModel, semanticSnapshot(surface0));
        assertEquals(expectedModel, semanticSnapshot(surface10));
        assertEquals(expectedModel, semanticSnapshot(surface2));

        assertEquals(expectedSurfacePath(loaded.shellRoot, 0), surface0.selfFilename);
        assertEquals(expectedSurfacePath(loaded.shellRoot, 10), surface10.selfFilename);
        assertEquals(expectedSurfacePath(loaded.shellRoot, 2), surface2.selfFilename);
        assertEquals(expectedPaddedSurfacePath(loaded.shellRoot, 0), surface0.bp2);
        assertEquals(expectedPaddedSurfacePath(loaded.shellRoot, 10), surface10.bp2);
        assertEquals(expectedPaddedSurfacePath(loaded.shellRoot, 2), surface2.bp2);
    }

    @Test
    public void requiredMigrationInvariant_managerUsesExactAndSpeakerDefaultSurfaces()
            throws Exception {
        LoadedFixture loaded = loadGroupedSurfacesFixture();
        ShellSurface surface0 = loaded.manager.getSurface("0");
        ShellSurface surface10 = loaded.manager.getSurface("10");
        ShellSurface surface2 = loaded.manager.getSurface("2");

        assertSame(surface2, loaded.manager.getSakuraSurface("2"));
        assertSame(surface2, loaded.manager.getKeroSurface("2"));
        assertSame(surface0, loaded.manager.getSakuraSurface("404"));
        assertSame(surface10, loaded.manager.getKeroSurface("404"));
        assertNull(loaded.manager.getSurface("404"));
    }

    @Test
    public void kotlinCatalog_startsEmptyAndPublishesAnExactSurface() {
        SurfaceManager manager = new SurfaceManager("synthetic-ghost");
        ShellSurface surface = new ShellSurface();

        assertEquals(0, manager.getTotalSurfaceCount());
        assertEquals(1, manager.addSurface("99", surface));
        assertSame(surface, manager.getSurface("99"));
        assertEquals(Collections.singleton("99"), manager.getSurfaceKeys());
    }

    @Test
    public void legacyObserved_resetFramesDiscardParsedOffsets() throws Exception {
        LoadedFixture loaded = loadGroupedSurfacesFixture();
        ShellSurface.Animation animation = loaded.manager.getSurface("0").animationTable.get("0");

        assertEquals(0, animation.frames.get(0).startX);
        assertEquals(0, animation.frames.get(0).startY);
        assertEquals(0, animation.frames.get(1).startX);
        assertEquals(0, animation.frames.get(1).startY);
    }

    @Test
    public void composeBoundary_snapshotPreservesSurfaceDefinitionSemantics() throws Exception {
        LoadedFixture loaded = loadGroupedSurfacesFixture();

        SurfaceDefinition definition = SurfaceDefinitionMapper.toSurfaceDefinition(
                loaded.manager.getSurface("0"));

        assertEquals(0, definition.getId());
        assertEquals(ShellSurface.S_TYPE_BASE, definition.getType());
        assertEquals(1, definition.getCollisions().size());
        assertEquals(0, definition.getCollisions().get(0).getId());
        assertEquals("Head", definition.getCollisions().get(0).getName());
        assertEquals(1, definition.getAnimations().size());
        assertEquals("0", definition.getAnimations().get(0).getId());
        assertEquals(ShellSurface.A_TYPE_TALK, definition.getAnimations().get(0).getInterval());
        assertEquals(2, definition.getAnimations().get(0).getFrames().size());
        assertEquals(ShellSurface.TYPE_RESET,
                definition.getAnimations().get(0).getFrames().get(0).getType());
    }

    @Test
    public void composeBoundary_preservesAlternativeAnimationTargets() throws Exception {
        File shellRoot = temporaryFolder.newFolder("alternative-animation");
        File descriptor = new File(shellRoot, "surfaces.txt");
        FileOutputStream output = new FileOutputStream(descriptor);
        try {
            output.write(("surface0\n{\n"
                    + "0pattern0,0,0,alternativestart,[1.2]\n"
                    + "}\n").getBytes(Charset.forName("Shift_JIS")));
        } finally {
            output.close();
        }

        SurfaceManager manager = new SurfaceManager("synthetic-ghost");
        new SurfaceReader(manager, shellRoot.getAbsolutePath() + File.separator,
                descriptor.getAbsolutePath());
        SurfaceAnimation animation = SurfaceDefinitionMapper.toSurfaceDefinition(
                manager.getSurface("0")).getAnimations().get(0);

        assertEquals(Arrays.asList("1", "2"), animation.getAlternativeAnimationIds());
        assertTrue(animation.getFrames().isEmpty());
    }

    @Test
    public void composeImageLayer_onlyAcceptsStaticBaseSurfaceStates() throws Exception {
        SurfaceDefinition base = SurfaceDefinitionMapper.toSurfaceDefinition(
                loadGroupedSurfacesFixture().manager.getSurface("0"));

        assertTrue(ComposeSurfaceImagePolicy.shouldRenderComposeSurface(base, null, false, false));
        assertFalse(ComposeSurfaceImagePolicy.shouldRenderComposeSurface(base, "0", false, false));
        assertFalse(ComposeSurfaceImagePolicy.shouldRenderComposeSurface(base, null, true, true));
        assertFalse(ComposeSurfaceImagePolicy.shouldRenderComposeSurface(null, null, false, false));
    }

    @Test
    public void platformHitTest_preservesAndroidRectBoundarySemantics() throws Exception {
        SurfaceDefinition definition = SurfaceDefinitionMapper.toSurfaceDefinition(
                loadGroupedSurfacesFixture().manager.getSurface("0"));

        assertEquals(0, SurfaceHitTest.findCollisionId(definition, 1, 2));
        assertEquals(0, SurfaceHitTest.findCollisionId(definition, 10, 21));
        assertEquals(-1, SurfaceHitTest.findCollisionId(definition, 11, 22));
        assertEquals(-1, SurfaceHitTest.findCollisionId(null, 1, 2));
    }

    private LoadedFixture loadGroupedSurfacesFixture() throws Exception {
        byte[] fixture = GROUPED_SURFACES_FIXTURE.getBytes(Charset.forName("Shift_JIS"));
        assertFixtureSha256(
                "86714964606059af816e2915317d411bc55a5066318542714ef31274382b4b6f",
                fixture);

        File shellRoot = temporaryFolder.newFolder("shell-" + fixtureIndex++);
        File descriptor = new File(shellRoot, "surfaces.txt");
        FileOutputStream output = new FileOutputStream(descriptor);
        try {
            output.write(fixture);
        } finally {
            output.close();
        }

        SurfaceManager manager = new SurfaceManager("synthetic-ghost");
        String rootPath = shellRoot.getAbsolutePath() + File.separator;
        SurfaceReader reader = new SurfaceReader(manager, rootPath, descriptor.getAbsolutePath());
        return new LoadedFixture(shellRoot, manager, reader);
    }

    private static List<String> sortedSurfaceIds(SurfaceManager manager) {
        List<String> ids = new ArrayList<String>(manager.getSurfaceKeys());
        Collections.sort(ids, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return Integer.valueOf(left).compareTo(Integer.valueOf(right));
            }
        });
        return ids;
    }

    private static List<String> semanticSnapshot(ShellSurface surface) {
        List<String> snapshot = new ArrayList<String>();

        for (Integer collisionId : new TreeSet<Integer>(surface.collisionAreas.keySet())) {
            ShellSurface.CollisionArea collision = surface.collisionAreas.get(collisionId);
            snapshot.add(
                    "collision:" + collision.id + ":" + collision.name
                            + ":start=" + collision.startX + "," + collision.startY
                            + ":size=" + collision.W + "x" + collision.H);
        }

        for (Integer type : new TreeSet<Integer>(surface.animationTypeTable.keySet())) {
            snapshot.add("animation-type:" + type + "=" + surface.animationTypeTable.get(type));
        }

        for (String animationId : new TreeSet<String>(surface.animationTable.keySet())) {
            ShellSurface.Animation animation = surface.animationTable.get(animationId);
            snapshot.add(
                    "animation:" + animation.id + ":interval=" + animation.interval
                            + ":exclusive=" + animation.exclusive);
            for (int index = 0; index < animation.frames.size(); index++) {
                ShellSurface.AnimationFrame frame = animation.frames.get(index);
                snapshot.add(
                        "frame:" + index + ":sid=" + frame.sid + ":type=" + frame.frameType
                                + ":wait=" + frame.time);
            }
        }
        return snapshot;
    }

    private static String expectedSurfacePath(File root, int id) {
        return new File(root, "surface" + id + ".png").getAbsolutePath();
    }

    private static String expectedPaddedSurfacePath(File root, int id) {
        return new File(root, String.format("surface%04d.png", id)).getAbsolutePath();
    }

    private static void assertFixtureSha256(String expected, byte[] fixture) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(fixture);
        StringBuilder actual = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            actual.append(String.format("%02x", value & 0xff));
        }
        assertEquals("Synthetic fixture bytes changed", expected, actual.toString());
    }

    private static final class LoadedFixture {
        final File shellRoot;
        final SurfaceManager manager;
        final SurfaceReader reader;

        LoadedFixture(File shellRoot, SurfaceManager manager, SurfaceReader reader) {
            this.shellRoot = shellRoot;
            this.manager = manager;
            this.reader = reader;
        }
    }
}
