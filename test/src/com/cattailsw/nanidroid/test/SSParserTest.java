package com.cattailsw.nanidroid.test;

import android.test.AndroidTestCase;
import com.cattailsw.nanidroid.*;
import android.content.Context;
import android.util.Log;

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
	    this.text = str;
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
	String cmd = "\\hlalala\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	//	sr.stop();
	assertEquals("lalala", bSakura.text);
	
	cmd = "\\0abcdefg\\n";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("abcdefg", bSakura.text);
    }

    public void testKeroSpeak() {
	String cmd = "\\1xxxxxx\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("xxxxxx", bKero.text);

	cmd = "\\habcde\\uyyyyyy\\e";
	sr.addMsgToQueue(new String[]{cmd});
	sr.run();
	assertEquals("yyyyyy", bKero.text);
	sr.stop();
    }

}
