package com.cattailsw.nanidroid.test;

import java.util.regex.Matcher;

import android.test.AndroidTestCase;
import com.cattailsw.nanidroid.*;
import android.util.Log;

public class SurfaceParserTest extends AndroidTestCase {
    private static final String TAG = "SurfaceParserTest";
	public void testAnimationIntervalParsing() {
		String t = "animation0.interval,never";
		Matcher m = PatternHolders.animation_interval.matcher(t);
		assertTrue( m.matches());		
	}
	
	public void testAnimationPattern1() {
		String t = "animation0.pattern0,overlay,1201,50,117,66";
		Matcher m = PatternHolders.animation.matcher(t);
		assertTrue(m.matches());
		assertEquals(9, m.groupCount());
		Util.printMatch(m);
		t = "animation2.pattern0,alternativestart,(0,1,2)";
		m = PatternHolders.animation.matcher(t);
		assertTrue(m.matches());
		Util.printMatch(m);
		m = PatternHolders.animation_alt.matcher(t);
		assertTrue(m.matches());
		assertEquals(3, m.groupCount());
		assertEquals("0,1,2", m.group(3));
	}
	
	public void testAnimationPattern2_Overlay() {
		String t = "animation0.pattern2,overlay,-1";
		Matcher m = PatternHolders.animation_base.matcher(t);
		assertTrue( m.matches() );
		assertNull(m.group(7));

		t = "animation1.pattern3,overlay,-1,100";
		m = PatternHolders.animation_base.matcher(t);
		assertTrue( m.matches() );
		assertEquals(7, m.groupCount());
		assertEquals("100", m.group(7));		
	}


    public void testSurfaceFileParse(){
	String t = "surface0.png";
	Matcher m = PatternHolders.surface_file_scan.matcher(t);
	assertTrue(m.matches());
	assertEquals("0", m.group(1));
	Util.printMatch(m);
	t = "surface0000.png";
	m = PatternHolders.surface_file_scan.matcher(t);
	assertTrue(m.matches());
	assertEquals("0000", m.group(1));

	t = "surface0001.png";
	m = PatternHolders.surface_file_scan.matcher(t);
	assertTrue(m.matches());
	assertEquals("0001", m.group(1));
    }

    public void testIntervalParse(){
	String t = "0interval,talk";
	Matcher m = PatternHolders.interval.matcher(t);
	assertTrue(m.matches());
	assertEquals("0", m.group(1));
	assertEquals("talk", m.group(2));

	t = "1interval,sometimes";
	m = PatternHolders.interval.matcher(t);
	assertTrue(m.matches());
	assertEquals("1", m.group(1));
	assertEquals("sometimes", m.group(2));

	t = "2interval15,talk";
	m = PatternHolders.interval.matcher(t);
	assertTrue(m.matches());
	assertEquals("2", m.group(1));
	assertEquals("talk", m.group(2));

    }
	
}
