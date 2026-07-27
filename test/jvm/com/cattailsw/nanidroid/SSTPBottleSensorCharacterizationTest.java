package com.cattailsw.nanidroid;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.LinkedList;

import org.junit.Test;

/** Locks down the historical Bottle response framing before its Kotlin migration. */
public final class SSTPBottleSensorCharacterizationTest {
    @Test
    public void requiredMigrationInvariant_skipsStatusHeaderAndReturnsEighthColumn() throws Exception {
        BufferedReader response = new BufferedReader(new StringReader(
                "HTTP/1.1 200 OK\n"
                        + "X-Example: true\n"
                        + "\n"
                        + "0\t1\t2\t3\t4\t5\t6\tfirst\n"
                        + "0\t1\t2\t3\t4\t5\t6\tsecond\n"));

        LinkedList<String> result = SSTPBottleSensor.parseBuffer(response);

        assertEquals(2, result.size());
        assertEquals("first", result.get(0));
        assertEquals("second", result.get(1));
    }
}
