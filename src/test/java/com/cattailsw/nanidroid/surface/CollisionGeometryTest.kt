package com.cattailsw.nanidroid.surface

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.cattailsw.nanidroid.HostAndroidStubRule
import com.cattailsw.nanidroid.ShellSurface
import com.cattailsw.nanidroid.SurfaceManager
import com.cattailsw.nanidroid.SurfaceReader
import com.cattailsw.nanidroid.toSurfaceDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class CollisionGeometryTest {
    @get:Rule
    val androidStubs = HostAndroidStubRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun authoredRectangleEndpointsNormalizeToOneHalfOpenShape() {
        val shape = CollisionShape.Rectangle.fromAuthored(10, 20, 0, 5)

        assertEquals(IntRect(0, 5, 11, 21), shape.bounds)
        assertTrue(shape.contains(IntOffset(0, 5)))
        assertTrue(shape.contains(IntOffset(10, 20)))
        assertFalse(shape.contains(IntOffset(11, 20)))
        assertFalse(shape.contains(IntOffset(10, 21)))
    }

    @Test
    fun ellipseAndCircleUseTheirExactEdgesInsteadOfBoundingRectangles() {
        val ellipse = CollisionShape.Ellipse.fromAuthored(4, 2, 0, 0)
        val circle = CollisionShape.Circle.fromAuthored(3, 4, 2)

        assertEquals(IntRect(0, 0, 5, 3), ellipse.bounds)
        assertTrue(ellipse.contains(IntOffset(0, 1)))
        assertTrue(ellipse.contains(IntOffset(4, 1)))
        assertTrue(ellipse.contains(IntOffset(2, 0)))
        assertTrue(ellipse.contains(IntOffset(2, 2)))
        assertFalse(ellipse.contains(IntOffset(0, 0)))
        assertFalse(ellipse.contains(IntOffset(4, 2)))

        assertEquals(IntRect(1, 2, 6, 7), circle.bounds)
        assertTrue(circle.contains(IntOffset(3, 4)))
        assertTrue(circle.contains(IntOffset(5, 4)))
        assertFalse(circle.contains(IntOffset(5, 5)))
    }

    @Test
    fun polygonUsesEvenOddFillAndTreatsVerticesAndEdgesAsHits() {
        val square = CollisionShape.Polygon(
            listOf(IntOffset(0, 0), IntOffset(4, 0), IntOffset(4, 4), IntOffset(0, 4)),
        )
        val crossing = CollisionShape.Polygon(
            listOf(IntOffset(0, 0), IntOffset(4, 4), IntOffset(0, 4), IntOffset(4, 0)),
        )

        assertTrue(square.contains(IntOffset(0, 0)))
        assertTrue(square.contains(IntOffset(2, 0)))
        assertTrue(square.contains(IntOffset(2, 2)))
        assertFalse(square.contains(IntOffset(5, 2)))

        assertTrue(crossing.contains(IntOffset(2, 1)))
        assertTrue(crossing.contains(IntOffset(2, 2)))
        assertTrue(crossing.contains(IntOffset(4, 4)))
        assertFalse(crossing.contains(IntOffset(1, 2)))
    }

    @Test
    fun degenerateSupportedShapesRetainOnlyTheirExactPointOrEdges() {
        val pointEllipse = CollisionShape.Ellipse.fromAuthored(7, 9, 7, 9)
        val pointCircle = CollisionShape.Circle.fromAuthored(4, 6, 0)
        val collinearPolygon = CollisionShape.Polygon(
            listOf(IntOffset(0, 0), IntOffset(2, 0), IntOffset(4, 0)),
        )

        assertTrue(pointEllipse.contains(IntOffset(7, 9)))
        assertFalse(pointEllipse.contains(IntOffset(8, 9)))
        assertTrue(pointCircle.contains(IntOffset(4, 6)))
        assertFalse(pointCircle.contains(IntOffset(4, 7)))
        assertTrue(collinearPolygon.contains(IntOffset(3, 0)))
        assertFalse(collinearPolygon.contains(IntOffset(3, 1)))
    }

    @Test(timeout = 1_000)
    fun exactGeometryMathStaysBoundedForHostileIntCoordinates() {
        val hugeRectangle = CollisionShape.Rectangle.fromAuthored(
            Int.MIN_VALUE,
            Int.MIN_VALUE,
            Int.MAX_VALUE - 1,
            Int.MAX_VALUE - 1,
        )
        val hugeCircle = CollisionShape.Circle.fromAuthored(0, 0, 1_000_000_000)
        val hugeEllipse = CollisionShape.Ellipse.fromAuthored(
            -1_000_000_000,
            -1_000_000_000,
            1_000_000_000,
            1_000_000_000,
        )
        val hugePolygon = CollisionShape.Polygon(
            listOf(
                IntOffset(Int.MIN_VALUE + 1, Int.MIN_VALUE + 1),
                IntOffset(Int.MAX_VALUE - 1, Int.MIN_VALUE + 2),
                IntOffset(Int.MIN_VALUE + 2, Int.MAX_VALUE - 1),
            ),
        )

        assertTrue(hugeRectangle.contains(IntOffset(0, 0)))
        assertTrue(hugeCircle.contains(IntOffset(1_000_000_000, 0)))
        assertTrue(hugeEllipse.contains(IntOffset(1_000_000_000, 0)))
        assertFalse(hugeEllipse.contains(IntOffset(1_000_000_000, 1_000_000_000)))
        assertTrue(hugePolygon.contains(IntOffset(0, 0)))
        assertNull(CollisionShape.Rectangle.fromAuthoredOrNull(0, 0, Int.MAX_VALUE, 1))
        assertNull(CollisionShape.Circle.fromAuthoredOrNull(Int.MAX_VALUE, 0, 1))
    }

    @Test
    fun readerPreservesPerFileSortAndCrossFileAuthoredPrecedence() {
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                descript
                {
                collision-sort,ascend
                }
                surface0
                {
                collision20,0,0,5,5,FirstTwenty
                }
                surface0
                {
                collision10,0,0,5,5,FirstTen
                }
            """.trimIndent(),
            "surfaces2.txt" to """
                descript
                {
                collision-sort,descend
                }
                surface0
                {
                collision5,0,0,5,5,SecondFive
                collision7,0,0,5,5,SecondSeven
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(listOf(10, 20, 7, 5), definition.collisions.map { it.id })
        assertEquals(
            listOf("FirstTen", "FirstTwenty", "SecondSeven", "SecondFive"),
            definition.collisions.map { it.identifier },
        )
        assertEquals(listOf(1, 0, 3, 2), definition.collisions.map { it.authoredOrder })
        assertTrue(loaded.reader.diagnostics.isEmpty())
    }

    @Test
    fun defaultSortKeepsEarlierAuthoredCollisionFrontmost() {
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                collision9,0,0,5,5,Earlier
                collision1,0,0,5,5,Later
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(listOf(9, 1), definition.collisions.map { it.id })
    }

    @Test
    fun identifiersPreserveCaseUnicodeAndInteriorTextAfterSyntaxWhitespaceIsTrimmed() {
        val legacyCollision = "collision0,0,0,1,1,  Head Area${" ".repeat(2)}"
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                $legacyCollision
                collisionex1,  顔・目  ,circle,4,4,2
                }
            """.trimIndent(),
        )
        val collisions = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition().collisions

        assertEquals(listOf("Head Area", "顔・目"), collisions.map { it.identifier })
        assertTrue(collisions.single { it.id == 1 }.shape.contains(IntOffset(4, 4)))
    }

    @Test
    fun blankAndMalformedCollisionDeclarationsAreDiagnosedWithoutLosingValidSiblings() {
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                collision0,0,0,1,1,ValidFirst
                collision1,0,0,1,1,
                collisionex2,   ,circle,4,4,2
                collisionfoo,0,0,1,1,MalformedLegacyKey
                collisionexfoo,MalformedExtendedKey,circle,4,4,2
                animation0.collisionfoo,0,0,1,1,MalformedAnimationKey
                animation0.collisionexfoo,Name,circle,4,4,2
                collision3,5,5,6,6,ValidSibling
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(listOf(0, 3), definition.collisions.map { it.id })
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("collision1,0,0,1,1,"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("collisionex2,"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("MalformedLegacyKey"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("MalformedExtendedKey"))
        assertEquals(SurfaceDiagnosticReason.UNSUPPORTED, loaded.reasonFor("animation0.collisionfoo,"))
        assertEquals(SurfaceDiagnosticReason.UNSUPPORTED, loaded.reasonFor("animation0.collisionexfoo,"))
    }

    @Test
    fun duplicateMalformedAndUnsupportedEntriesAreDiagnosedWithoutLosingSiblings() {
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                collision0,0,0,2,2,Original
                collision0,0,0,2,2,DuplicateSameBlock
                collision1,0,broken,2,2,Malformed
                collision2,5,5,6,6,ValidSibling
                collisionex3,Mask,region,mask.png,255,0,0
                animation0.collision4,0,0,2,2,Animated
                animation0.collisionex4,AnimatedEx,polygon,0,0,2,0,1,2
                collisionex7,Unknown,triangle,0,0,2,0,1,2
                collisionex8,OddPolygon,polygon,0,0,2
                collisionex9,TooFewPolygon,polygon,0,0,2,2
                collisionex10,NegativeRadius,circle,0,0,-1
                collision11,0,0,2147483647,1,EndpointOverflow
                collisionex12,CircleOverflow,circle,2147483647,0,1
                collision-1,0,0,1,1,NegativeId
                collision999999999999999999999,0,0,1,1,OverflowId
                collision13,999999999999999999999,0,1,1,OverflowCoordinate
                collisionex14,PolygonOverflow,polygon,0,0,2147483647,0,0,1
                collisionex5,ExactEllipse,ellipse,10,10,14,12
                }
                surface0
                {
                collision0,0,0,2,2,DuplicateLaterBlock
                }
            """.trimIndent(),
            "surfaces2.txt" to """
                surface0
                {
                collision0,0,0,2,2,DuplicateLaterFile
                collision6,20,20,21,21,LaterFileSibling
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(listOf(0, 2, 5, 6), definition.collisions.map { it.id })
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("DuplicateSameBlock"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("Malformed"))
        assertEquals(SurfaceDiagnosticReason.UNSUPPORTED, loaded.reasonFor("Mask,region"))
        assertEquals(SurfaceDiagnosticReason.UNSUPPORTED, loaded.reasonFor("animation0.collision4"))
        assertEquals(SurfaceDiagnosticReason.UNSUPPORTED, loaded.reasonFor("animation0.collisionex4"))
        assertEquals(SurfaceDiagnosticReason.UNSUPPORTED, loaded.reasonFor("Unknown,triangle"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("OddPolygon"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("TooFewPolygon"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("NegativeRadius"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("EndpointOverflow"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("CircleOverflow"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("NegativeId"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("OverflowId"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("OverflowCoordinate"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("PolygonOverflow"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("DuplicateLaterBlock"))
        assertEquals(SurfaceDiagnosticReason.ENTRY, loaded.reasonFor("DuplicateLaterFile"))
        assertTrue(loaded.reader.diagnostics.any { it.source.contains("DuplicateSameBlock") })
        assertTrue(loaded.reader.diagnostics.any { it.source.contains("DuplicateLaterBlock") })
        assertTrue(loaded.reader.diagnostics.any { it.source.contains("DuplicateLaterFile") })
        assertTrue(loaded.reader.diagnostics.any { it.source.contains("animation0.collision4") })
    }

    @Test
    fun legacyCollisionAreaFallbackUsesItsInclusiveStoredSizeExactlyOnce() {
        val shell = ShellSurface()
        shell.collisionAreas[7] = shell.CollisionArea(7, 1, 2, 11, 22, "Legacy")

        val collision = shell.toSurfaceDefinition().collisions.single()

        assertEquals(IntRect(1, 2, 12, 23), collision.shape.bounds)
        assertTrue(collision.shape.contains(IntOffset(11, 22)))
        assertFalse(collision.shape.contains(IntOffset(12, 23)))
    }

    @Test
    fun legacyCollisionAreaFallbackSkipsUnrepresentableBoundsAndKeepsValidSiblings() {
        val shell = ShellSurface()
        shell.collisionAreas[7] = shell.CollisionArea(7, 1, 2, 11, 22, "Legacy")
        shell.collisionAreas[8] = shell.CollisionArea(8, 0, 0, 0, 0, "Overflow").apply {
            startX = Int.MAX_VALUE
            W = 1
        }

        val collision = shell.toSurfaceDefinition().collisions.single()

        assertEquals(7, collision.id)
        assertEquals(IntRect(1, 2, 12, 23), collision.shape.bounds)
    }

    @Test
    fun legacyDirectParserRejectsInclusiveEndpointOverflowBeforeMirrorMutation() {
        val shell = ShellSurface(
            "",
            null,
            0,
            listOf("collision0,0,0,1,1,Valid", "collision0,2147483647,0,2147483647,0,Overflow"),
            probeBitmap = false,
        )

        assertEquals("Valid", shell.collisionAreas.getValue(0).name)
        assertEquals("Valid", shell.toSurfaceDefinition().collisions.single().identifier)
    }

    @Test
    fun collisionBudgetRejectsEntriesAfterTheFirst256() {
        val collisions = buildString {
            repeat(257) { id -> appendLine("collision$id,$id,0,$id,0,Hit$id") }
        }
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                $collisions
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(256, definition.collisions.size)
        assertEquals((0..255).toList(), definition.collisions.map { it.id })
        assertTrue(loaded.reader.diagnostics.any { it.source.startsWith("collision256,") })
    }

    @Test
    fun aggregatePolygonVertexBudgetRejectsLaterPolygonsButRetainsCheapCollisions() {
        val polygon = (0 until CollisionShape.MAX_POLYGON_VERTICES).joinToString(",") { index ->
            "$index,${index % 2}"
        }
        val collisions = buildString {
            repeat(17) { id -> appendLine("collisionex$id,Polygon$id,polygon,$polygon") }
            appendLine("collisionex99,Cheap,circle,2,2,1")
        }

        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                $collisions
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals((0 until 16).toList() + 99, definition.collisions.map { it.id })
        assertTrue(loaded.reader.diagnostics.any { it.source.startsWith("collisionex16,") })
        assertTrue(definition.collisions.single { it.id == 99 }.shape.contains(IntOffset(2, 2)))
    }

    @Test
    fun aggregatePolygonVertexBudgetBoundsRetainedPolygons() {
        val polygon = (0 until CollisionShape.MAX_POLYGON_VERTICES).joinToString(",") { index ->
            "$index,${index % 2}"
        }
        val collisions = buildString {
            repeat(256) { id -> appendLine("collisionex$id,Polygon$id,polygon,$polygon") }
        }
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                $collisions
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(16, definition.collisions.size)
    }

    @Test
    fun polygonOverflowFallbackRetainsExactExtremeCoordinateMembership() {
        val polygon = CollisionShape.Polygon(
            listOf(
                IntOffset(Int.MIN_VALUE + 1, Int.MIN_VALUE + 1),
                IntOffset(Int.MAX_VALUE - 1, Int.MIN_VALUE + 2),
                IntOffset(Int.MIN_VALUE + 2, Int.MAX_VALUE - 1),
            ),
        )

        assertTrue(polygon.contains(IntOffset(0, 0)))
        assertFalse(polygon.contains(IntOffset(Int.MAX_VALUE - 1, Int.MAX_VALUE - 1)))
    }

    @Test
    fun hugeCanonicalShapeSurvivesWhenLegacyAreaProjectionCannotRepresentItsSpan() {
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                collision0,-2147483648,0,2147483646,0,Huge
                collision1,0,0,1,1,Sibling
                }
            """.trimIndent(),
        )
        val surface = requireNotNull(loaded.manager.getSurface("0"))
        val definition = surface.toSurfaceDefinition()

        assertEquals(listOf(0, 1), definition.collisions.map { it.id })
        assertEquals(2, surface.collisionCount)
        assertEquals(setOf(1), surface.collisionAreas.keys)
        assertTrue(loaded.reader.diagnostics.isEmpty())
    }

    @Test
    fun oversizedPolygonIsRejectedWithoutLosingItsValidSibling() {
        val tooManyPoints = (0..256).joinToString(",") { index -> "$index,${index % 2}" }
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0
                {
                collisionex300,TooMany,polygon,$tooManyPoints
                collisionex301,Recovered,circle,10,10,2
                }
            """.trimIndent(),
        )
        val definition = requireNotNull(loaded.manager.getSurface("0")).toSurfaceDefinition()

        assertEquals(listOf(301), definition.collisions.map { it.id })
        assertTrue(loaded.reader.diagnostics.any { it.source.startsWith("collisionex300,") })
        assertFalse(loaded.reader.diagnostics.any { it.source.startsWith("collisionex301,") })
    }

    @Test
    fun fannedOutMaxVertexCollisionIsSharedAcrossSurfaceDefinitions() {
        val points = (0 until 256).joinToString(",") { index -> "$index,${index % 2}" }
        val loaded = loadSurfaceFiles(
            "surfaces.txt" to """
                surface0-1023
                {
                collisionex42,FannedPolygon,polygon,$points
                }
            """.trimIndent(),
        )
        val collisions = (0..1023).map { id ->
            requireNotNull(loaded.manager.getSurface(id.toString()))
                .toSurfaceDefinition()
                .collisions
                .single()
        }

        assertTrue(loaded.reader.diagnostics.isEmpty())
        assertTrue(collisions.drop(1).all { collision -> collision === collisions.first() })
        assertTrue(collisions.drop(1).all { collision -> collision.shape === collisions.first().shape })
        assertEquals(256, (collisions.first().shape as CollisionShape.Polygon).points.size)
    }

    @Test
    fun shortBanchoFixtureRetainsPolygonGeometry() {
        val resource = requireNotNull(javaClass.getResource("/ghost-fixtures/bancho/collisionex.txt"))
        val root = temporaryFolder.newFolder("bancho")
        File(root, "surfaces.txt").writeBytes(resource.readBytes())
        val manager = SurfaceManager("bancho")
        val reader = SurfaceReader(
            manager,
            root.absolutePath + File.separator,
            File(root, "surfaces.txt").absolutePath,
        )
        val definition = requireNotNull(manager.getSurface("0")).toSurfaceDefinition()

        assertTrue(reader.diagnostics.isEmpty())
        assertEquals(listOf(3), definition.collisions.map { it.id })
        assertTrue(definition.collisions.single().shape is CollisionShape.Polygon)
        assertEquals("BanchoCoat", definition.collisions.single().identifier)
    }

    private fun loadSurfaceFiles(vararg sources: Pair<String, String>): LoadedSurface {
        val root = temporaryFolder.newFolder("surface-${fixtureIndex++}")
        sources.forEach { (name, text) ->
            File(root, name).writeText(text + "\n", StandardCharsets.UTF_8)
        }
        val manager = SurfaceManager("synthetic")
        val reader = SurfaceReader(
            manager,
            root.absolutePath + File.separator,
            File(root, sources.first().first).absolutePath,
        )
        return LoadedSurface(manager, reader)
    }

    private data class LoadedSurface(
        val manager: SurfaceManager,
        val reader: SurfaceReader,
    ) {
        fun reasonFor(sourceFragment: String): SurfaceDiagnosticReason =
            requireNotNull(reader.diagnostics.singleOrNull { it.source.contains(sourceFragment) }) {
                "Expected one diagnostic containing '$sourceFragment', got ${reader.diagnostics}"
            }.reason
    }

    private var fixtureIndex = 0
}
