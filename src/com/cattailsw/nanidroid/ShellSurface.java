package com.cattailsw.nanidroid;

import java.io.File;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;

import com.cattailsw.nanidroid.util.AnalyticsUtils;

/*
 * surface should have 
 * surface id (surface0~n in surface.txt)
 * optional below:
 * - collision
 *   collision0~31, p1, p2, p3, p4, Name
 *
 * - point.x, num
 *   point.centerx, xvalue
 *   point.centery, yvalue
 *   point.kinoko.centerx, xvalue
 *   point.kinoko.centery, yvalue
 *
 * - Ninterval,frequency
 *   0interval, sometimes/rarely/radom,X/always/runonce/never/yen-e/talk,X/bind
 * 
 * - NpatternM, surfaceid, weight, pattern_definition, x, y
 *   0pattern0, 101, 10, overlay, xvalue, yvalue
 *   0pattern1, 100, 10, overlay, xvalue, yvalue
 *   0pattern2, -1, 10, overlay, xvalue, yvalue
 *   using overlay with surfaceid = -1 means remove the pattern
 */
public class ShellSurface {
    private static final String TAG = "ShellSurface";
    public static final int S_TYPE_BASE = 0;
    public static final int S_TYPE_ELEMENT = 1;
    public static final int TYPE_RESET = -1;
    public static final int TYPE_BASE = 0;
    public static final int TYPE_OVERLAY = 1;
	public static final int TYPE_MOVE = 2;

    int surfaceId;
    int origW;
    int origH;

    int targetW;
    int targetH;

    String basePath;
    String selfFilename = null;
    String bp2 = null;
    
    Drawable surfaceDrawable = null;
    int surfaceType = 0;

    
    //SurfaceManager mgr = null;
    public ShellSurface() {

    }

    public ShellSurface(String path, int id, List<String> elements) {
	surfaceId = id;
	basePath = path;
	selfFilename = basePath + "surface" + surfaceId + ".png";
	bp2 = String.format("%s%04d%s", basePath+"surface", surfaceId, ".png");
	Log.d(TAG, "bp2:" + bp2);
	//mgr = SurfaceManager.getInstance();
	loadSurface(elements);	
    }

    public ShellSurface(String path, int id) {
	this(path, id, null);
    }

    private BitmapFactory.Options readBitmapInfo(String filename) {
	BitmapFactory.Options opt = new BitmapFactory.Options();
	opt.inJustDecodeBounds = true;
	BitmapFactory.decodeFile(filename, opt);
	return opt;
    }

    private void loadSurface(List<String> elements) {
	if ( elements != null )
	    loadElements(elements);
	// sometimes elementList is 0...
	if ( surfaceType == S_TYPE_ELEMENT && elementList.size() > 0 ) {
	    origW = elementList.get(0).W;
	    origH = elementList.get(0).H;
	    targetW = origW;
	    targetH = origH;
	    return; 
	}

	File self = new File(selfFilename);
	if ( self.exists() == false ) {
	    //Log.d(TAG, "loadSurface error: surface" + surfaceId + " not found");
	    self = new File(bp2);
	    if ( self.exists() == false ) {
		Log.d(TAG, "loadSurface error: surface" + surfaceId + " not found");
		return;
	    }
	    else
		selfFilename = bp2;
	}

	BitmapFactory.Options opt = readBitmapInfo(selfFilename);

	origW = opt.outWidth;
	origH = opt.outHeight;

	if ( origH == -1 || origW == -1 ) {
	    Log.d(TAG, "surface dimension error for surface" + surfaceId);
	    return;
	}
	else {
	    Log.d(TAG, "surface" + surfaceId + " (w,h)=(" + origW + "," + origH + ")");
	}
	targetW = origW; targetH = origH;

    }

    Map<String, Animation> animationTable = null;
    Map<Integer, String> animationTypeTable = null;
    List<AnimationFrame> elementList = null;


    class Animation {
	Animation(){}
	Animation(String id, int interval){this.id = id; this.interval = interval;}
	void addFrame(int index, AnimationFrame f) {
	    if ( frames == null ) frames = new ArrayList<AnimationFrame>();
	    if ( frames.size() <=  index ) {
	    	for(int i = frames.size(); i < index; i++) {
	    		frames.add(i, f);
	    	}
	    }
	    frames.add(index, f);
	    Log.d(TAG, "animation " + id + " has " + frames.size() + "frames");
	}
	int interval;
	String id;
	boolean exclusive;
	List<AnimationFrame> frames;
	AnimationDrawable animation = null;

	AnimationDrawable getAnimation(Resources res){
	    return getAnimation(res, null);
	}

	AnimationDrawable getAnimation(Resources res, SurfaceManager mgr) {
	    if ( animation != null )
		return animation;

	    int frameCount = frames.size();
	    AnimationDrawable anime = new AnimationDrawable();
	    for ( int i = 0; i < frameCount; i++ ) {
		AnimationFrame f = frames.get(i);
		anime.addFrame( f.getDrawable(res, mgr), f.time /* *100 */ );
	    }
	    this.animation = anime;
	    return anime;
	}
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("[Animation] id:" + id + ",interval:" + interval + "\n");
		sb.append("  => dumping frames:\n");
		for ( AnimationFrame f : frames) {
			sb.append(f.toString());
			sb.append("\n");
		}
		return sb.toString();
	}

    }

    class AltAnimation extends Animation {
	AltAnimation(){}
	AltAnimation(String id, String []idz){
	    this.id = id;
	    refidz = idz;
	    refAnimationz = new AnimationDrawable[refidz.length];
	}		
	String [] refidz;
	AnimationDrawable []refAnimationz;

	AnimationDrawable getAnimation(Resources res) {
	    return getAnimation(res, null);
	}

	AnimationDrawable getAnimation(Resources res, SurfaceManager mgr) {
	    int index = (int)(Math.random() * refidz.length);
	    if ( refAnimationz[index] == null )
		refAnimationz[index] = animationTable.get(refidz[index]).getAnimation(res);

	    return refAnimationz[index];
	}
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		sb.append("  => refidz:");
		for ( String i : refidz) {
			sb.append(i + ",");
		}
		sb.append("\n");
		
		return sb.toString();
	}
    }


    class AnimationFrame{
	String sid;

	String filePath;
	int time;
	int frameType;
	int startX;
	int startY;
	int W;
	int H;
	Drawable d;


	Drawable getDrawable(Resources res) {
	    return getDrawable(res, null);
	}

	Drawable getDrawable(Resources res, SurfaceManager mgr) {	
	    if ( d != null )
		return d;

	    if ( frameType == TYPE_OVERLAY ) {
		Drawable []dz = new Drawable[2];
		dz[0] = getSurfaceDrawable(res);
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inPreferredConfig = Bitmap.Config.ARGB_8888;

		if ( mgr != null && sid != null && mgr.containsSurface(sid) ) {
		    dz[1] = mgr.getSurfaceDrawable(sid, res);
		    // need to get the size of the surface...
		    Rect d = mgr.getSurfaceRect(sid, res);
		    if ( d != null ) {
			W = d.width();
			H = d.height();
		    }
		    else {
			W = origW;
			H = origH;
		    }
		}
		else {
		    dz[1] = loadTransparentBitmapFromFile(filePath, res, opt);
		}

		LayerDrawable ld = new LayerDrawable(dz);
		ld.setLayerInset(1, startX, startY, origW - startX - W, origH - startY - H);
		d = ld;
		return d;
		
	    }
	    else if ( frameType == TYPE_RESET ) {
		return getSurfaceDrawable(res);
	    }
	    else if ( frameType == TYPE_BASE ) {
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
		d = loadTransparentBitmapFromFile(filePath, res, opt);
		return d;
	    }
	    else if ( frameType == TYPE_MOVE ) {
	    	// move type not supported, return base surface for the time being...
	    	d = getSurfaceDrawable(res);
	    }

	    return d;
	}

	@Override
	public String toString() {
		return "[AnimationFrame] sid=" + sid + ",FType="+frameType+ ",[W,H]=["+W+","+H+"],(startX,startY)=(" +startX+","+startY+")";
	}

    }

    class CollisionArea {
	CollisionArea(int id, int sx, int sy, int ex, int ey, String name) {
	    startX = sx;
	    startY = sy;
	    W = ex - sx;
	    H = ey - sy;
	    this.id = id;
	    this.name = name;
	    rect = new Rect(sx, sy, ex, ey);
	}
	int id;
	String name;
	int startX;
	int startY;
	int W;
	int H;
	Rect rect;
    }

    Map<Integer, CollisionArea> collisionAreas = null;
    private void prepareCollisionAreas() {
	if ( collisionAreas == null )
	    collisionAreas = new Hashtable<Integer, CollisionArea>();
    }

    private CollisionArea findIdInCollision(int id) {
	prepareCollisionAreas();

	if ( collisionAreas.containsKey(id) )
	    return collisionAreas.get(id);
	
	return null;
    }

    public int getCollisionCount() {
	prepareCollisionAreas();
	return collisionAreas.size();
    }

    private void prepareAnimationTable() {
	if ( animationTable == null )
	    animationTable = new Hashtable<String, Animation>();
	if ( animationTypeTable == null )
	    animationTypeTable = new Hashtable<Integer, String>();
    }

    private void loadElements(List<String> elements) {
	Log.d(TAG, "loading " + elements.size() + " elements");

	for ( String s : elements ) {
	    // need to check if its collusion
	    Matcher m = PatternHolders.collision.matcher(s);
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches collision");
		//printMatch(m);
		handleCollision(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6));
		continue;
	    }

	    m = PatternHolders.element.matcher(s);
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches element");
		//printMatch(m);
		// need to store this as the new self?
		this.surfaceType = S_TYPE_ELEMENT;
		handleElement(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
		continue;
	    }

	    // or point
	    m = PatternHolders.point.matcher(s);
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches point");
		//printMatch(m);
		continue;
	    }

	    // or interval
	    m = PatternHolders.interval.matcher( s );
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches interval");
		//printMatch(m);
		handleInterval(m.group(1), m.group(2));
		continue;
	    }
	    m = PatternHolders.animation_interval.matcher(s);
	    if ( m.matches() ) {
		handleInterval(m.group(1), m.group(2));
		continue;
	    }

	    // or pattern
	    m = PatternHolders.pattern.matcher( s );
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches pattern");
		//printMatch(m);
		handlePattern(m.group(1), m.group(2),m.group(3), m.group(4), m.group(5), m.group(6), m.group(7) );
		continue;
	    }

	    m = PatternHolders.animation.matcher(s);
	    if ( m.matches() ) {
		if ( m.group(4) == null ){
		    //printMatch(m);
		    handlePattern(m.group(1), m.group(2), m.group(6), m.group(7), m.group(5), m.group(8), m.group(9) );
		}
		else {
		    Log.d(TAG, "have altstart case, need to do something");
		    // alt start has the seq in m.group(4) in form of 0,1,2,...,N
		    //printMatch(m);
		    addAltAnimation(m.group(1), m.group(4));
		}
		continue;
	    }
	    m = PatternHolders.animation_base.matcher(s);
	    if ( m.matches() ) {
		//printMatch(m);
		String filename = (m.group(6)==null)?m.group(4):m.group(6);
		handlePattern(m.group(1), m.group(2), filename, m.group(7), m.group(3), null, null);
		continue;
	    }

	    m = PatternHolders.pattern_base.matcher(s);
	    if ( m.matches() ) {
		printMatch(m);
		handlePattern(m.group(1), m.group(2),m.group(3), m.group(4), m.group(5), null, null );
		continue;
	    }
	    // or option
	    m = PatternHolders.option.matcher( s );
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches option");
		//printMatch(m);
		handleOptions(m.group());
		continue;
	    }

	    // skip comments
	    m = PatternHolders.comment_ptrn.matcher( s );
	    if( m.matches() ) {
		continue;
	    }

	    Log.d(TAG, s + " matched nothing.");
	    try {
		AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse no_match", s, -1);
	    }
	    catch(Exception e) {
		// too many errors can cause this line to crash...@_@
	    }
	}
    }

    private void handleOptions(String s) {
	try {
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse not support", s, -1);
	}
	catch(Exception e) {

	}
    }

    private void prepareElementList() {
	if ( elementList == null )
	    elementList = new ArrayList<AnimationFrame>(2);
    }

    private void handleElement(String id, String method, String filename, String startX, String startY){
	prepareElementList();

	int index = 0;
	int x = 0;
	int y = 0;
	try {
	    index = Integer.parseInt(id);
	    x = Integer.parseInt(startX);
	    y = Integer.parseInt(startY);
	}
	catch(Exception e) {
	    Log.d(TAG, "invalid input, aborting, will cause system to crash later");
	    return;
	}

	/* filename is actual file name on file system, not surface id? */	
	String fz = basePath + filename;
	File file = new File(fz);
	if ( file.exists() ) {
	    
	    BitmapFactory.Options opt = readBitmapInfo(fz);
	    
	    AnimationFrame f = new AnimationFrame();
	    f.filePath = fz;
	    f.frameType = TYPE_BASE; // always force base
	    f.startX = x;
	    f.startY = y;
	    f.W = opt.outWidth;
	    f.H = opt.outHeight;
	    

	    elementList.add(index, f);
	}

    }


    private void handleCollision(String id, String startX, String startY, String endX, String endY, String name) {
	prepareCollisionAreas();
	try {
	    int cid = Integer.parseInt(id);
	    int sx = Integer.parseInt(startX);
	    int sy = Integer.parseInt(startY);
	    int ex = Integer.parseInt(endX);
	    int ey = Integer.parseInt(endY);

	    CollisionArea ca = findIdInCollision(cid);
	    if ( ca == null ) {
		ca = new CollisionArea(cid, sx, sy, ex, ey, name);
		collisionAreas.put(cid, ca);
	    }
	    else {
		ca.startX = sx;
		ca.startY = sy;
		ca.W = ex - sx;
		ca.H = ey - sy;
		ca.name = name;
	    }
	}
	catch(Exception e) {

	}
    }

    public static final int A_TYPE_SOMETIMES = 0;
    public static final int A_TYPE_RARELY = 1;
    public static final int A_TYPE_TALK = 2;
    public static final int A_TYPE_RUNONCE = 3;
    public static final int A_TYPE_YEN_E = 4;
    public static final int A_TYPE_BIND = 5;
    public static final int A_TYPE_RANDOM = 6;
    public static final int A_TYPE_LOOP = 7;
    public static final int A_TYPE_NEVER = 8;
    public static final int A_TYPE_LAST = A_TYPE_NEVER + 1;

    private int lookupInterval(String in) {
	if ( in.equalsIgnoreCase("sometimes") )
	    return A_TYPE_SOMETIMES;
	else if ( in.equalsIgnoreCase("rarely"))
	    return A_TYPE_RARELY;
	else if ( in.equalsIgnoreCase("random") )
	    return A_TYPE_RANDOM;
	else if ( in.equalsIgnoreCase("always") )
	    return A_TYPE_LOOP;
	else if ( in.equalsIgnoreCase("yen-e") )
	    return A_TYPE_YEN_E;
	else if ( in.equalsIgnoreCase("talk") )
	    return A_TYPE_TALK;
	else if ( in.equalsIgnoreCase("runonce") )
	    return A_TYPE_RUNONCE;
	else if ( in.equalsIgnoreCase("never") )
	    return A_TYPE_NEVER;
	else if ( in.equalsIgnoreCase("bind") )
	    return A_TYPE_BIND;
	// sometimes
	// rarely
	// random, X
	// always
	// runonce
	// never
	// yen-e
	// talk, X
	// bind
	return 0;
    }
    
    private void handleInterval(String id, String interval) {
	prepareAnimationTable();

	if ( animationTable.containsKey(id) ) {
	    animationTable.get(id).interval = lookupInterval(interval);
	}
	else {
	    int val = lookupInterval(interval);
	    animationTable.put(id, new Animation(id, val));//lookupInterval(interval)));
	    if (animationTypeTable.containsKey(val) ) {
		String exist = animationTypeTable.get(val);
		animationTypeTable.put(val, exist + ","  + id);
	    }
	    else
		animationTypeTable.put(val, id);
	}
    }

    private int lookupPatternType(String type) {
	if ( type.equalsIgnoreCase("base") ){
	    return TYPE_BASE;
	}
	else if ( type.equalsIgnoreCase("overlay") || type.equalsIgnoreCase("overlayfast")) {
	    return TYPE_OVERLAY;
	}
	else if ( type.equalsIgnoreCase("move")) {
		return TYPE_MOVE;
	}
	// base
	// overlay
	// overlayfast
	// move
	// bind
	// -add
	// -reduce
	// -insert
	// start,[pattern Id]
	// alternativestart, [patternid1, id2,...,idN]
	// 
	return TYPE_BASE;
    }

    private void addAltAnimation(String aId, String refidz) {
	// refidz should be in the form of 1,2,3,4,5,...,N
	String [] ridz = refidz.split(",");
	if ( ridz == null )
	    return;
	prepareAnimationTable();
	AltAnimation a = new AltAnimation(aId, ridz);
	if ( animationTable.containsKey(aId) )
	    a.interval = animationTable.get(aId).interval;
	animationTable.put(aId, a);
    }

    private void addFrameToAnimation(String aId, int index, AnimationFrame frame) {
	prepareAnimationTable();

	Animation a = null;
	if ( animationTable.containsKey(aId) ) {
	    a = animationTable.get(aId);
	}
	else {
	    a = new Animation();
	    a.id = aId;
	    animationTable.put(aId, a);
	}
	
	a.addFrame(index, frame);
    }


    private void handlePattern(String animationId, String seq, String filename, String waitTime,
			       String pattern, String x_in, String y_in) {
	// if filename is "-1", this means end of animation
	int index = -1;
	int x = -1;
	int y = -1;
	int wait = -1;
	int findex = -1;
	try {
	    index = Integer.parseInt(seq);
	    wait = Integer.parseInt(waitTime);
	    x = Integer.parseInt(x_in);
	    y = Integer.parseInt(y_in);
	    findex = Integer.parseInt(filename);
	}
	catch(Exception e ) {
		e.printStackTrace();
	}

	if ( filename.equalsIgnoreCase("-1") ) {
	    AnimationFrame f = new AnimationFrame();
	    f.frameType = TYPE_RESET;
	    f.time = wait;
	    addFrameToAnimation(animationId, index, f);
	}
	else {
	    // first check file existance
	    String fz = basePath + "surface" + filename + ".png";
	    File file = new File(fz);
	    if ( file.exists() ) {

		BitmapFactory.Options opt = readBitmapInfo(fz);

		AnimationFrame f = new AnimationFrame();
		f.sid = "" + findex; // filename should really be called surface id...
		f.filePath = fz;
		f.frameType = lookupPatternType(pattern);
		f.time = wait;
		f.startX = x;
		f.startY = y;
		f.W = opt.outWidth;
		f.H = opt.outHeight;

		addFrameToAnimation(animationId, index, f);
	    }
	    else {
		AnimationFrame f = new AnimationFrame();
		f.sid = "" + findex ;//filename;
		f.startX = x;
		f.startY = y;
		f.frameType = lookupPatternType(pattern);
		addFrameToAnimation(animationId, index, f);
	    }
	}
    }

    private void handleOption(String id, String option) {
	prepareAnimationTable();
	boolean exclusive = option.equalsIgnoreCase("exclusive");
	if ( animationTable.containsKey(id) ) {
	    animationTable.get(id).exclusive = exclusive;
	}
	else {
	    Animation a = new Animation();
	    a.exclusive = exclusive;
	    a.id = id;
	    animationTable.put(id, a);
	}
	
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

    // resizes surface to size x, y?
    public void resize(int height, int width) {
	targetW = width;
	targetH = height;
    }

    // return normal surface drawable?
    public Drawable getSurfaceDrawable(Resources res) {
	if ( surfaceDrawable != null )
	    return surfaceDrawable;

	// read "surface" + id.png
	if ( surfaceType == S_TYPE_BASE ) {
	    BitmapFactory.Options opt = new BitmapFactory.Options();
	    if ( origH != targetH )
		opt.outHeight = targetH;
	    if ( origW != targetW )
		opt.outWidth = targetW;
	    surfaceDrawable = loadTransparentBitmapFromFile(selfFilename, res, opt);

	    return surfaceDrawable;
	}
	else {
	    // type is S_TYPE_ELEMENT
	    // need to compose a layered drawable
	    // first, get the number of elements 
	    int layerCount = elementList.size();
	    Drawable[] dz = new Drawable[layerCount];
	    for ( int i = 0; i < layerCount; i++ ){
		dz[i] = elementList.get(i).getDrawable(res);
	    }
	    LayerDrawable ld = new LayerDrawable(dz);
	    int oW = elementList.get(0).W;
	    int oH = elementList.get(0).H;
	    for ( int j = 1; j < layerCount; j++ ) {
		int lX = elementList.get(j).startX;
		int lY = elementList.get(j).startY;
		int lW = elementList.get(j).W;
		int lH = elementList.get(j).H;
		ld.setLayerInset(j, lX, lY, oW - lX - lW, oH - lY - lH);
	    }
	    surfaceDrawable = ld;
	    
	    return surfaceDrawable;
	}
    }

    private Drawable loadTransparentBitmapFromFile(String filename, Resources res, BitmapFactory.Options opt) {
	Log.d(TAG, "loading " + filename);
	Bitmap bmp = BitmapFactory.decodeFile(filename, opt);
// 	Log.d(TAG, " -> bitmap config:" + bmp.getConfig());

	int colorVal = bmp.getPixel(0,0);
	// use pixel value at upper left as transparent color
	return new BitmapDrawable(res, createTransparentBmp(bmp, colorVal));
    }

    // from stack overflow 
    // http://stackoverflow.com/questions/8264181/replace-specific-color-in-bitmap-with-transparency
    // this only work with ARGB8888 format!
    // it will skip on 16 bit format @_@
    private Bitmap createTransparentBmp(Bitmap bitmap, int replaceThisColor) {
	if ( bitmap != null ) {
// 	    Log.d(TAG, " got bmp of config:" + bitmap.getConfig());
	    int picw = bitmap.getWidth(); 
	    int pich = bitmap.getHeight();
// 	    int rowbyte = bitmap.getRowBytes();
// 	    Log.d(TAG, " bmp [w, h, rowbyte]=[" + picw + "," + pich + "," + rowbyte + "]");
	    int[] pix = new int[picw * pich];
	    bitmap.getPixels(pix, 0, picw, 0, 0, picw, pich);
// 	    Log.d(TAG, "replacing " + replaceThisColor + " with transparency");
	    for (int y = 0; y < pich; y++) {   
		int startY = y * picw;
		for (int x = 0; x < picw; x++) {
		    int index = startY + x;

		    if (pix[index] == replaceThisColor) {
			pix[index] = Color.TRANSPARENT;  
// 			if ( y == 0 )
// 			    Log.d(TAG, "replacing (" + x + "," + y + ") with transparency");
		    } else {
			//break;
// 			if ( y == 0 )
// 			    Log.d(TAG, "colorval @(" + x + "," + y + ") is " + pix[index]);
		    }
		}
	    }

	    Bitmap bm = Bitmap.createBitmap(pix, picw, pich,
					    Bitmap.Config.ARGB_8888);  

	    return bm;

	}
	else
	    return null;
    }

    public int getFirstAnimationIndex() {
	if ( animationTable == null || animationTable.size() == 0 )
	    return -1;
	TreeSet<String> key = new TreeSet<String>( animationTable.keySet() );
	int first = 0;
	try {
	    first = Integer.parseInt( key.first() );
	}
	catch(Exception e) {

	}
	return first;
    }

    public Drawable getAnimation(String id, Resources res) {
	return getAnimation(id, res, null);
    }

    public Drawable getAnimation(String id, Resources res, SurfaceManager mgr) {
	if ( animationTable!= null && animationTable.containsKey(id) )
	    return animationTable.get(id).getAnimation(res, mgr);
	else 
	    return null;
    }

    public Drawable getAnimation(int patternId, Resources res) {
	if ( animationTable.size() < patternId && animationTable.containsKey("" + patternId) == false)
	    return null;

	String pid = "" + patternId;
	return getAnimation(pid, res);
    }

    private AnimationFrame getAnimationFrame(String patternId, int frameIndex) {
	return animationTable.get(patternId).frames.get(frameIndex);
    }

    public int getAnimationCount() {
	prepareAnimationTable();
	return animationTable.size();
    }

    public int getAnimationFrequency(int patternId) {
	// sometimes = 50% per 1 second
	// rarely = 25% per 1 second
	// random, X
	// always = always looping
	// runonce = only shown once
	// talk, X = ?
	if ( animationTable.size() < patternId )
	    return -1;

	
	return animationTable.get(""+patternId).interval;
    }

    public int getAnimationFrameCount(int patternId) {
	if ( animationTable.size() < patternId )
	    return 0;

	return animationTable.get(""+patternId).frames.size();
    }

    public String getAnimationIdByType(int type){
	// go through animation table, get all animation of type
	// randomly return one of them...
	
	if (animationTypeTable!=null && animationTypeTable.containsKey(type) ) {
	    String idstring = animationTypeTable.get(type);
	    String[] idz = idstring.split(",");
	    if ( idz == null || idz.length == 1 )
		return idstring;
	    else {
		return idz[ (int)(Math.random() * idz.length) ];

	    }
	}
	return null;
    }

    Rect dim = null;
    public Rect getSurfaceDim(){
	if ( dim == null )
	    dim = new Rect(0, 0, origW, origH);

	return dim;
    }

    public String dumpSurfaceStat() {
    	StringBuilder sb = new StringBuilder();    	
   		sb.append("==================\n");

   		sb = dumpElementList(sb);
   		sb = dumpStat(sb);
    	sb = dumpAnimation(sb);

   		sb.append("==================\n");
    	return sb.toString();
    }

    public StringBuilder dumpAnimation(StringBuilder sb) {
    	if ( animationTable != null ) {
    	
    	sb.append("[dumping animation]\n");
    	for(String k : animationTable.keySet()) {
    		sb.append( animationTable.get(k).toString());
    	}
    	}
    	
    	if ( animationTypeTable != null ) {
    	sb.append("[dumping animation type table]\n");
    	for ( Integer i : animationTypeTable.keySet()) {
    		sb.append(animationTypeTable.get(i));
    		sb.append("\n");
    	}
    	sb.append("[done dumping animation type table]\n");
    	}
    	return sb;
    }

    public StringBuilder dumpStat(StringBuilder sb) {
    	sb.append("[dumping surface status]");
    	sb.append("surface id:" + surfaceId + "\n");
    	sb.append("surface type:" + surfaceType + "\n");
    	sb.append("surface filename:" + selfFilename + "\n");
    	sb.append("[origW,origH]:[" + origW + "," + origH + "]\n");
    	if ( animationTable != null )
    	sb.append("animation count:" + animationTable.size() + "\n");
    	return sb;
    }

    
    // debug dumping functions...
    public StringBuilder dumpElementList(StringBuilder sb) {    	
    	if ( elementList != null && elementList.size() > 0) {

    	sb.append("[dumping element list]\n");
    	for(AnimationFrame e : elementList){
    		sb.append(e.toString());
    		sb.append("\n");
    	}
    	sb.append("[end of element list]\n");
    	}
    	return sb;
    }

    
}
