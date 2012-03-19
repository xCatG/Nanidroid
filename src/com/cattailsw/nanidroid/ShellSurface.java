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
    
    Drawable surfaceDrawable = null;

    public ShellSurface() {

    }

    public ShellSurface(String path, int id, List<String> elements) {
	surfaceId = id;
	basePath = path + "/surface" + surfaceId + ".png";
	loadSurface(elements);	
    }

    public ShellSurface(String path, int id) {
	this(path, id, null);
    }

    private void loadSurface(List<String> elements) {
	File self = new File(basePath);
	if ( self.exists() == false ) {
	    Log.d(TAG, "loadSurface error: surface" + surfaceId + " not found");
	    return;
	}

	BitmapFactory.Options opt = new BitmapFactory.Options();
	opt.inJustDecodeBounds = true;

	// need to get width, height?
	BitmapFactory.decodeFile(basePath, opt);
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

	if ( elements == null ) {
	    // no eleemnt, just load the base surface
	    return;
	}
	else 
	    loadElements(elements);

    }

    private void loadElements(List<String> element) {
	// need to go through elements line by line to set correct settings?
	// collusion

	// kero

	// things started with number...
	//Pattern interval = new 
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
	
	Bitmap bmp = BitmapFactory.decodeFile(basePath, opt);
	// use pixel value at upper left as transparent color
	surfaceDrawable = new BitmapDrawable(res, createTransparentBmp(bmp, bmp.getPixel(0,0)));

	return surfaceDrawable;
    }

    // from stack overflow 
    // http://stackoverflow.com/questions/8264181/replace-specific-color-in-bitmap-with-transparency
    private Bitmap createTransparentBmp(Bitmap bitmap, int replaceThisColor) {
	if ( bitmap != null ) {
          int picw = bitmap.getWidth(); 
          int pich = bitmap.getHeight();
          int[] pix = new int[picw * pich];
          bitmap.getPixels(pix, 0, picw, 0, 0, picw, pich);

          int sr = (replaceThisColor >> 16) & 0xff;
          int sg = (replaceThisColor >> 8) & 0xff;
          int sb = replaceThisColor & 0xff;

          for (int y = 0; y < pich; y++) {   
            for (int x = 0; x < picw; x++) {
              int index = y * picw + x;
            /*  int r = (pix[index] >> 16) & 0xff;
              int g = (pix[index] >> 8) & 0xff;
              int b = pix[index] & 0xff;*/

              if (pix[index] == replaceThisColor) {

		  /*  if(x<topLeftHole.x) topLeftHole.x = x;  
                if(y<topLeftHole.y) topLeftHole.y = y;
                if(x>bottomRightHole.x) bottomRightHole.x = x;
                if(y>bottomRightHole.y)bottomRightHole.y = y;
		  */
                pix[index] = Color.TRANSPARENT;   
              } else {
                //break;
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

    // return drawable for AnimationDrawable
    public Drawable getAnimationFrame(int patternId, int frame) {
	// need to actually assemble the Drawable here
	LayerDrawable ret = null;
	return null;
    }

    public int getPatternCount() {
	return 0;
    }

    public int getAnimationFrequency(int patternId) {
	// sometimes = 50% per 1 second
	// rarely = 25% per 1 second
	// random, X
	// always = always looping
	// runonce = only shown once
	// talk, X = ?
	return 0;
    }

    public int getAnimationFrameCount(int patternId) {
	return 0;
    }
    
    

}
