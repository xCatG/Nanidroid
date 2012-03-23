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
		printMatch(m);
		t = "animation2.pattern0,alternativestart,(0,1,2)";
		m = PatternHolders.animation.matcher(t);
		assertTrue(m.matches());
		printMatch(m);
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
		printMatch(m);
		assertEquals(7, m.groupCount());
		assertEquals("100", m.group(7));		
	}

    private void printMatch(Matcher m) {
	Log.d(TAG, "matcher has " + m.groupCount() + " groups");
	for ( int i = 0; i < m.groupCount(); i++ ) {
	    Log.d(TAG, "("+i+") => " + m.group(i));
	}
	try {
	    Log.d(TAG, "("+m.groupCount()+") => " + m.group( m.groupCount()));
	}
	catch(Exception e) {

	}

    }

    public void testSurfaceFileParse(){
	String t = "surface0.png";
	Matcher m = PatternHolders.surface_file_scan.matcher(t);
	assertTrue(m.matches());
	assertEquals("0", m.group(1));

    }
	
}
