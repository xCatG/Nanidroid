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
import com.cattailsw.nanidroid.util.AnalyticsUtils;
import java.util.Vector;

public class SScriptRunner implements Runnable {
    private static final String TAG = "SScriptRunner";

    private static SScriptRunner _self = null;

    public interface StatusCallback {
	public void stop();
	public void canExit();
	public void ghostSwitchScriptComplete();
	//public void showUserInputBox(String id);
	//public void showUserSelection(int timeout);
    }

    public interface UICallback {
	public void showUserInputBox(String id);
	public void showUserSelection(String []textlabel, String[] ids);
    }

    public static SScriptRunner getInstance(Context ctx) {
	if ( _self == null )
	    _self = new SScriptRunner(ctx);

	return _self;
    }

    public static final long WAIT_UNIT = 50; // wait unit is 50ms
    public static final long WAIT_YEN_E = 1000; // wait one second before clearing?

    private static final int RUN = 42;
    private static final int STOP = RUN+1;
    private static final int INC_CLOCK = STOP + 1;
    private static final long CLOCK_STEP = 1000;

    private static final ConcurrentLinkedQueue<String> mMsgQueue = new ConcurrentLinkedQueue<String>();

    private LayoutManager layoutMgr = null;
    private SakuraView sakura = null;
    private KeroView kero = null;
    private Balloon sakuraBalloon = null;
    private Balloon keroBalloon = null;
    private Ghost g = null;
    private Context mCtx = null;
    private UICallback ucb = null;
    private StatusCallback cb = null;
    //private boolean paused = false;
    private Boolean isRunning = false;
    private String msg = null;
    private boolean no_wait_mode = false;
    private long startTime = 0;
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

    private int talkAnimeControl  = 0;
    private int lastSec = 0;
    private int lastMin = 0;
    private int lastHour = 0;

    private boolean restore = false;
    private boolean exitPending = false;
    private boolean changingPending = false;
    private boolean paused = false;

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
	String lName = null;//g.getGhostName();
	String lScript = null;

	if ( g != null ) {
	    lName = g.getGhostName();
	    lScript = null;
	}

	g = _g;

	if ( lName != null ) {
	    if ( g.getCreateCount() > 1 ) {
		doShioriEvent("OnGhostChanged", new String[]{lName, lScript});
	    }
	    else {
		doShioriEvent("OnFirstBoot", new String[]{"0"});
		AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW, "onfirstboot", g.getGhostId(), 0);
	    }
	}
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
    

    public void setNoWaitMode(boolean wait){
	no_wait_mode = wait;
    }

    public void setCallback(StatusCallback c){
	cb = c;
    }

    public void setUICallback(UICallback c) {
	ucb = c;
    }

    public void resumeEvt() {
	if ( isRunning && paused ) {
	    paused = false;
	    loopHandler.sendEmptyMessage(RUN);
	}
	else {
		if ( paused ) paused = false;
		run();
	}
    }

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
	if ( paused ) return;
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


    void startClock() {
	Log.d(TAG, "startClock called");
	startTime = SystemClock.uptimeMillis();
	clockHandler.sendEmptyMessageDelayed(INC_CLOCK, CLOCK_STEP);

	if ( restore )
	    parseShioriResponseAndInsert(g.doShioriEvent("OnWindowStateRestore", null));
	else
	    doBoot();
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

    public synchronized void clearMsgQueue(){
	mMsgQueue.clear();
	msg = null;
	stop();
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
	    if ( exitPending ) {
		if ( cb != null ) cb.canExit();
		exitPending = false;
	    }

	    if ( changingPending ) {
		changingPending = false;
		if ( cb != null ) cb.ghostSwitchScriptComplete();
		g.unload();
	    }
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
	//sakuraSurfaceId = "0";
	//keroSurfaceId = "10";
	bSakuraId = "-1";
	bKeroId = "-1";
	sakuraAnimationId = null;
	keroAnimationId = null;
    }


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
		case 'q':
		    if ( handle_selection() )
			break loop;
		    break;
		case '-':
		case '4':
		case '5':
		case '6':
		case 'v':
		    // ignore
		    Log.d(TAG, "ignore unsupported " + c2 + " tag");
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "" + c2, -1);
		    break;
		default:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport_other", "" + c2, -1);
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
		AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "_" + c + m.group(), -1);
	    }
	    break;
	case 'n':
	case 'V':
	    Log.d(TAG, "ignore unsupported _" + c + " tag");
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "_" + c, -1);
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
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_err", "_" + c + m.group(), -1);
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
	Matcher m = PatternHolders.open_input.matcher(leftString);
	if ( m.find() ) {
	    charIndex += m.group().length();
	    String id = m.group(1);
	    openUserInputBox(id);
	}
	else {
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_unsupport", "exclaim_type", -1);
	}
	return false;
    }

    private void openUserInputBox(String id){
	if ( id == null ){
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_err", "open input box", -1);
	    return;
	}

	// enter pause mode
	if ( ucb != null ) {
	    paused = true;
	    ucb.showUserInputBox(id);
	}
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
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_SSC, "tag_err", "\\s" + left.substring(0,4), -1); // just record 4 more chars after \\s
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

    private boolean handle_selection() {
	// \q[text,Id] or \q[text,Id,xxx,x,x]?
	// text to display is
	// text from \q, then anything follow...
	// but text needs to be formatted as a clickable span
	charIndex = charIndex -2;// back up until \q
	//String left = msg.substring(charIndex, msg.length());
	Matcher m = PatternHolders.q_choice_ptrn.matcher(msg);
	Vector<String> labels = new Vector<String>(2);
	Vector<String> ids = new Vector<String>(2);
	while(m.find()) {
	    //Log.d(TAG, "found:" + m.group());
	    msg = m.replaceFirst(m.group(1));
	    //Log.d(TAG, "msg=" + msg);
	    labels.add(m.group(1));
	    ids.add(m.group(2));
	    m = PatternHolders.q_choice_ptrn.matcher(msg);

	}

	if ( ucb != null ) {
	    wholeline = true;
	    String[] lbl = new String[labels.size()];//(String[])labels.toArray();
	    labels.toArray(lbl);
	    String[] idz = new String[ids.size()];
	    ids.toArray(idz);
	    ucb.showUserSelection(lbl, idz);
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

    private void doMouseClick(int x, int y, boolean sakura, int collid, int buttonid) {
	doShioriEvent("OnMouseClick", new String[]{
			  "" + x,
			  "" + y,
			  "0",
			  ((sakura)?"0":"1"),
			  ((collid>-1)?""+collid:""),
			  "" + buttonid,
			  "touch"}
		      );
    }

    private void doMouseDblClick(int x, int y, boolean sakura, int collid, int buttonid) {
	doShioriEvent("OnMouseDoubleClick", new String[]{
			  "" + x,
			  "" + y,
			  "0",
			  ((sakura)?"0":"1"),
			  ((collid>-1)?""+collid:""),
			  "" + buttonid,
			  "touch"}
				);
    }

    private void doMouseWheel(int x, int y, int wheelO, boolean sakura, int collid) {
	doShioriEvent("OnMouseWheel", new String[]{
			  "" + x,
			  "" + y,
			  "" + wheelO,
			  ((sakura)?"0":"1"),
			  ((collid>-1)?""+collid:""),
			  null,
			  "touch"}
		      );
    }

    private void doMouseMove(int x, int y, int wheelO, boolean sakura, int collid) {
	doShioriEvent("OnMouseMove", new String[] {
			  ""+x, 
			  ""+y, 
			  ""+wheelO, 
			  ((sakura)?"0":"1"), 
			  ((collid>-1)?""+collid:""),
			  null,
			  "touch"
		      });
    }

    private SakuraView.UIEventCallback cbSakura = new SakuraView.UIEventCallback() {
	    public void onHit(int type, int x, int y, int orientation, int collId, int buttonid){
		switch( type ) {
		case SakuraView.UIEventCallback.TYPE_SINGLE_CLICK:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "click", collId);
		    doMouseClick(x, y, true, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_DOUBLE_CLICK:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "dblclick", collId);		    
		    doMouseDblClick(x, y, true, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_WHEEL:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "wheel", collId);		    
		    doMouseWheel(x, y, orientation, true, collId);
		    break;
		case SakuraView.UIEventCallback.TYPE_MOVE:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "sakuraview_touch", "move", collId);
		    doMouseMove(x, y, orientation, true, collId);
		    break;
		}
	    }
	};

    private SakuraView.UIEventCallback cbKero = new SakuraView.UIEventCallback() {
	    public void onHit(int type, int x, int y, int orientation, int collId, int buttonid){
		clearMsgQueue();
		switch( type ) {
		case SakuraView.UIEventCallback.TYPE_SINGLE_CLICK:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "click", collId);
		    doMouseClick(x, y, false, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_DOUBLE_CLICK:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "dblclick", collId);
		    doMouseDblClick(x, y, false, collId, buttonid);
		    break;
		case SakuraView.UIEventCallback.TYPE_WHEEL:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "wheel", collId);
		    doMouseWheel(x, y, orientation, false, collId);
		    break;
		case SakuraView.UIEventCallback.TYPE_MOVE:
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_UI_TOUCH, "keroview_touch", "move", collId);
		    doMouseMove(x, y, orientation, false, collId);
		    break;
		}
	    }
	};    


    public void doMinimize() {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent("OnWindowStateMinimize", null);	    
	}
    }

    public void doRestore() {
	Log.d(TAG, "doRestore called");
	restore = true;
    }

    public void doExit() {
	doShioriEvent("OnClose", null);
	exitPending = true;
    }

    public void doGhostChanging(String nextName, String type, String nextPath) {
	changingPending = true;
	boolean hasRes = doShioriEvent("OnGhostChanging", new String[]{nextName, type, null, nextPath});	
	/*	if ( hasRes == false ) {
	    msg = null;
	    if( cb != null ) {
		stop();	    
	    }
	    }*/
    }

    public void doInstallBegin(String ghostId) {
	String ref[] = new String[] {"ghost", ghostId, ghostId};
	doShioriEvent("OnInstallBegin", ref);
    }
    
    public void doInstallComplete(String ghostId) {
	String ref[] = new String[] {"ghost", ghostId, ghostId};
	doShioriEvent("OnInstallComplete", ref);
    }
	
    public boolean doShioriEvent(String evt, String[] ref) {
	if ( g != null ) {
	    ShioriResponse r = g.doShioriEvent(evt, ref);
	    if ( r != null ) {
		parseShioriResponseAndInsert(r);
		return true;
	    }
	}
	return false;
    }

    public void doBoot() {
	if ( g != null ) {
	    String gshellname = g.getShellName();
	    long cCount = g.getCreateCount();
	    Log.d(TAG, gshellname + " create count" + cCount);
	    if ( cCount > 1 ) {
		doShioriEvent("OnBoot", new String[]{gshellname});
		AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW, "onboot", g.getGhostId(), (int)cCount);
	    }
	    else {
		Log.d(TAG, "do OnFirstBoot");
		doShioriEvent("OnFirstBoot", new String[]{"0"});
		AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PGM_FLOW, "onfirstboot", g.getGhostId(), 0);
	    }
	}
    }

    public String getStringValueFromShiori(String id) {
	if ( g != null )
	    return g.getStringFromShiori(id);

	return null;
    }

    public void doUserInput(String id, String input) {
	doShioriEvent("OnUserInput", new String[]{id, input});
    }

    public void doOnChoiceSelect(String id) {
	// before doing choice select, need to clear current queue...
	clearMsgQueue();
	doShioriEvent("OnChoiceSelect", new String[]{id});
    }

}
