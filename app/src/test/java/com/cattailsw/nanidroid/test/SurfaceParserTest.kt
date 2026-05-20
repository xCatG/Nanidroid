package com.cattailsw.nanidroid.test

import org.junit.Assert.*
import org.junit.Test
import com.cattailsw.nanidroid.PatternHolders
import java.util.regex.Matcher

class SurfaceParserTest {

    @Test
    fun testAnimationIntervalParsing() {
        val t = "animation0.interval,never"
        val m = PatternHolders.animation_interval.matcher(t)
        assertTrue(m.matches())
    }

    @Test
    fun testAnimationPattern1() {
        var t = "animation0.pattern0,overlay,1201,50,117,66"
        var m = PatternHolders.animation.matcher(t)
        assertTrue(m.matches())
        assertEquals(9, m.groupCount())

        t = "animation2.pattern0,alternativestart,(0,1,2)"
        m = PatternHolders.animation.matcher(t)
        assertTrue(m.matches())

        m = PatternHolders.animation_alt.matcher(t)
        assertTrue(m.matches())
        assertEquals(3, m.groupCount())
        assertEquals("0,1,2", m.group(3))

        t = "animation0.pattern0,move,0,150,0,-1"
        m = PatternHolders.animation.matcher(t)
        assertTrue(m.matches())

        t = "animation0.pattern10,move,0,-150,0,-1"
        m = PatternHolders.animation.matcher(t)
        assertTrue(m.matches())
    }

    @Test
    fun testAnimationPattern2_Overlay() {
        var t = "animation0.pattern2,overlay,-1"
        var m = PatternHolders.animation_base.matcher(t)
        assertTrue(m.matches())
        assertNull(m.group(7))

        t = "animation1.pattern3,overlay,-1,100"
        m = PatternHolders.animation_base.matcher(t)
        assertTrue(m.matches())
        assertEquals(7, m.groupCount())
        assertEquals("100", m.group(7))
    }

    @Test
    fun testSurfaceFileParse() {
        var t = "surface0.png"
        var m = PatternHolders.surface_file_scan.matcher(t)
        assertTrue(m.matches())
        assertEquals("0", m.group(1))

        t = "surface0000.png"
        m = PatternHolders.surface_file_scan.matcher(t)
        assertTrue(m.matches())
        assertEquals("0000", m.group(1))

        t = "surface0001.png"
        m = PatternHolders.surface_file_scan.matcher(t)
        assertTrue(m.matches())
        assertEquals("0001", m.group(1))
    }

    @Test
    fun testIntervalParse() {
        var t = "0interval,talk"
        var m = PatternHolders.interval.matcher(t)
        assertTrue(m.matches())
        assertEquals("0", m.group(1))
        assertEquals("talk", m.group(2))

        t = "1interval,sometimes"
        m = PatternHolders.interval.matcher(t)
        assertTrue(m.matches())
        assertEquals("1", m.group(1))
        assertEquals("sometimes", m.group(2))

        t = "2interval15,talk"
        m = PatternHolders.interval.matcher(t)
        assertTrue(m.matches())
        assertEquals("2", m.group(1))
        assertEquals("talk", m.group(2))
    }

    @Test
    fun testElementParsing() {
        var t = "element0,base,surface300.png,-22,1"
        var m = PatternHolders.element.matcher(t)
        assertTrue(m.matches())

        t = "element0,base,surface1.png,18,0"
        m = PatternHolders.element.matcher(t)
        assertTrue(m.matches())
    }

    @Test
    fun testCollParsing() {
        var t = "collision0,98,11,170,46,Head"
        var m = PatternHolders.collision.matcher(t)
        assertTrue(m.matches())

        t = "collision1,118,86,130,91,l-Lip"
        m = PatternHolders.collision.matcher(t)
        assertTrue(m.matches())
    }

    @Test
    fun testPatternParsing() {
        var t = "0pattern0,206,5,overlay,0,0"
        var m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern1,-1,15,overlay,0,0"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern0,102,5,overlay,106,66"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern11,103,5,overlay,106,66"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern20,102,15,overlay,106,66"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern30,-1,10,overlay,0,0"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern0,12,2,move,-16,0"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "0pattern26,12,2,move,-312,0"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "1pattern10,15,2,overlay,-40,-20"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())

        t = "2pattern8,15,2,overlay,36,-27"
        m = PatternHolders.pattern.matcher(t)
        assertTrue(m.matches())
    }

    @Test
    fun testPatternAltStart() {
        var t = "\t1pattern0,0,0,alternativestart,[2.3.4.5]"
        var m = PatternHolders.pattern_alt.matcher(t)
        assertTrue(m.find())
        assertEquals("2.3.4.5", m.group(3))
        assertEquals("1", m.group(1))
        assertEquals("0", m.group(2))

        t = "1pattern0,0,0,alternativestart,[2.3.4]"
        m = PatternHolders.pattern_alt.matcher(t)
        assertTrue(m.find())
        assertEquals("2.3.4", m.group(3))
    }

    private fun getSurfaceIds(line: String): IntArray? {
        return if (line.contains(",")) {
            val ss = line.split(",").dropLastWhile { it.isEmpty() }
            val id = IntArray(ss.size)
            for (i in ss.indices) {
                val m = PatternHolders.surface_desc_ptrn.matcher(ss[i])
                if (m.matches()) {
                    id[i] = m.group(1)?.toInt() ?: 0
                }
            }
            id
        } else {
            val m = PatternHolders.surface_desc_ptrn.matcher(line)
            if (m.find()) {
                val valStr = m.group(1)
                if (valStr != null) {
                    intArrayOf(valStr.toInt())
                } else {
                    null
                }
            } else {
                null
            }
        }
    }

    @Test
    fun testSurfaceWithCommaSeparation() {
        val t = "surface0,surface1"
        if (t.contains(",")) {
            val ss = t.split(",")
            val idz = arrayOfNulls<String>(ss.size)
            for (i in ss.indices) {
                val m = PatternHolders.surface_desc_ptrn.matcher(ss[i])
                if (m.matches()) {
                    idz[i] = m.group(1)
                }
            }
            assertEquals("0", idz[0])
            assertEquals("1", idz[1])
        }

        var id = getSurfaceIds(t)
        assertNotNull(id)
        assertEquals(0, id!![0])
        assertEquals(1, id[1])

        val t2 = "surface1020"
        id = getSurfaceIds(t2)
        assertNotNull(id)
        assertEquals(1, id!!.size)
        assertEquals(1020, id[0])

        val t3 = "surface1,"
        id = getSurfaceIds(t3)
        assertNotNull(id)
        assertEquals(1, id!!.size)

        val t4 = "asldkas;ldkasl;"
        id = getSurfaceIds(t4)
        assertNull(id)

        val t5 = "surface1 xxxx"
        id = getSurfaceIds(t5)
        assertNotNull(id)
        assertEquals(1, id!!.size)
        assertEquals(1, id[0])
    }

    @Test
    fun testComment() {
        var t = ";askjdaklsjdkals"
        var m = PatternHolders.comment_ptrn.matcher(t)
        assertTrue(m.matches())

        t = "// askdjklsd nkwenlkasl"
        m = PatternHolders.comment_ptrn.matcher(t)
        assertTrue(m.matches())

        t = "abcde"
        m = PatternHolders.comment_ptrn.matcher(t)
        assertFalse(m.matches())

        t = "abcde;//asldka;sldkl;"
        m = PatternHolders.comment_ptrn.matcher(t)
        assertFalse(m.matches())
    }
}
