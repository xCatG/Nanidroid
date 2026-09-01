package com.cattailsw.nanidroid

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.surface.CollisionShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GhostPreparationTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `preparation publishes immutable metadata and catalog defaults`() {
        val root = createFixtureGhost()

        val prepared = GhostPreparer(null).prepare(41L, "fixture", root.canonicalFile)

        assertEquals(41L, prepared.operationId)
        assertEquals(root.canonicalFile, prepared.canonicalRoot)
        assertEquals("fixture", prepared.id)
        assertEquals("Fixture", prepared.name)
        assertEquals(GhostEngine.Yaya, prepared.engine)
        assertTrue(SurfaceCatalog::class.java.methods.none {
            it.name.startsWith("add") || it.name.startsWith("set")
        })
        assertTrue(prepared.surfaces.definitionsForTesting().values.all {
            it::class == SurfaceDefinition::class
        })
        assertSame(
            prepared.surfaces.definition("0"),
            prepared.surfaces.sakuraDefinition("missing"),
        )
        assertSame(
            prepared.surfaces.definition("10"),
            prepared.surfaces.keroDefinition("missing"),
        )
    }

    @Test
    fun `descriptor without shiori uses Kawari when kawarirc exists`() {
        val root = temporaryFolder.newFolder("legacy-kawari")
        val ghostMaster = File(root, "ghost/master").apply { mkdirs() }
        val shellMaster = File(root, "shell/master").apply { mkdirs() }
        File(ghostMaster, "descript.txt").writeText(
            "charset,UTF-8\nname,Legacy Kawari\n",
        )
        File(ghostMaster, "kawarirc.kis").writeText("# legacy Kawari fixture\n")
        File(shellMaster, "descript.txt").writeText("charset,UTF-8\nname,master\n")
        File(shellMaster, "surfaces.txt").writeText("")

        val prepared = GhostPreparer(null).prepare(52L, "legacy-kawari", root.canonicalFile)

        assertEquals(GhostEngine.Kawari, prepared.engine)
    }

    @Test
    fun `surface catalog copies polygon points and rejects published mutation`() {
        val sourcePoints = mutableListOf(
            IntOffset(0, 0),
            IntOffset(4, 0),
            IntOffset(0, 4),
        )
        val source = SurfaceDefinition(
            id = 0,
            type = 0,
            imagePath = null,
            fallbackImagePath = null,
            width = 0,
            height = 0,
            collisions = listOf(
                SurfaceCollision(
                    id = 0,
                    identifier = "Polygon",
                    shape = CollisionShape.Polygon(sourcePoints),
                    authoredOrder = 0,
                ),
            ),
            animations = emptyList(),
            elements = emptyList(),
        )

        val catalog = SurfaceCatalog.freeze(mapOf("0" to source))
        sourcePoints[0] = IntOffset(99, 99)
        val publishedPoints = ((catalog.definition("0")!!.collisions.single().shape as CollisionShape.Polygon).points)

        assertEquals(IntOffset(0, 0), publishedPoints.first())
        val mutablePoints = publishedPoints as MutableList<IntOffset>
        try {
            mutablePoints += IntOffset(8, 8)
            throw AssertionError("Published polygon points accepted mutation")
        } catch (_: UnsupportedOperationException) {
            // The public collection is a read-only snapshot, not the source list.
        }
    }

    @Test
    fun `unreadable Nanidroid content preserves legacy present empty fallback`() {
        val unreadableContent = temporaryFolder.newFolder("content.txt")

        val content = GhostPreparer(null).readNanidroidContentFile(unreadableContent)

        assertTrue(content.isEmpty())
        assertTrue((content as NanidroidContentPresence).contentFilePresent)
    }

    @Test
    fun `preparation source has no adapter or native reference`() {
        val source = File("src/main/kotlin/com/cattailsw/nanidroid/GhostPreparation.kt").readText()

        assertTrue("GhostPreparation must not mention Shiori", "Shiori" !in source)
        assertTrue("GhostPreparation must not mention ShioriFactory", "ShioriFactory" !in source)
        assertTrue(
            "GhostPreparation must not contain a standalone native reference",
            !Regex("\\bnative\\b").containsMatchIn(source),
        )
    }

    @Test
    fun `function backed test preparer receives exact operation identity and root`() {
        val root = temporaryFolder.newFolder("scripted").canonicalFile
        val calls = mutableListOf<Triple<Long, String, File>>()
        val expected = PreparedGhost(
            operationId = 73L,
            id = "scripted",
            canonicalRoot = root,
            name = "Scripted",
            shellName = "master",
            crafterName = null,
            sakuraName = null,
            keroName = null,
            surfaces = SurfaceCatalog.freeze(emptyMap()),
            ghostDescriptor = emptyMap(),
            shellDescriptor = null,
            engine = GhostEngine.Unsupported,
            nanidroidContent = emptyMap(),
        )
        val preparer = GhostPreparer { operationId, ghostId, canonicalRoot ->
            calls += Triple(operationId, ghostId, canonicalRoot)
            expected
        }

        assertSame(expected, preparer.prepare(73L, "scripted", root))
        assertEquals(listOf(Triple(73L, "scripted", root)), calls)
    }

    private fun createFixtureGhost(): File {
        val root = temporaryFolder.newFolder("fixture")
        val ghostMaster = File(root, "ghost/master").apply { mkdirs() }
        val shellMaster = File(root, "shell/master").apply { mkdirs() }
        File(ghostMaster, "descript.txt").writeText(
            "charset,UTF-8\nname,Fixture\ncraftman,Fixture Crafter\nsakura.name,Sakura\nkero.name,Kero\nshiori,yaya.dll\n",
        )
        File(shellMaster, "descript.txt").writeText("charset,UTF-8\nname,Fixture Shell\n")
        File(shellMaster, "surfaces.txt").writeText(
            "surface0,surface10\n{\ncollisionex0,Polygon,polygon,0,0,4,0,0,4\n}\n",
        )
        return root
    }
}
