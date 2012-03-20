package com.cattailsw.nanidroid;

import android.graphics.drawable.Drawable;
import java.io.File;
import android.graphics.drawable.LayerDrawable;
import java.util.List;
import android.util.Log;
import android.graphics.BitmapFactory;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import java.util.regex.Pattern;
import android.graphics.Color;
import java.util.regex.Matcher;
import java.util.Hashtable;
import java.util.Map;
import java.util.ArrayList;
import android.graphics.drawable.AnimationDrawable;
import java.nio.ByteBuffer;

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

    int surfaceId;
    int origW;
    int origH;

    int targetW;
    int targetH;

    String basePath;
    String selfFilename = null;
    String bp2 = null;
    
    Drawable surfaceDrawable = null;

    public ShellSurface() {

    }

    public ShellSurface(String path, int id, List<String> elements) {
	surfaceId = id;
	basePath = path;
	selfFilename = basePath + "/surface" + surfaceId + ".png";
	bp2 = String.format("%s%04d%s", basePath+"/surface", surfaceId, ".png");
	Log.d(TAG, "bp2:" + bp2);
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

	File self = new File(selfFilename);
	if ( self.exists() == false ) {
	    Log.d(TAG, "loadSurface error: surface" + surfaceId + " not found");
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

    class Animation {
	Animation(){}
	Animation(String id, int interval){this.id = id; this.interval = interval;}
	void addFrame(int index, AnimationFrame f) {
	    if ( frames == null ) frames = new ArrayList<AnimationFrame>();
	    frames.add(index, f);
	    Log.d(TAG, "animation " + id + " has " + frames.size() + "frames");
	}
	int interval;
	String id;
	boolean exclusive;
	List<AnimationFrame> frames;
	AnimationDrawable animation = null;
    }


    public static final int TYPE_RESET = -1;
    public static final int TYPE_BASE = 0;
    public static final int TYPE_OVERLAY = 1;

    class AnimationFrame{
	String filePath;
	int time;
	int frameType;
	int startX;
	int startY;
	int W;
	int H;
	Drawable d;

	Drawable getDrawable(Resources res) {
	    if ( d != null )
		return d;

	    if ( frameType == TYPE_OVERLAY ) {
		Drawable []dz = new Drawable[2];
		dz[0] = getSurfaceDrawable(res);
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inPreferredConfig = Bitmap.Config.ARGB_8888;

		dz[1] = loadTransparentBitmapFromFile(filePath, res, opt);
		LayerDrawable ld = new LayerDrawable(dz);
		ld.setLayerInset(1, startX, startY, origW - startX - W, origH - startY - H);
		d = ld;
		return d;
	    }

	    if ( frameType == TYPE_RESET )
		return getSurfaceDrawable(res);

	    if ( frameType == TYPE_BASE ) {
		BitmapFactory.Options opt = new BitmapFactory.Options();
		opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
		d = loadTransparentBitmapFromFile(filePath, res, opt);
		return d;
	    }

	    return d;
	}

    }

    private void prepareAnimationTable() {
	if ( animationTable == null )
	    animationTable = new Hashtable<String, Animation>();
    }

    private void loadElements(List<String> elements) {
	Log.d(TAG, "loading " + elements.size() + " elements");
	// need to go through elements line by line to set correct settings?
	// collusion
	Pattern element = Pattern.compile("element(\\d+),(\\w*),(\\S*),(\\d+),(\\d+)$");

	Pattern collision = Pattern.compile("collision(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\w*)$");

	// kero, sakura

	// point
	Pattern point = Pattern.compile("point.center([xXyY]{1}),(\\d+$)");

	// things started with number...
	// that includes
	// *interval,xxx
	// *pattern*,x,x,x,x,x
	// *option,xxx
	// 
	Pattern interval = Pattern.compile("(\\d+)interval,(\\S*)$");
	Pattern pattern = Pattern.compile("(\\d+)pattern(\\d?),(\\d*|-1),(\\d*),(\\w*),(\\d*),(\\d*)$");
	Pattern option = Pattern.compile("(\\d+)[oO]ption,(\\S*)$");
	for ( String s : elements ) {
	    // need to check if its collusion
	    Matcher m = collision.matcher(s);
	    if ( m.matches() ) {
		Log.d(TAG, "string " + s + " matches collision");
		printMatch(m);
		continue;
	    }

	    m = element.matcher(s);
	    if ( m.matches() ) {
		Log.d(TAG, "string " + s + " matches element");
		printMatch(m);
		// need to store this as the new self?

		continue;
	    }

	    // or point
	    m = point.matcher(s);
	    if ( m.matches() ) {
		Log.d(TAG, "string " + s + " matches point");
		printMatch(m);
		continue;
	    }

	    // or interval
	    m = interval.matcher( s );
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches interval");
		//printMatch(m);
		handleInterval(m.group(1), m.group(2));
		continue;
	    }
	    // or pattern
	    m = pattern.matcher( s );
	    if ( m.matches() ) {
		//Log.d(TAG, "string " + s + " matches pattern");
		//printMatch(m);
		handlePattern(m.group(1), m.group(2),m.group(3), m.group(4), m.group(5), m.group(6), m.group(7) );
		continue;
	    }
	    // or option
	    m = option.matcher( s );
	    if ( m.matches() ) {
		Log.d(TAG, "string " + s + " matches option");
		printMatch(m);
		continue;
	    }

	    Log.d(TAG, s + " matched nothing.");
	}
    }

    private int lookupInterval(String in) {
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
	    animationTable.put(id, new Animation(id, lookupInterval(interval)));
	}
    }

    private int lookupPatternType(String type) {
	if ( type.equalsIgnoreCase("base") ){
	    return TYPE_BASE;
	}
	else if ( type.equalsIgnoreCase("overlay")) {
	    return TYPE_OVERLAY;
	}
	// base
	// overlay
	// overlayfast
	// move
	// bind
	// add
	// reduce
	// insert
	// start,[pattern Id]
	// alternativestart, [patternid1, id2,...,idN]
	// 
	return TYPE_BASE;
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
	try {
	    index = Integer.parseInt(seq);
	    x = Integer.parseInt(x_in);
	    y = Integer.parseInt(y_in);
	    wait = Integer.parseInt(waitTime);
	}
	catch(Exception e ) {

	}

	if ( filename.equalsIgnoreCase("-1") ) {
	    AnimationFrame f = new AnimationFrame();
	    f.frameType = TYPE_RESET;
	    f.time = wait;
	    addFrameToAnimation(animationId, index, f);
	}
	else {
	    // first check file existance
	    String fz = basePath + "/surface" + filename + ".png";
	    File file = new File(fz);
	    if ( file.exists() ) {

		BitmapFactory.Options opt = readBitmapInfo(fz);

		AnimationFrame f = new AnimationFrame();
		f.filePath = fz;
		f.frameType = lookupPatternType(pattern);
		f.time = wait;
		f.startX = x;
		f.startY = y;
		f.W = opt.outWidth;
		f.H = opt.outHeight;

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
	BitmapFactory.Options opt = new BitmapFactory.Options();
	if ( origH != targetH )
	    opt.outHeight = targetH;
	if ( origW != targetW )
	    opt.outWidth = targetW;
	surfaceDrawable = loadTransparentBitmapFromFile(selfFilename, res, opt);

	return surfaceDrawable;
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

    public Drawable getAnimation(int patternId, Resources res) {
	if ( animationTable.size() < patternId )
	    return null;

	String pid = "" + patternId;
	if ( animationTable.get(pid).animation != null )
	    return animationTable.get(pid).animation;
	
	// need to assemble the animation...
	int frameCount = getAnimationFrameCount(patternId);
	AnimationDrawable anime = new AnimationDrawable();
	for ( int i = 0; i < frameCount; i++ ) {
	    AnimationFrame f = getAnimationFrame(pid, i);
	    anime.addFrame( f.getDrawable(res), f.time /* *100 */ );
	}
	animationTable.get(pid).animation = anime;

	return anime;
    }

    private AnimationFrame getAnimationFrame(String patternId, int frameIndex) {
	return animationTable.get(patternId).frames.get(frameIndex);
    }

    public int getPatternCount() {
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
    
    

}
