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

		t = "animation0.pattern0,move,0,150,0,-1";
		m = PatternHolders.animation.matcher(t);
		assertTrue(m.matches());

		t = "animation0.pattern10,move,0,-150,0,-1";
		m = PatternHolders.animation.matcher(t);
		assertTrue(m.matches());

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

    public void testElementParsing() {
	String t = "element0,base,surface300.png,-22,1";
	Matcher m = PatternHolders.element.matcher(t);
	assertTrue(m.matches());

	t = "element0,base,surface1.png,18,0";
	m = PatternHolders.element.matcher(t);
	assertTrue(m.matches());

    }

    public void testCollParsing() {
	String t = "collision0,98,11,170,46,Head";
	Matcher m = PatternHolders.collision.matcher(t);
	assertTrue(m.matches());

	t = "collision1,118,86,130,91,l-Lip";
	m = PatternHolders.collision.matcher(t);
	assertTrue(m.matches());

    }

    public void testPatternParsing() {
	String t = "0pattern0,206,5,overlay,0,0";
	Matcher m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern1,-1,15,overlay,0,0";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern0,102,5,overlay,106,66";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern11,103,5,overlay,106,66";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern20,102,15,overlay,106,66";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern30,-1,10,overlay,0,0";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern0,12,2,move,-16,0";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "0pattern26,12,2,move,-312,0";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

	t = "1pattern10,15,2,overlay,-40,-20";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());
	t = "2pattern8,15,2,overlay,36,-27";
	m = PatternHolders.pattern.matcher(t);
	assertTrue(m.matches());

    }

    public void testPatternAltStart() {
	String t = "	1pattern0,0,0,alternativestart,[2.3.4.5]";
	Matcher m = PatternHolders.pattern_alt.matcher(t);
	assertTrue(m.find());
	assertEquals("2.3.4.5", m.group(3));
	assertEquals("1", m.group(1));
	assertEquals("0", m.group(2));

	t = "1pattern0,0,0,alternativestart,[2.3.4]";
	m = PatternHolders.pattern_alt.matcher(t);
	assertTrue(m.find());
	assertEquals("2.3.4", m.group(3));
    }

    public int[] getSurfaceIds(String line) {
	if ( line.contains(",") ) {
	    String [] ss = line.split(",");
	    String [] idz = new String[ss.length];
	    int [] id = new int[ss.length];
	    for ( int i = 0; i < ss.length; i++ ) {
		Matcher m = PatternHolders.surface_desc_ptrn.matcher(ss[i]);
		if ( m.matches() ) {
		    idz[i] = m.group(1);
		    id[i] = Integer.parseInt(idz[i]);
		}
	    }
	    return id;

	}
	else {
	    Matcher m = PatternHolders.surface_desc_ptrn.matcher(line);
	    if ( m.find() ) {
		return new int[]{ Integer.parseInt(m.group(1)) };
	    }
	    return null;
	}
    }

    public void testSurfaceWithCommaSeparation() {
	String t = "surface0,surface1";
	// we need to actually create two separate surface objects
	// first find ,
	if ( t.contains(",") ) {
	    String [] ss = t.split(",");
	    String [] idz = new String[ss.length];
	    for ( int i = 0; i < ss.length; i++ ) {
		Matcher m = PatternHolders.surface_desc_ptrn.matcher(ss[i]);
		if ( m.matches() )
		    idz[i] = m.group(1);
	    }
	    assertEquals("0", idz[0]);
	    assertEquals("1", idz[1]);
	}

	int[] id = getSurfaceIds(t);
	assertEquals(0, id[0]);
	assertEquals(1, id[1]);

	t = "surface1020";
	id = getSurfaceIds(t);
	assertEquals(1, id.length);
	assertEquals(1020, id[0]);

	t = "surface1,"; // should choke? or should parse fine?
	id = getSurfaceIds(t);
	assertEquals(1, id.length);

	t = "asldkas;ldkasl;";
	id = getSurfaceIds(t);
	assertNull(id);

	t = "surface1 xxxx";
	id = getSurfaceIds(t);
	assertEquals(1, id.length);
	assertEquals(1, id[0]);
    }
	
    public void testComment() {
	String t = ";askjdaklsjdkals";
	Matcher m = PatternHolders.comment_ptrn.matcher(t);
	assertTrue(m.matches());

	t = "// askdjklsd nkwenlkasl";
	m = PatternHolders.comment_ptrn.matcher(t);
	assertTrue(m.matches());

	t = "abcde";
	m = PatternHolders.comment_ptrn.matcher(t);
	assertFalse(m.matches());


	t = "abcde;//asldka;sldkl;";
	m = PatternHolders.comment_ptrn.matcher(t);
	assertFalse(m.matches());

    }
}
