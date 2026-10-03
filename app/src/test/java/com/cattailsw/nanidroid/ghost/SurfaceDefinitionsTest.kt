package com.cattailsw.nanidroid.ghost

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceDefinitionsTest {
    @Test fun retainsElementsCollisionsAndNormalizedAnimationWaits() {
        val definitions = SurfaceDefinitions.parse("""
            surface0
            {
            element2,overlay,parts\eye.png,-3,4
            element0,base,body.png,0,0
            collision1,2,3,12,13,Face
            7interval,always
            7pattern0,101,7,overlay,2,3
            animation8.interval,runonce
            animation8.pattern0,overlayfast,102,70,4,5
            animation8.pattern1,alternativestart,(7,9)
            }
        """.trimIndent())
        val definition = definitions.definition(0)!!
        assertEquals(listOf(0, 2), definition.elements.map { it.id })
        assertEquals("parts\\eye.png", definition.elements[1].fileName)
        assertEquals("Face", definition.collisions.single().name)
        val legacy = definition.animations.first().patterns.first() as SurfacePattern.Image
        val modern = definition.animations.last().patterns.first() as SurfacePattern.Image
        assertEquals(70L, legacy.waitMinMs)
        assertEquals(70L, modern.waitMinMs)
        assertEquals(listOf(7, 9), (definition.animations.last().patterns.last() as SurfacePattern.Alternative).animationIds)
        assertTrue(0 in definitions.ids())
        assertNull(definitions.definition(10))
    }

    @Test fun parsesBracketedAlternativeTargetsInLegacyPattern() {
        val definition = SurfaceDefinitions.parse("""
            surface0 {
            3interval,never
            3pattern0,alternativestart,[1.2.4]
            }
        """.trimIndent()).definition(0)!!
        assertEquals(listOf(1, 2, 4),
            (definition.animations.single().patterns.single() as SurfacePattern.Alternative).animationIds)
    }

    @Test fun alternativesAcceptUnbracketedAndAlternateSeparatorLists() {
        val definition = SurfaceDefinitions.parse("""
            surface0 {
            1pattern0,0,0,alternativestart,4.5.6
            2pattern0,0,0,alternativestart,[4 5 6]
            3pattern0,0,0,alternativestart,[4:5:6]
            4pattern0,0,0,alternativestart,[4.5.]
            animation5.pattern0,alternativestart,7,8
            6pattern0,0,0,alternativestart,[]
            7pattern0,0,0,alternativestart,[bad.x]
            }
        """.trimIndent()).definition(0)!!
        fun targets(id: Int) = (definition.animations.first { it.id == id }.patterns.single()
            as SurfacePattern.Alternative).animationIds
        assertEquals(listOf(4, 5, 6), targets(1))
        assertEquals(listOf(4, 5, 6), targets(2))
        assertEquals(listOf(4, 5, 6), targets(3))
        assertEquals(listOf(4, 5), targets(4))
        assertEquals(listOf(7, 8), targets(5))
        assertTrue(definition.animations.firstOrNull { it.id == 6 }?.patterns.orEmpty().isEmpty())
        assertTrue(definition.animations.firstOrNull { it.id == 7 }?.patterns.orEmpty().isEmpty())
    }

    @Test fun alternativesUseAuthoredTargetColumnAndKeepValidTargets() {
        val definition = SurfaceDefinitions.parse("""
            surface0 {
            333pattern0,0,0,alternativestart,[4.5.6]
            334pattern0,0,0,alternativestart,(7,8,9)
            335pattern0,0,0,alternativestart,[4.bad.6]
            animation336.pattern0,alternativestart,[10.11]
            animation337.pattern0,alternativestart,(10,bad)
            }
        """.trimIndent()).definition(0)!!
        assertEquals(listOf(4, 5, 6),
            (definition.animations.first { it.id == 333 }.patterns.single() as SurfacePattern.Alternative).animationIds)
        assertEquals(listOf(7, 8, 9),
            (definition.animations.first { it.id == 334 }.patterns.single() as SurfacePattern.Alternative).animationIds)
        assertEquals(listOf(4, 6),
            (definition.animations.first { it.id == 335 }.patterns.single() as SurfacePattern.Alternative).animationIds)
        assertEquals(listOf(10, 11),
            (definition.animations.first { it.id == 336 }.patterns.single() as SurfacePattern.Alternative).animationIds)
        assertEquals(listOf(10),
            (definition.animations.first { it.id == 337 }.patterns.single() as SurfacePattern.Alternative).animationIds)
    }

    @Test fun boundsInputAndSkipsMalformedRecordsLocally() {
        val definitions = SurfaceDefinitions.parse("""
            surface0 {
            element0,overlay,body.png,0,0
            element1,overlay,bad.png,NaN,0
            collision0,1,2,3,4,Body
            animation0.interval,always
            animation0.pattern0,overlay,100,-3,0,0
            animation0.pattern1,overlay,101,70,0,0
            }
        """.trimIndent())
        assertEquals(1, definitions.definition(0)!!.elements.size)
        assertEquals(1, definitions.definition(0)!!.animations.single().patterns.size)
        val file = File.createTempFile("oversized-surfaces", ".txt")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(4L * 1024 * 1024 + 1) }
            assertTrue(runCatching { SurfaceDefinitions.read(file) }.isFailure)
        } finally { file.delete() }
    }

    @Test fun expandedRecordsStopAtRetainedRecordLimit() {
        val text = buildString {
            appendLine("surface0-4095 {")
            repeat(17) { id -> appendLine("element$id,overlay,part$id.png,0,0") }
            appendLine("}")
        }
        val definitions = SurfaceDefinitions.parse(text)
        assertEquals(4096, definitions.ids().size)
        assertEquals(16, definitions.definition(0)!!.elements.size)
        assertEquals(16, definitions.definition(4095)!!.elements.size)
    }

    @Test fun readsUtf8FileWithByteOrderMark() {
        val file = File.createTempFile("surfaces", ".txt")
        try {
            val text = """
                surface0
                {
                animation0.interval,always
                animation0.pattern0,overlay,301,0,0,0
                }
            """.trimIndent()
            file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray())
            assertEquals(listOf(SurfacePattern.Image(301, "overlay", 0, 0, 0, 0)),
                SurfaceDefinitions.read(file).definition(0)!!.animations.single().patterns)
        } finally {
            file.delete()
        }
    }

    @Test fun parsesNumericListsRangesExclusionsAndInlineBrace() {
        val definitions = SurfaceDefinitions.parse("""
            surface 0, 10, 20-22, !21 {
            animation0.interval,always
            animation0.pattern0,overlay,301,0,2,3
            }
        """.trimIndent())
        assertEquals(setOf(0, 10, 20, 22), definitions.ids())
    }
}
