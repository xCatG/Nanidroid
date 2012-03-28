package com.cattailsw.nanidroid;

import android.content.Context;
import java.util.concurrent.ConcurrentLinkedQueue;
import android.view.View;
import java.util.regex.Matcher;
import android.util.Log;

public class SScriptRunner {
    private static final String TAG = "SScriptRunner";

    private static SScriptRunner _self = null;
    private static final ConcurrentLinkedQueue<String> mMsgQueue = new ConcurrentLinkedQueue<String>();

    public static SScriptRunner getInstance(Context ctx) {
	if ( _self == null )
	    _self = new SScriptRunner(ctx);

	return _self;
    }

    SakuraView sakura = null;
    KeroView kero = null;
    Balloon sakuraBalloon = null;
    Balloon keroBalloon = null;
    Context mCtx = null;

    private SScriptRunner(Context ctx) {
	mCtx = ctx.getApplicationContext();
    }

    public void setViews(SakuraView s, KeroView k, Balloon bS, Balloon bK){
	sakura = s;
	kero = k;
	sakuraBalloon = bS;
	keroBalloon = bK;
    }

    public synchronized void addMsgToQueue(String []msgs){
	for( String s : msgs ) 
	    mMsgQueue.add(s);
    }
    
    private Boolean isRunning = false;

    private String msg = null;

    private void loopControl() {
	if ( charIndex < msg.length() ){
	    parseMsg();
	    updateUI();
	    loopControl();
	}
	else {
	    reset();
	    msg = mMsgQueue.poll();
	    if ( msg == null )
		stop();
	    else
		loopControl();
	}
    }

    public synchronized void run() {
	synchronized( isRunning ) {
	    if ( isRunning )
		return;
	    isRunning = true;
	}

	reset();
	msg = mMsgQueue.poll();
	if ( msg == null ) {
	    stop();
	}
	else {
	    loopControl();
	    //parseMsg();
	}
    }

    public synchronized void stop() {
	synchronized( isRunning ) {
	    isRunning = false;
	}
    }

    private void reset() {
	sync = false;
	wholeline = false;
	sakuraTalk = true;
	sakuraMsg.setLength(0);
	keroMsg.setLength(0);
	msg = "";
	charIndex = 0;
	sakuraSurfaceId = "0";
	keroSurfaceId = "10";
	sakuraAnimationId = null;
	keroAnimationId = null;
    }

    public static final long WAIT_UNIT = 50; // wait unit is 50ms

    private boolean sync = false;
    private boolean wholeline = false;
    private boolean sakuraTalk = false;
    private StringBuilder sakuraMsg = new StringBuilder();
    private StringBuilder keroMsg = new StringBuilder();
    private long waitTime = WAIT_UNIT;
    private int charIndex = 0;

    private String sakuraSurfaceId = "0";
    private String keroSurfaceId = "10";
    private String sakuraAnimationId = null;
    private String keroAnimationId = null;

    private void appendChar(char c) {
	if ( sync ) {
	    sakuraMsg.append(c);
	    keroMsg.append(c);
	}
	else if ( sakuraTalk )
	    sakuraMsg.append(c);
	else
	    keroMsg.append(c);
    }

    private void clearMsg() {
	if ( sakuraTalk ) 
	    sakuraMsg.setLength(0);
	else
	    keroMsg.setLength(0);
    }

    private void parseMsg() {
	waitTime = WAIT_UNIT;
	char c1, c2;
	loop: while(true){
	    try {
		c1 = msg.charAt(charIndex);
		charIndex++;
		if ( c1 != '\\' ) {
		    appendChar(c1);
		    if ( wholeline )
			continue;
		    else
			break loop;
		}
		// so we need to check control sequence
		c2 = msg.charAt(charIndex);
		charIndex++;
		switch(c2) {
		case '0':
		case 'h':
		    if ( sakuraTalk != true ){
			sakuraTalk = true;
			sakuraMsg.setLength(0);
		    }
		    break;
		case '1':
		case 'u':
		    sakuraTalk = false;
		    keroMsg.setLength(0);
		    break;
		case 's':
		    if ( handle_surface() )
			break loop;
		    break;
		case 'i':
		    if ( handle_animation() )
			break loop;
		    break;
		case 'e': // yen-e
		    charIndex = msg.length();
		    break loop;
		case 'n': // new line, but need to handle and discard \n[XXX] cases		    
		    appendChar('\n');

		    // need to check if there are [] left right after this
		    String rest = msg.substring(charIndex, msg.length());
		    Matcher m = PatternHolders.sqbracket_half_number.matcher(rest);
		    if ( m.find() ) { // [xxx] is found in the sequence
			charIndex += m.group().length(); // skip the whole matching [] portions
			Log.d(TAG, "skipping unsupported tag " + m.group());
		    }
		    break loop;
		case 'c': // clear msg
		    clearMsg();
		    break;
		case '_':
		    if ( handle_underscore_type( msg.substring(charIndex, msg.length()) ) )
			break loop;
		    break;
		case '!':
		    if ( handle_exclaim_type( msg.substring(charIndex, msg.length() ) ) )
			break loop;
		    break;
		case '-':
		case '4':
		case '5':
		case '6':
		case 'v':
		    // ignore
		    Log.d(TAG, "ignore unsupported " + c2 + " tag");
		    break;
		default:
		    break;
		}
	    }
	    catch(Exception e) {
		break;
	    }
	} 

	//updateUI();
    }

    // if true, break loop
    // if false, just break?
    private boolean handle_underscore_type(String leftString) {
		    // need to check for the following cases: 
		    // _b[]
		    // _n <- ignore
		    // _l[xx] <- ignore
		    // _q switch quick session
		    // _s switch sync session
		    // _a[xx] <- ignore
		    // _v[xx] <- ignore
		    // _V <- ignore
	char c = msg.charAt(charIndex);	charIndex++;
	switch( c ) {
	case 's':
	    sync = !sync;
	    break; // returns false
	case 'q':
	    wholeline = !wholeline;
	    break;
	case 'l':
	case 'a':
	case 'v':
	    // need to swallow [] after the switches
	    leftString = msg.substring(charIndex, msg.length());
	    Matcher m = PatternHolders.sqbracket_half_number.matcher(leftString);
	    if ( m.find() ) { // [xxx] is found in the sequence
		charIndex += m.group().length(); // skip the whole matching [] portions
		Log.d(TAG, "skipping unsupported tag " + c + m.group());
	    }
	    break;
	case 'n':
	case 'V':
	    Log.d(TAG, "ignore unsupported " + c + " tag");
	    break;
	default:
	    break;
	}

	return false;
    }

    private boolean handle_exclaim_type(String leftString) {
	return false;
    }

    // should break out of loop to perform surface update immediately upon hitting this tag
    private boolean handle_surface(){
	// two cases
	// \s0-9 and \s[id] case
	String left = msg.substring(charIndex, msg.length());
	Matcher m = PatternHolders.surface_ptrn.matcher(left);
	if ( m.find() ) {
	    // now, sid will be in m.group(1) or m.group(2) depending on format
	    // check for m.group(2) first?	    
	    String sid = null;
	    if ( m.group(2) != null ) {
		sid = m.group(2);
	    }
	    else {
		sid = m.group(1);
	    }
	    changeSurface(sid);
	    charIndex += m.group().length();
	    return true;
	}
	else {
	    Log.d(TAG, "malformed \\s tag at index:" + charIndex);
	}
	return false;
    }

    // same as surface, should break out and start animation immediately
    private boolean handle_animation() {
	String left = msg.substring(charIndex, msg.length());
	Matcher m = PatternHolders.ani_ptrn.matcher(left);
	if ( m.find() ) {
	    String aid = m.group(1);
	    queueAnimation(aid);
	    charIndex+= m.group().length();
	    return true;
	}
	
	return false;
    }

    private void changeSurface(String sid) {
	if ( sakuraTalk )
	    sakuraSurfaceId = sid;
	else
	    keroSurfaceId = sid;
    }

    private void queueAnimation(String aid) {
	if ( sakuraTalk ){
	    sakuraAnimationId = aid;
	}
	else {
	    keroAnimationId = aid;
	}
    }


    private void updateUI() {
	sakura.changeSurface(sakuraSurfaceId);
	kero.changeSurface(keroSurfaceId);

	boolean sakuraAnimate = (sakuraAnimationId != null);
	boolean keroAnimate = ( keroAnimationId != null );

	if ( sakuraMsg.length() == 0 ){
	    //need to set sakura balloon to none
	    sakuraBalloon.setVisibility(View.INVISIBLE);
	}
	else {
	    sakuraBalloon.setVisibility(View.VISIBLE);
	    sakuraBalloon.setText(sakuraMsg.toString());
	    if ( !sakuraAnimate) sakura.startTalkingAnimation();
	}

	if ( keroMsg.length() == 0 ) {
	    keroBalloon.setVisibility(View.INVISIBLE);
	}
	else {
	    keroBalloon.setVisibility(View.VISIBLE);
	    keroBalloon.setText(keroMsg.toString());
	    if ( !keroAnimate) kero.startTalkingAnimation();
	}

	if ( sakuraAnimate ) {
	    sakura.loadAnimation(sakuraAnimationId);
	    sakura.startAnimation();
	    sakuraAnimationId = null; // need to clear the animation after starting
	}

	if ( keroAnimate ) {
	    kero.loadAnimation(keroAnimationId);
	    kero.startAnimation();
	    keroAnimationId = null;
	}
    }

}
