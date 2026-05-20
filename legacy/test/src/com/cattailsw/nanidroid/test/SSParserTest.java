package com.cattailsw.nanidroid.test;

import android.test.AndroidTestCase;
import com.cattailsw.nanidroid.*;
import android.content.Context;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Vector;

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
	@Override
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
	    public void canExit() {}
	    public void ghostSwitchScriptComplete(){}
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
	String sqbreket_tester = "[half]efghi";
	Matcher m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertTrue(m.find());
	assertEquals("[half]", m.group());
	assertEquals("half", m.group(1));

	sqbreket_tester = "[100]ksjdlaksjklwje";
	m = PatternHolders.sqbracket_half_number.matcher(sqbreket_tester);
	assertTrue(m.find());
	assertEquals("[100]", m.group());
	assertEquals("100", m.group(1));

	sqbreket_tester = "[100%]ksjdlaksjklwje";
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

	t = "\\t\\h\\s[20]\\n\\w9\\u\\s[10]\\n\\h\\s0";
	sr.addMsgToQueue(new String[]{t});
	sr.run();
	assertEquals("0", sakura.sid);
	assertEquals("0,120,1,10,20,0", sakura.stext);
	assertEquals("\n", bSakura.dispText); 

	//assertEquals("0,120,1,10", sakura.stext);
	//assertEquals("0wrong",bSakura.dispText);	
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

	String cmd = "\\halala\\i[0]opqrstmnopqrst\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();

	assertEquals(2, sakura.talkCalledTime );
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

    public void testUrlFilterPattern(){

	Pattern url = Pattern.compile(".*.nar");
	String s = "http://xxx.xx.xx/xxx/xxx.nar";
	Matcher m = url.matcher(s);
	assertTrue( m.find());

	m = PatternHolders.url_ptrn.matcher(s);
	assertTrue( m.find() );

	s = "https://xxx.xxx.xxx/xxx/xxx.nar";
	m = PatternHolders.url_ptrn.matcher(s);
	assertTrue( m.find() );

	s = "http://11.22.33.44/~xxx/asdasdas_asdas/xx.nar";
	m = PatternHolders.url_ptrn.matcher(s);
	assertTrue( m.find() );

	s = "http://1.2.3.4/~%20easdjkasjdk/asjdklasjkl/sajdkls.asdkjl.nar";
	m = PatternHolders.url_ptrn.matcher(s);
	assertTrue( m.find() );

	s = "http://11.22.33.44/~xxx/asdasdas_asdas/xx.zip";
	m = PatternHolders.url_ptrn.matcher(s);
	assertTrue( m.find() );

	s = "http://1.2.3.4/~%20easdjkasjdk/asjdklasjkl/sajdkls.asdkjl_.zip";
	m = PatternHolders.url_ptrn.matcher(s);
	assertTrue( m.find() );

	s = "http://www.google.com/";
	m = PatternHolders.url_ptrn.matcher(s);
	assertFalse( m.find() );

	s = "http://www.google.com/.mp3";
	m = PatternHolders.url_ptrn.matcher(s);
	assertFalse( m.find() );

    }

    boolean uscbCalled = false;
    SScriptRunner.UICallback tcwr_ucb = new SScriptRunner.UICallback() {
		public void showUserInputBox(String id) {}
		public void showUserSelection(String []t, String[] i) {
		    uscbCalled = true;
		    Log.d(TAG, "showUserSelection called");
		    assertEquals(2, t.length);
		    assertEquals(2, i.length);
		    assertEquals("fgh", t[0]);
		    assertEquals("lmno", t[1]);
		    assertEquals("lalala asda", i[0]);
		    assertEquals("lalala aaaa", i[1]);
		}
	};

    public void testChoiceWithRunner() {
	String s = "\\0abcde\\q[fgh,lalala asda]ijk\\q[lmno,lalala aaaa]\\e";
	bSakura = new DummyBalloon(mContext);
	sr.setViews(sakura, kero, bSakura, bKero);
	uscbCalled = false;
	sr.setUICallback(tcwr_ucb);
	sr.addMsgToQueue(new String[]{s});
	sr.run();

	Log.d(TAG, "sakura text"+bSakura.dispText);
	assertEquals("abcdefghijklmno", bSakura.dispText);
	assertTrue(uscbCalled);
	sr.setUICallback(null);
    }


    public void testQChoice() {
	// suppose \\q is stripped away already, just need to match the brackets
	String s = "[abc,asdkllaskdl;asdkl asdsa]";
	Matcher m = PatternHolders.sqbracket_q_title.matcher(s);
	assertTrue( m.find() );
	assertEquals( 3, m.groupCount());
	assertEquals( "abc", m.group(1) );
	assertEquals( "asdkllaskdl;asdkl asdsa", m.group(2) );
	assertEquals( "", m.group(3));

	s = "[abc asds,asdkllaskdl;asdkl asdsa,askdlaks;,qwewqew]";
	m = PatternHolders.sqbracket_q_title.matcher(s);
	assertTrue( m.find() );
	assertEquals("abc asds", m.group(1));
	assertEquals("asdkllaskdl;asdkl asdsa", m.group(2));
	assertEquals("askdlaks;,qwewqew", m.group(3));

	s = "[abc asds,asdkllaskdl asdkl asdsa 1,askdlaks;,qwewqew]";
	m = PatternHolders.sqbracket_q_title.matcher(s);
	assertTrue( m.find() );

	s = "[abc,asds]\\nasdkllaskdl asdkl asdsa 1,askdlaks;,qwewqew]";
	m = PatternHolders.sqbracket_q_title.matcher(s);
	assertTrue( m.find() );
	assertEquals("abc", m.group(1));
	assertEquals("asds", m.group(2));

	s = "[aagasdds asdqwewe]";
	m = PatternHolders.sqbracket_q_title.matcher(s);
	assertFalse( m.find() );

	s = "[qkw qwe qwewe,OnXXXXXX XXXX]";
	m = PatternHolders.sqbracket_q_title.matcher(s);
	assertTrue( m.find() );

	m = PatternHolders.sqbracket_q_withOn.matcher(s);
	assertTrue(m.find());
	assertEquals("qkw qwe qwewe", m.group(1));
	assertEquals("OnXXXXXX XXXX", m.group(2));
	
    }


    public void testFindQ() {
	String s = "[a,xxx]\\n\\q[b,yyy]\\n\\q[c,zzz]\\n\\e";
	// we want two arrays, qText={"a","b","c"} and qIdz={"xxx","yyy","zzz"}
	// to display in separate dlg...?

	// actually, what we have is "[a,xxx]\\n\\q[b,yyy]\\n\\q[c,zzz]\\n\\e"
	Matcher m = PatternHolders.sqbracket_q_title.matcher(s);
	assertTrue(m.find());
	Log.d(TAG, "matched:" + m.group());
	Vector<String> vtext =  new Vector<String>();
	Vector<String> vidz =  new Vector<String>();

	vtext.add(m.group(1));
	vidz.add(m.group(2));
	String l = s.substring(m.group().length());
	Log.d(TAG, "left=" + l);
	m = PatternHolders.q_choice_ptrn.matcher(l);

	assertTrue(m.find());
	assertEquals("b", m.group(1));
	assertEquals("yyy", m.group(2));
	vtext.add(m.group(1));
	vidz.add(m.group(2));
	while( m.find() ) {
	    vtext.add(m.group(1));
	    vidz.add(m.group(2));
	}

	assertEquals(3, vtext.size());
	assertEquals(3, vidz.size());

	s = "\\q[a,sdkjkl asdas,lwkel;qwk]\\nakjkaljsdkladkjlasd\\q[aksdjkkjkl, oqwjeop lalk;,aijpqjl ,asldkl;]\\n\\e";
	m = PatternHolders.q_choice_ptrn.matcher(s);
	assertTrue(m.find());
	assertEquals("a", m.group(1));
	assertEquals("sdkjkl asdas", m.group(2));

	assertTrue(m.find());
	assertEquals("aksdjkkjkl", m.group(1));
	assertEquals(" oqwjeop lalk;", m.group(2));
    }

    public void testReplaceQ() {
	String s = "\\q[aaaa,bbbb]\\n\\q[bbbb,askjdaklsdj]\\n\\q[cccc,askjasd]\\e";
	Matcher m = PatternHolders.q_choice_ptrn.matcher(s);
	while( m.find() ) {
	    s = m.replaceFirst(m.group(1));
	    m = PatternHolders.q_choice_ptrn.matcher(s);
	}
	assertEquals("aaaa\\nbbbb\\ncccc\\e", s);
    }

    public void testInputCmd() {
	String s = "[open,inputbox,XXXX XXX XXXX]";
	Matcher m = PatternHolders.open_input.matcher(s);
	assertTrue( m.find() );
	assertEquals( 1, m.groupCount());
	assertEquals("XXXX XXX XXXX", m.group(1));

	s = "[abc asds,asdkllaskdl asdkl asdsa 1,askdlaks;,qwewqew]";
	m = PatternHolders.open_input.matcher(s);
	assertFalse( m.find() );

	s = "[open,kakaka]";
	m = PatternHolders.open_input.matcher(s);
	assertFalse( m.find() );

    }

}
