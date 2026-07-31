package com.cattailsw.nanidroid

import org.junit.Assert
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.lang.reflect.InvocationTargetException
import java.util.LinkedList

/** Locks down the historical Bottle response framing before its Kotlin migration.  */
class SSTPBottleSensorCharacterizationTest {
    @Suppress("UNCHECKED_CAST")
    private fun parseBuffer(response: BufferedReader): LinkedList<String> {
        val method = SSTPBottleSensor::class.java.getDeclaredMethod("parseBuffer", BufferedReader::class.java)
        method.isAccessible = true
        return try {
            method.invoke(null, response) as LinkedList<String>
        } catch (failure: InvocationTargetException) {
            val cause = failure.cause
            if (cause is IndexOutOfBoundsException) throw cause
            throw failure
        }
    }
    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_skipsStatusHeaderAndReturnsEighthColumn() {
        val response = BufferedReader(
            StringReader(
                ("HTTP/1.1 200 OK\n"
                        + "X-Example: true\n"
                        + "\n"
                        + "0\t1\t2\t3\t4\t5\t6\tfirst\n"
                        + "0\t1\t2\t3\t4\t5\t6\tsecond\n")
            )
        )

        val result: LinkedList<String> = parseBuffer(response)

        Assert.assertEquals(2, result.size.toLong())
        Assert.assertEquals("first", result.get(0))
        Assert.assertEquals("second", result.get(1))
    }

    @Test
    @Throws(Exception::class)
    fun requiredMigrationInvariant_rejectsTrailingEmptyEighthColumn() {
        val response = BufferedReader(
            StringReader(
                ("status\n"
                        + "\n"
                        + "0\t1\t2\t3\t4\t5\t6\t\n")
            )
        )

        try {
            parseBuffer(response)
            Assert.fail("A trailing empty Bottle field was rejected by the Java implementation")
        } catch (expected: IndexOutOfBoundsException) {
            // Java String.split("\\t") discards trailing empty fields before [7].
        }
    }
}
