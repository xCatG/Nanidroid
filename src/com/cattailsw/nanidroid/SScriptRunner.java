package com.cattailsw.nanidroid;

import android.content.Context;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import android.view.View;
import java.util.regex.Matcher;
import android.util.Log;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;

public class SScriptRunner implements Runnable {
    private static final String TAG = "SScriptRunner";

    private static SScriptRunner _self = null;
    private static final ConcurrentLinkedQueue<String> mMsgQueue = new ConcurrentLinkedQueue<String>();

    private LayoutManager layoutMgr = null;

    public interface StatusCallback {
	public void stop();
	public void canExit();
    }

    public static SScriptRunner getInstance(Context ctx) {
	if ( _self == null )
	    _self = new SScriptRunner(ctx);

	return _self;
    }

    SakuraView sakura = null;
    KeroView kero = null;
    Balloon sakuraBalloon = null;
    Balloon keroBalloon = null;
    Ghost g = null;
    Context mCtx = null;

    private SScriptRunner(Context ctx) {
	mCtx = ctx.getApplicationContext();
    }

    public void setViews(SakuraView s, KeroView k, Balloon bS, Balloon bK){
	sakura = s;
	kero = k;
	sakuraBalloon = bS;
	keroBalloon = bK;

	sakura.setUiEventCallback(cbSakura);
	kero.setUiEventCallback(cbKero);
    }

    public void setGhost(Ghost _g){
	g = _g;

    }

    public void setLayoutMgr(LayoutManager lm) {
	layoutMgr = lm;
    }

    public synchronized void addMsgToQueue(Collection<String> inCol) {
    	mMsgQueue.addAll(inCol);
    }
    
    public synchronized void addMsgToQueue(String []msgs){
	for( String s : msgs ) 
	    mMsgQueue.add(s);
    }
    
    private Boolean isRunning = false;

    private String msg = null;

    private boolean no_wait_mode = false;
    public void setNoWaitMode(boolean wait){
	no_wait_mode = wait;
    }

    private StatusCallback cb = null;
    public void setCallback(StatusCallback c){
	cb = c;
    }

    private static final int RUN = 42;
    private static final int STOP = RUN+1;
    private static final int INC_CLOCK = STOP + 1;
    private static final long CLOCK_STEP = 1000;
    private Handler loopHandler = new Handler() {
	    @Override
	    public void handleMessage(Message m) {
		if ( m.what == RUN )
		    loopControl();
		else if ( m.what == STOP )
		    stop();

	    }
	};

    private Handler clockHandler = new Handler() {
	    @Override
	    public void handleMessage(Message m) {
		if ( m.what == INC_CLOCK ) {
		    perClockEvent();
		    clockHandler.sendEmptyMessageAtTime(INC_CLOCK, SystemClock.uptimeMillis() + 1000);
		}
	    }
	};

    private void loopControl() {
	if (msg != null && charIndex < msg.length() ){
	    parseMsg();
	    updateUI();
	    if ( no_wait_mode )
		loopControl();
	    else
		loopHandler.sendEmptyMessageDelayed(RUN, waitTime);
	}
	else {
	    reset();
	    msg = getFromQueue();//mMsgQueue.poll();
	    if ( msg == null ) {
		if ( no_wait_mode )
		    stop();
		else
		    loopHandler.sendEmptyMessageDelayed(STOP, waitTime);
	    }
	    else {
		if ( no_wait_mode )
		    loopControl();
		else
		    loopHandler.sendEmptyMessageDelayed(RUN, waitTime);
	    }
	}
    }

    private long startTime = 0;

    void startClock() {
	Log.d(TAG, "startClock called");
	startTime = SystemClock.uptimeMillis();
	clockHandler.sendEmptyMessageDelayed(INC_CLOCK, CLOCK_STEP);

	
	parseShioriResponseAndInsert(g.doShioriEvent(restore?"OnWindowStateRestore":"OnBoot", null));
	restore = false;
    }

    void stopClock() {
	clockHandler.removeMessages(INC_CLOCK);
    }

    public synchronized void run() {
	synchronized( isRunning ) {
	    if ( isRunning )
		return;
	    isRunning = true;
	}

	reset();
	msg = getFromQueue();//rewriteMsg(mMsgQueue.poll());
	if ( msg == null ) {
	    stop();
	}
	else {
		if ( no_wait_mode )
			loopControl();
		else
			loopHandler.sendEmptyMessage(RUN);
	    //loopControl();
	    //parseMsg();
	}
    }

    private String getFromQueue(){
	return rewriteMsg(mMsgQueue.poll());
    }

    private String rewriteMsg(String inStr){
	// search in string for things that match rewrite rule
	if ( g == null || inStr == null )
	    return inStr;

	inStr = inStr.replaceAll("%username", g.getUsername());
	inStr = inStr.replaceAll("%selfname2?", g.getSakuraName());
	inStr = inStr.replaceAll("%keroname", g.getKeroName());


	return inStr;
    }

    public synchronized void stop() {
	synchronized( isRunning ) {
	    isRunning = false;
	}
	bSakuraId = "-1";
	bKeroId = "-1";
	updateUI();

	if ( cb != null ) {
	    cb.stop();
	    if ( exitPending )
		cb.canExit();
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
	bSakuraId = "0";
	bKeroId = "-1";
	sakuraAnimationId = null;
	keroAnimationId = null;
    }

    public static final long WAIT_UNIT = 50; // wait unit is 50ms
    public static final long WAIT_YEN_E = 1000; // wait one second before clearing?

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

    private String bSakuraId = "0";
    private String bKeroId = "-1";

    private void appendChar(char c) {
	if ( sync ) {
	    sakuraMsg.append(c);
	    keroMsg.append(c);
	}
	else if ( sakuraTalk )
	    sakuraMsg.append(c);
	else
	    keroMsg.append(c);

	if ( keroMsg.length() > 0 )
	    bKeroId = "0";
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
		    waitTime = WAIT_YEN_E;
		    break loop;
		case 'n': // new line, but need to handle and discard \n[XXX] cases		    
		    appendChar('\n');

		    // need to check if there are [] left right after this
		    String rest = msg.substring(charIndex, msg.length());
		    Matcher m = PatternHolders.sqbracket_half_number.matcher(rest);
		    if ( m.find() ) { // [xxx] is found in the sequence
			charIndex += m.group().length(); // skip the whole matching [] portions
			Log.d(TAG, "skipping unsupported tag n" + m.group());
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
		case 'w':
		    char c = msg.charAt(charIndex); charIndex++;
		    if ( Character.isDigit(c) ) {
			int cint = (c - '0');
			waitTime = cint * WAIT_UNIT;
			//Log.d(TAG, "waiting" + waitTime);
			break loop;
		    }
		    break;
		case 'b':
		    if ( handle_balloon() )
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
		Log.d(TAG, "skipping unsupported tag _" + c + m.group());
	    }
	    break;
	case 'n':
	case 'V':
	    Log.d(TAG, "ignore unsupported _" + c + " tag");
	    break;
	case 'b':
	    return handle_balloon();
	case 'w':
	    leftString = msg.substring(charIndex, msg.length());
	    m = PatternHolders.sqbracket_half_number.matcher(leftString);
	    if ( m.find() ) {
		charIndex += m.group().length();
		try {
		    waitTime = Long.parseLong(m.group(1));
		    return true;
		}
		catch(Exception e) {

		}
		//waitTime = 
	    }
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

    private boolean handle_balloon(){
	// need to check for
	// [id] and [filename,x,y] type commands
	String left = msg.substring(charIndex, msg.length());
	Matcher m = PatternHolders.balloon_ptrn.matcher(left);
	if ( m.find()) {
	    String sid = null;
	    if ( m.group(2) != null )
		sid = m.group(2);
	    else
		sid = m.group(1);

	    changeBalloon(sid);
	    charIndex += m.group().length();
	    return true;
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
	if ( g != null )
	g.doShioriEvent("OnSurfaceChange", new String[]{"Reference0: " + sakuraSurfaceId, 
							"Reference1: " + keroSurfaceId});
    }

    private void changeBalloon(String bid){
	if ( sakuraTalk) {
	    bSakuraId = bid;
	}
	else
	    bKeroId = bid;
    }

    private void queueAnimation(String aid) {
	if ( sakuraTalk ){
	    sakuraAnimationId = aid;
	}
	else {
	    keroAnimationId = aid;
	}
    }

    private int talkAnimeControl  = 0;
    private void updateUI() {
	sakura.changeSurface(sakuraSurfaceId);
	kero.changeSurface(keroSurfaceId);

	boolean sakuraAnimate = (sakuraAnimationId != null);
	boolean keroAnimate = ( keroAnimationId != null );

	if ( bSakuraId.equalsIgnoreCase("-1") && sakuraMsg.length() == 0 ){
	    //need to set sakura balloon to none
	    sakuraBalloon.setVisibility(View.INVISIBLE);
	}
	else {
	    sakuraBalloon.setVisibility(View.VISIBLE);
	    sakuraBalloon.setText(sakuraMsg.toString());
	    if ( !sakuraAnimate && talkAnimeControl == 0 ) sakura.startTalkingAnimation();
	}

	if ( bKeroId.equalsIgnoreCase("-1") && keroMsg.length() == 0 ) {
	    keroBalloon.setVisibility(View.INVISIBLE);
	}
	else {
	    keroBalloon.setVisibility(View.VISIBLE);
	    keroBalloon.setText(keroMsg.toString());
	    if ( !keroAnimate && talkAnimeControl == 0 ) kero.startTalkingAnimation();
	}

	if ( layoutMgr != null )
	    layoutMgr.checkAndUpdateLayoutParam();


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
	talkAnimeControl++;
	if ( talkAnimeControl == 10 )
	    talkAnimeControl = 0;
    }
    private int lastSec = 0;
    private int lastMin = 0;
    private int lastHour = 0;

    private void startPerSecondAnimation(SakuraView target){
	double p = Math.random();
	if ( p < 0.25f ){
	    target.startRarelyAnimation();
	}
	else if ( p < 0.50f ) {
	    target.startSometimesAnimation();
	}
    }

    private void doPerSecondEvent(int hr) {
	startPerSecondAnimation(sakura);
	startPerSecondAnimation(kero);
	
	ShioriResponse r = g.sendOnSecondChange(hr);
	parseShioriResponseAndInsert( r );
    }

    private void doPerMinuteEvent(int hr) {
	ShioriResponse r = g.sendOnMinuteChange(hr);
	parseShioriResponseAndInsert( r );
    }

    private void doPerHourEvent() {

    }


    private void perClockEvent() {
	long mills = SystemClock.uptimeMillis() - startTime;
	// every second
	int seconds = (int) ( mills / 1000 );
	int minute = seconds / 60;
	int hour = minute / 60;
	minute = minute % 60;
	seconds = seconds % 60;

	if ( seconds - lastSec >= 1 || seconds == 0) {
	    doPerSecondEvent(hour);
	    lastSec = seconds;	   
	}
	// every minute
	if ( minute - lastMin >= 1 || (lastMin == 59 && minute == 0)) {
	    doPerMinuteEvent(hour);
	    lastMin = minute;
	}

	// every hour
	if ( hour - lastHour >= 1 ) {
	    doPerHourEvent();
	    lastHour = hour;
	}
	//Log.d(TAG, "perClockEvent called at:["+hour+":"+minute+":"+seconds+"]");
    }

    private void parseShioriResponseAndInsert(ShioriResponse res){
	//Log.d(TAG, "value=" + res.getKey("Value"));	
	if ( res.getStatusCode() != 200 )
	    return;
	Log.d(TAG, res.toString());
	msg = res.getKey("Value");
	addMsgToQueue(new String[]{msg});
	if ( !isRunning )
	    run();
    }

    private ShioriResponse doMouseClick(int x, int y, boolean sakura, int collid, int buttonid) {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnMouseClick", new String[]{
				"Reference0: " + x,
				"Reference1: " + y,
				"Reference2: 0",
				"Reference3: " + ((sakura)?"0":"1"),
				"Reference4: " + collid,
				"Reference5: " + buttonid}
				);
	    return r;
	}
	return null;
    }

    private ShioriResponse doMouseDblClick(int x, int y, boolean sakura, int collid, int buttonid) {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnMouseDoubleClick", new String[]{
				"Reference0: " + x,
				"Reference1: " + y,
				"Reference2: 0",
				"Reference3: " + ((sakura)?"0":"1"),
				"Reference4: " + collid,
				"Reference5: " + buttonid}
				);
	    return r;
	}
	return null;
    }

    private ShioriResponse doMouseWheel(int x, int y, int wheelO, boolean sakura, int collid) {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnMouseWheel", new String[]{
				"Reference0: " + x,
				"Reference1: " + y,
				"Reference2: " + wheelO,
				"Reference3: " + ((sakura)?"0":"1"),
				"Reference4: " + collid}
				);
	    return r;
	}
	return null;
    }

    private ShioriResponse doMouseMove(int x, int y, int wheelO, boolean sakura, int collid) {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnMouseMove", new String[]{
				"Reference0: " + x,
				"Reference1: " + y,
				"Reference2: " + wheelO,
				"Reference3: " + ((sakura)?"0":"1"),
				"Reference4: " + collid}
				);
	    return r;
	}
	return null;
    }

    private SakuraView.UIEventCallback cbSakura = new SakuraView.UIEventCallback() {
	    public void onHit(int type, int x, int y, int orientation, int collId, int buttonid){
		ShioriResponse r = null;
		switch( type ) {
		case SakuraView.UIEventCallback.TYPE_SINGLE_CLICK:
		    r = doMouseClick(x, y, true, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_DOUBLE_CLICK:
		    r = doMouseDblClick(x, y, true, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_WHEEL:
		    r= doMouseWheel(x, y, orientation, true, collId);
		    break;
		case SakuraView.UIEventCallback.TYPE_MOVE:
		    r = doMouseMove(x, y, orientation, true, collId);
		    break;
		}
		if ( r != null ) 
		    parseShioriResponseAndInsert(r);
	    }
	};

    private SakuraView.UIEventCallback cbKero = new SakuraView.UIEventCallback() {
	    public void onHit(int type, int x, int y, int orientation, int collId, int buttonid){
		ShioriResponse r = null;
		switch( type ) {
		case SakuraView.UIEventCallback.TYPE_SINGLE_CLICK:
		    r = doMouseClick(x, y, false, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_DOUBLE_CLICK:
		    r = doMouseDblClick(x, y, false, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_WHEEL:
		    r = doMouseWheel(x, y, orientation, false, collId);
		    break;
		case SakuraView.UIEventCallback.TYPE_MOVE:
		    r = doMouseMove(x, y, orientation, false, collId);
		    break;
		}
		if ( r != null )
		    parseShioriResponseAndInsert(r);
	    }
	};    


    public void doMinimize() {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnWindowStateMinimize", null);	    
	}
    }
    boolean restore = false;
    public void doRestore() {
	Log.d(TAG, "doRestore called");
	restore = true;
    }

    private boolean exitPending = false;
    public void doExit() {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnClose", null);
	    if (r != null )
		parseShioriResponseAndInsert(r);
	}
	exitPending = true;
    }

}
