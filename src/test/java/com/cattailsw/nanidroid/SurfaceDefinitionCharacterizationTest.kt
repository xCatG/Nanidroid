package com.cattailsw.nanidroid

import com.cattailsw.nanidroid.compose.shouldRenderComposeSurface
import org.junit.Assert
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Collections
import java.util.TreeSet

/** Characterizes structural surface-definition loading without rendering resources.  */
class SurfaceDefinitionCharacterizationTest {
    @Rule
    @JvmField
    val androidStubs: com.cattailsw.nanidroid.HostAndroidStubRule =
        com.cattailsw.nanidroid.HostAndroidStubRule()

    @Rule
    @JvmField
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private var fixtureIndex = 0

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_groupedAndAlternateSyntaxesLoadEquivalentModels() {
        val loaded = loadGroupedSurfacesFixture()

        Assert.assertFalse(loaded.reader.error)
        Assert.assertArrayEquals(arrayOf<String>("surfaces.txt"), loaded.shellRoot.list())
        Assert.assertEquals(
            mutableListOf<String>("0", "2", "10"),
            sortedSurfaceIds(loaded.manager)
        )

        val surface0: com.cattailsw.nanidroid.ShellSurface = requireNotNull(loaded.manager.getSurface("0"))
        val surface10: com.cattailsw.nanidroid.ShellSurface = requireNotNull(loaded.manager.getSurface("10"))
        val surface2: com.cattailsw.nanidroid.ShellSurface = requireNotNull(loaded.manager.getSurface("2"))
        Assert.assertNotSame(surface0, surface10)

        val expectedModel = mutableListOf<String>(
            "collision:0:Head:start=1,2:size=10x20",
            "animation-type:2=0",
            "animation:0:interval=2:exclusive=false",
            "frame:0:sid=null:type=-1:wait=50",
            "frame:1:sid=null:type=-1:wait=75"
        )
        Assert.assertEquals(expectedModel, semanticSnapshot(surface0))
        Assert.assertEquals(expectedModel, semanticSnapshot(surface10))
        Assert.assertEquals(expectedModel, semanticSnapshot(surface2))

        Assert.assertEquals(expectedSurfacePath(loaded.shellRoot, 0), surface0.selfFilename)
        Assert.assertEquals(expectedSurfacePath(loaded.shellRoot, 10), surface10.selfFilename)
        Assert.assertEquals(expectedSurfacePath(loaded.shellRoot, 2), surface2.selfFilename)
        Assert.assertEquals(expectedPaddedSurfacePath(loaded.shellRoot, 0), surface0.bp2)
        Assert.assertEquals(expectedPaddedSurfacePath(loaded.shellRoot, 10), surface10.bp2)
        Assert.assertEquals(expectedPaddedSurfacePath(loaded.shellRoot, 2), surface2.bp2)
    }

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_managerUsesExactAndSpeakerDefaultSurfaces() {
        val loaded = loadGroupedSurfacesFixture()
        val surface0: com.cattailsw.nanidroid.ShellSurface = requireNotNull(loaded.manager.getSurface("0"))
        val surface10: com.cattailsw.nanidroid.ShellSurface = requireNotNull(loaded.manager.getSurface("10"))
        val surface2: com.cattailsw.nanidroid.ShellSurface = requireNotNull(loaded.manager.getSurface("2"))

        Assert.assertSame(surface2, loaded.manager.getSakuraSurface("2"))
        Assert.assertSame(surface2, loaded.manager.getKeroSurface("2"))
        Assert.assertSame(surface0, loaded.manager.getSakuraSurface("404"))
        Assert.assertSame(surface10, loaded.manager.getKeroSurface("404"))
        Assert.assertNull(loaded.manager.getSurface("404"))
    }

    @Test
    fun kotlinCatalog_startsEmptyAndPublishesAnExactSurface() {
        val manager: SurfaceManager = SurfaceManager("synthetic-ghost")
        val surface: com.cattailsw.nanidroid.ShellSurface = com.cattailsw.nanidroid.ShellSurface()

        Assert.assertEquals(0, manager.getTotalSurfaceCount().toLong())
        Assert.assertEquals(1, manager.addSurface("99", surface).toLong())
        Assert.assertSame(surface, requireNotNull(manager.getSurface("99")))
        Assert.assertEquals(mutableSetOf<String>("99"), manager.getSurfaceKeys())
    }

    @Test
    @Throws(Exception::class)
    fun legacyObserved_resetFramesDiscardParsedOffsets() {
        val loaded = loadGroupedSurfacesFixture()
        val animation: com.cattailsw.nanidroid.ShellSurface.Animation =
            requireNotNull(requireNotNull(loaded.manager.getSurface("0")).animationTable!!["0"])

        Assert.assertEquals(0, animation.frames!![0].startX.toLong())
        Assert.assertEquals(0, animation.frames!![0].startY.toLong())
        Assert.assertEquals(0, animation.frames!![1].startX.toLong())
        Assert.assertEquals(0, animation.frames!![1].startY.toLong())
    }

    @Test
    @Throws(Exception::class)
    fun composeBoundary_snapshotPreservesSurfaceDefinitionSemantics() {
        val loaded = loadGroupedSurfacesFixture()

        val definition: com.cattailsw.nanidroid.SurfaceDefinition =
            requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        Assert.assertEquals(0, definition.id.toLong())
        Assert.assertEquals(
            com.cattailsw.nanidroid.ShellSurface.S_TYPE_BASE.toLong(),
            definition.type.toLong()
        )
        Assert.assertEquals(1, definition.collisions.size.toLong())
        Assert.assertEquals(0, definition.collisions.get(0).id)
        Assert.assertEquals("Head", definition.collisions.get(0).name)
        Assert.assertEquals(1, definition.animations.size.toLong())
        Assert.assertEquals("0", definition.animations.get(0).id)
        Assert.assertEquals(
            com.cattailsw.nanidroid.ShellSurface.A_TYPE_TALK,
            definition.animations.get(0).interval
        )
        Assert.assertEquals(2, definition.animations.get(0).frames.size)
        Assert.assertEquals(
            com.cattailsw.nanidroid.ShellSurface.TYPE_RESET,
            definition.animations.get(0).frames.get(0).type
        )
    }

    @Test
    @Throws(Exception::class)
    fun composeBoundary_preservesAlternativeAnimationTargets() {
        val shellRoot = temporaryFolder.newFolder("alternative-animation")
        val descriptor = File(shellRoot, "surfaces.txt")
        val output = FileOutputStream(descriptor)
        try {
            output.write(
                (("surface0\n{\n"
                        + "0pattern0,0,0,alternativestart,[1.2]\n"
                        + "}\n")).toByteArray(Charset.forName("Shift_JIS"))
            )
        } finally {
            output.close()
        }

        val manager: SurfaceManager = SurfaceManager("synthetic-ghost")
        com.cattailsw.nanidroid.SurfaceReader(
            manager, shellRoot.absolutePath + File.separator,
            descriptor.absolutePath
        )
        val animation: com.cattailsw.nanidroid.SurfaceAnimation =
            requireNotNull(manager.getSurface("0")).toSurfaceDefinition().animations.get(0)

        Assert.assertEquals(mutableListOf<String>("1", "2"), animation.alternativeAnimationIds)
        Assert.assertTrue(animation.frames.isEmpty())
    }

    @Test
    fun animationPatternIdsAreBoundedSortedAndNeverMaterializeSparsePadding() {
        val entries = mutableListOf<String?>(
            "0pattern5,-1,50,overlay,0,0",
            "animation0.pattern2,overlay,-1,20",
            "0pattern2,-1,21,overlay,0,0",
            "animation0.pattern3,overlay,-1,30",
            "1pattern4095,-1,10,overlay,0,0",
            "2pattern4096,-1,10,overlay,0,0",
            "3pattern-1,-1,10,overlay,0,0",
            "animation4.pattern999999999999999999999999,overlay,-1,10",
            "animation5.pattern100000,overlay,-1,10",
            "animation6.pattern2147483647,overlay,-1,10",
        )
        repeat(100) { animation ->
            entries += "animation${animation + 10}.pattern4095,overlay,-1,10"
        }

        val surface = ShellSurface("", null, 0, entries, probeBitmap = false)

        Assert.assertEquals(listOf(20, 21, 30, 50), surface.animationTable!!["0"]!!.frames!!.map { it.time })
        Assert.assertEquals(1, surface.getAnimationFrameCount(1))
        Assert.assertEquals(0, surface.getAnimationFrameCount(2))
        Assert.assertEquals(0, surface.getAnimationFrameCount(3))
        Assert.assertEquals(0, surface.getAnimationFrameCount(4))
        Assert.assertEquals(0, surface.getAnimationFrameCount(5))
        Assert.assertEquals(0, surface.getAnimationFrameCount(6))
        Assert.assertEquals(100, (10 until 110).sumOf(surface::getAnimationFrameCount))
        Assert.assertEquals(
            listOf(20, 21, 30, 50),
            surface.toSurfaceDefinition().animations.single { it.id == "0" }.frames.map { it.durationMillis },
        )
    }

    @Test
    @Throws(Exception::class)
    fun composeImageLayer_onlyAcceptsStaticBaseSurfaceStates() {
        val base: com.cattailsw.nanidroid.SurfaceDefinition =
            requireNotNull(loadGroupedSurfacesFixture().manager.getSurface("0")).toSurfaceDefinition()

        Assert.assertTrue(
            shouldRenderComposeSurface(
                base,
                null,
                false,
                false
            )
        )
        Assert.assertFalse(
            shouldRenderComposeSurface(
                base,
                "0",
                false,
                false
            )
        )
        Assert.assertFalse(
            shouldRenderComposeSurface(
                base,
                null,
                true,
                true
            )
        )
        Assert.assertFalse(
            shouldRenderComposeSurface(
                null,
                null,
                false,
                false
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun platformHitTest_preservesAndroidRectBoundarySemantics() {
        val definition: com.cattailsw.nanidroid.SurfaceDefinition =
            requireNotNull(loadGroupedSurfacesFixture().manager.getSurface("0")).toSurfaceDefinition()

        Assert.assertEquals(0, findCollisionId(definition, 1, 2).toLong())
        Assert.assertEquals(0, findCollisionId(definition, 10, 21).toLong())
        Assert.assertEquals(-1, findCollisionId(definition, 11, 22).toLong())
        Assert.assertEquals(-1, findCollisionId(null, 1, 2).toLong())
    }

    @Throws(Exception::class)
    private fun loadGroupedSurfacesFixture(): LoadedFixture {
        val fixture = GROUPED_SURFACES_FIXTURE.toByteArray(Charset.forName("Shift_JIS"))
        assertFixtureSha256(
            "86714964606059af816e2915317d411bc55a5066318542714ef31274382b4b6f",
            fixture
        )

        val shellRoot = temporaryFolder.newFolder("shell-" + fixtureIndex++)
        val descriptor = File(shellRoot, "surfaces.txt")
        val output = FileOutputStream(descriptor)
        try {
            output.write(fixture)
        } finally {
            output.close()
        }

        val manager: SurfaceManager = SurfaceManager("synthetic-ghost")
        val rootPath = shellRoot.absolutePath + File.separator
        val reader: com.cattailsw.nanidroid.SurfaceReader =
            com.cattailsw.nanidroid.SurfaceReader(manager, rootPath, descriptor.absolutePath)
        return LoadedFixture(shellRoot, manager, reader)
    }

    private class LoadedFixture(
        val shellRoot: File,
        manager: SurfaceManager,
        reader: com.cattailsw.nanidroid.SurfaceReader
    ) {
        val manager: SurfaceManager
        val reader: com.cattailsw.nanidroid.SurfaceReader

        init {
            this.manager = manager
            this.reader = reader
        }
    }

    companion object {
        private val GROUPED_SURFACES_FIXTURE = ("surface0,surface10\n"
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
                + "}\n")

        private fun sortedSurfaceIds(manager: SurfaceManager): MutableList<String> {
            val ids: MutableList<String> = ArrayList<String>(manager.getSurfaceKeys())
            Collections.sort(ids, object : Comparator<String> {
                override fun compare(left: String, right: String): Int {
                    return left.toInt().compareTo(right.toInt())
                }
            })
            return ids
        }

        private fun semanticSnapshot(surface: com.cattailsw.nanidroid.ShellSurface): MutableList<String> {
            val snapshot: MutableList<String> = ArrayList<String>()

            for (collisionId in TreeSet<Int>(surface.collisionAreas.keys)) {
                val collision: com.cattailsw.nanidroid.ShellSurface.CollisionArea =
                    requireNotNull(surface.collisionAreas[collisionId])
                snapshot.add(
                    ("collision:" + collision.id + ":" + collision.name
                            + ":start=" + collision.startX + "," + collision.startY
                            + ":size=" + collision.W + "x" + collision.H)
                )
            }

            for (type in TreeSet<Int>(surface.animationTypeTable!!.keys)) {
                snapshot.add("animation-type:" + type + "=" + surface.animationTypeTable!![type])
            }

            for (animationId in TreeSet<String>(surface.animationTable!!.keys)) {
                val animation: com.cattailsw.nanidroid.ShellSurface.Animation =
                    requireNotNull(surface.animationTable!![animationId])
                snapshot.add(
                    ("animation:" + animation.id + ":interval=" + animation.interval
                            + ":exclusive=" + animation.exclusive)
                )
                for (index in animation.frames!!.indices) {
                    val frame: com.cattailsw.nanidroid.ShellSurface.AnimationFrame =
                        animation.frames!![index]
                    snapshot.add(
                        ("frame:" + index + ":sid=" + frame.sid + ":type=" + frame.frameType
                                + ":wait=" + frame.time)
                    )
                }
            }
            return snapshot
        }

        private fun expectedSurfacePath(root: File?, id: Int): String {
            return File(root, "surface" + id + ".png").absolutePath
        }

        private fun expectedPaddedSurfacePath(root: File?, id: Int): String {
            return File(root, String.format("surface%04d.png", id)).absolutePath
        }

        @Throws(Exception::class)
        private fun assertFixtureSha256(expected: String?, fixture: ByteArray) {
            val digest = MessageDigest.getInstance("SHA-256").digest(fixture)
            val actual = StringBuilder(digest.size * 2)
            for (value in digest) {
                actual.append(String.format("%02x", value.toInt() and 0xff))
            }
            Assert.assertEquals("Synthetic fixture bytes changed", expected, actual.toString())
        }
    }
}
