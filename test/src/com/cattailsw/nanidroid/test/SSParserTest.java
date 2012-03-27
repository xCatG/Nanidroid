package com.cattailsw.nanidroid.test;

import android.test.AndroidTestCase;
import com.cattailsw.nanidroid.*;
import android.content.Context;
import android.util.Log;
import java.util.regex.Matcher;

public class SSParserTest extends AndroidTestCase {
    private static final String TAG = "SSParserTest";
    Context mContext = getContext();
    class DummyKeroView extends KeroView {
	String sid = null;
	public DummyKeroView(Context ctx){super(ctx);}
	
	public void changeSurface(String id) {
	    this.sid = id;
	}
    }

    class DummySakuraView extends SakuraView {
	String sid = null;
	public DummySakuraView(Context ctx){super(ctx);}
	public void changeSurface(String id) {
	    this.sid = id;
	}
    }

    class DummyBalloon extends Balloon {
	String text = null;
	public DummyBalloon(Context ctx){super(ctx);}
	public void setText(String str){
	    Log.d(TAG, "got text:" + str);
	    if ( this.text == null )
	    this.text = str; 
	    else
		this.text += str;// keep appending text
	}
    }

    DummySakuraView sakura = null;//new DummySakuraView();
    DummyKeroView kero = null;//new DummyKeroView();
    DummyBalloon bSakura = null;//new DummyBalloon();
    DummyBalloon bKero = null;//new DummyBalloon();
    SScriptRunner sr = null;
    protected void setUp() throws Exception {
	super.setUp();
	mContext = getContext();
	sakura = new DummySakuraView(mContext);
	kero = new DummyKeroView(mContext);
	bSakura = new DummyBalloon(mContext);
	bKero = new DummyBalloon(mContext);
	sr = SScriptRunner.getInstance(mContext);
	sr.setViews(sakura, kero, bSakura, bKero);
    }

    protected void tearDown() throws Exception {
	super.tearDown();
	sr.stop();
    }

    public void testSakuraSpeak() {
	sr.stop();
	String cmd = "\\_q\\hlalala\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	//	sr.stop();
	assertEquals("lalala", bSakura.text);
	
	cmd = "\\_q\\0abcdefg\\n";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("lalalaabcdefg\n", bSakura.text);
    }

    public void testSakuraSpeakNormal() {
	String cmd = "\\habcde\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	//Log.d(TAG, "->" + bSakura.text);
	assertEquals("aababcabcdabcdeabcde", bSakura.text);
	// last string will be set twice because on \e, ui will still get updated
    }

    public void testKeroSpeak() {
	String cmd = "\\_q\\1xxxxxx\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("xxxxxx", bKero.text);

	cmd = "\\_q\\habcde\\uyyyyyy\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("xxxxxxyyyyyy", bKero.text);
	sr.stop();
    }

    public void testIgnoreCommands() {
	String cmd = "\\4\\5\\6\\v\\_n\\_V";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
    }

    public void testParsingRegExp() {
	String sqbreket_tester = "abcde\\n[half]efghi";
	Matcher m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertTrue(m.find());
	assertEquals("[half]", m.group());
	assertEquals("half", m.group(1));

	sqbreket_tester = "qoweqwjkjl\\n[100]ksjdlaksjklwje";
	m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertTrue(m.find());
	assertEquals("[100]", m.group());
	assertEquals("100", m.group(1));

	sqbreket_tester = "qoweqwjkjl\\n[100%]ksjdlaksjklwje";
	m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertTrue(m.find());
	assertEquals("100%", m.group(1));

	
	sqbreket_tester = "[1xaw]";
	m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertFalse(m.find()); // should not match

	sqbreket_tester = "[]";
	m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertFalse(m.find()); // should not match

    }

}
