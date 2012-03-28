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
	    if ( this.sid == null )
		this.sid = id;
	    else
		this.sid = this.sid + "," + id;
	}

	String aid = null;
	String aidz = null;
	@Override
	public void loadAnimation(String id) {
	    aid = id;
	    if ( aidz == null )
		aidz = id;
	    else
		aidz += "," + id;
	}

	@Override
	public void startTalkingAnimation() {
	    // do nothing
	}

	@Override
	public void startAnimation() {
	    // do nothing
	}

    }

    class DummySakuraView extends SakuraView {
	private static final String TAG = "DummySakuraView";
	String sid = null;
	String stext = null;
	public DummySakuraView(Context ctx){super(ctx);}
	public void changeSurface(String id) {
	    Log.d(TAG, " sid => " + id);
	    if ( this.sid == null ) {
		this.sid = id;
		this.stext = id;
	    }
	    else if ( sid.equalsIgnoreCase(id) == false ) {
		this.sid = id;
		this.stext = this.stext + "," + id;
	    }
	}

	int talkCalledTime = 0;

	@Override
	public void startTalkingAnimation() {
	    talkCalledTime++;
	    Log.d(TAG, "startTalkingAnimation called " + talkCalledTime + " times");
	}

	String aid = null;
	String aidz = null;
	@Override
	public void loadAnimation(String id) {
	    aid = id;
	    if ( aidz == null )
		aidz = id;
	    else
		aidz += "," + id;
	}

	@Override
	public void startAnimation() {
	    // do nothing
	}

    }

    class DummyBalloon extends Balloon {
	String dispText = null;
	String text = null;
	public DummyBalloon(Context ctx){super(ctx);}
	public void setText(String str){
	    Log.d(TAG, "got text:" + str);
	    if ( this.text == null ) {
		this.text = str; 
		dispText = str;
	    }
	    else {
		dispText = str;
		this.text += str;// keep appending text
	    }
	}
    }

    DummySakuraView sakura = null;//new DummySakuraView();
    DummyKeroView kero = null;//new DummyKeroView();
    DummyBalloon bSakura = null;//new DummyBalloon();
    DummyBalloon bKero = null;//new DummyBalloon();
    SScriptRunner sr = null;

    boolean stopCalled = false;
    SScriptRunner.StatusCallback c = new SScriptRunner.StatusCallback() {
	    public void stop() {
		stopCalled = true;
	    }
	};
    protected void setUp() throws Exception {
	super.setUp();
	mContext = getContext();
	sakura = new DummySakuraView(mContext);
	kero = new DummyKeroView(mContext);
	bSakura = new DummyBalloon(mContext);
	bKero = new DummyBalloon(mContext);
	sr = SScriptRunner.getInstance(mContext);
	sr.setViews(sakura, kero, bSakura, bKero);
	sr.setNoWaitMode(true);
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
	String cmd = "\\4\\5\\6\\v\\_n\\_V\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();

	cmd = "\\_q\\habcde\\_l[100]\\_a[45]\\_v[000]fghijk\\_n\\_V\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("abcdefghijk", bSakura.text);
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

    public void testParsingSurfaceRegExp() {
	String t = "[10]";
	Matcher m = PatternHolders.surface_ptrn.matcher(t);
	assertTrue(m.find());
	Util.printMatch(m);

	t = "0";
	m  = PatternHolders.surface_ptrn.matcher(t);
	assertTrue(m.find());
	Util.printMatch(m);

	t = "9";
	m  = PatternHolders.surface_ptrn.matcher(t);
	assertTrue(m.find());
	Util.printMatch(m);

	t = "a";
	m = PatternHolders.surface_ptrn.matcher(t);
	assertFalse(m.find());

	t = "[a]";
	m = PatternHolders.surface_ptrn.matcher(t);
	assertFalse(m.find());

	t = "[-1]"; // this should go through
	m = PatternHolders.surface_ptrn.matcher(t);
	assertTrue(m.find());
    }

    public void testSurfaceChangeSakura() {
	sakura = new DummySakuraView(mContext);
	bSakura = new DummyBalloon(mContext);
	sr.setViews(sakura, kero, bSakura, bKero);
	String t = "\\h\\s0\\e";
	sr.addMsgToQueue(new String[]{t});
	sr.run();
	
	assertEquals("0", sakura.stext);

	t = "\\s[120]\\e";
	sr.addMsgToQueue(new String[]{t});
	sr.run();
	Log.d(TAG, "..." + sakura.stext);
	assertEquals("120", sakura.sid);
	assertEquals("0,120", sakura.stext);

	t = "\\h\\s10wrong\\s[10]\\e";
	sr.addMsgToQueue(new String[]{t});
	sr.run();
	assertEquals("10", sakura.sid);
	assertEquals("0,120,1,10", sakura.stext);
	assertEquals("0wrong",bSakura.dispText);
    }

    public void testParsingAnimationRegExp() {
	String t = "[0]";
	Matcher m = PatternHolders.ani_ptrn.matcher(t);
	assertTrue(m.find());
	assertEquals("0", m.group(1));

	t = "[10,wait]";
	m = PatternHolders.ani_ptrn.matcher(t);
	assertTrue(m.find());
	assertEquals("10", m.group(1));

	t = "[abc,xxx]";
	m = PatternHolders.ani_ptrn.matcher(t);
	assertFalse(m.find());

	t = "[102,]"; // this should not match
	m = PatternHolders.ani_ptrn.matcher(t);
	assertFalse(m.find());

	t = "[-1,wait]"; // should not match either...
	m = PatternHolders.ani_ptrn.matcher(t);
	assertFalse(m.find());
    }

    public void testAnimation() {
	sakura = new DummySakuraView(mContext);
	//bSakura = new DummyBalloon(mContext);
	sr.setViews(sakura, kero, bSakura, bKero);

	String cmd = "\\halala\\i[0]opqrst\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();

	assertEquals(12, sakura.talkCalledTime );
	assertEquals("0", sakura.aid);
    }

    public void testCallback() {
	stopCalled = false;
	String cmd = "\\habcde\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertFalse(stopCalled);

	sr.setCallback(c);
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertTrue(stopCalled);
    }
}
