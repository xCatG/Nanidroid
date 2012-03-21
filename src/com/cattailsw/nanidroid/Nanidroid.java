package com.cattailsw.nanidroid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.os.Environment;
import java.io.File;
import android.widget.TextView;
import android.os.SystemClock;
import android.graphics.drawable.AnimationDrawable;
import android.view.View;
import java.util.Set;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Color;

public class Nanidroid extends Activity
{
    private ImageView iv = null;
    private TextView tv = null;
    AnimationDrawable anime = null;
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	iv = (ImageView) findViewById(R.id.sakura_display);
	tv = (TextView) findViewById(R.id.tv);

	// need to get a list of ghosts on sd card
	if ( Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED) == false ) {
	    tv.setText("sd card error");
	    return;
	    // need to prompt SD card issue
	}

	// use /sdcard/Android/data/com.cattailsw.nanidroid/ghost/yohko for the time being
	String ghost_path = Environment.getExternalStorageDirectory().getPath() + 
	    //"/Android/data/com.cattailsw.nanidroid/ghost/2elf";
	    "/Android/data/com.cattailsw.nanidroid/ghost/yohko";

	// read the ghost shell
	//
	String master_shell_path = ghost_path + "/shell/master";

	// read master data
	String master_ghost_path = ghost_path + "/ghost/master";

	String shell_desc_path = master_shell_path + "/descript.txt";
	String shell_surface_path = master_shell_path + "/surfaces.txt";

	File shell_desc = new File(shell_desc_path);
	if ( shell_desc.exists() == false ) {
	    tv.setText("shell desc error.");
	    return;
	}
	long starttime = SystemClock.uptimeMillis();
	DescReader shellDescReader = new DescReader(shell_desc);
	//DescReader shellSurfaceReader = 
	File shellSurface = new File(shell_surface_path);
	sr = new SurfaceReader(shellSurface);
	long dur = SystemClock.uptimeMillis() - starttime;

	tv.setText("shell desc size=" + shellDescReader.table.size() + ", ghost name=" + shellDescReader.table.get("name") 
		   + "\nshell surface count=" + sr.table.size() + ",parsing time:" + (float)dur/1000.0f + "s"  );

	int keycount = sr.table.keySet().size();
	surfaceKeys = new String[keycount];
	surfaceKeys = sr.table.keySet().toArray(surfaceKeys);
	currentSurfaceKey = surfaceKeys[0];
	currentSurface = sr.table.get(currentSurfaceKey);
	iv.setImageDrawable( currentSurface.getSurfaceDrawable(getResources()) );
	/*	anime = (AnimationDrawable) sr.table.get("2").getAnimation(0, getResources());	
	anime.setVisible(true, true);
	anime.setOneShot(false);
	iv.setImageDrawable( anime );
	*/
	//	anime.start();
    }
    SurfaceReader sr = null;
    String[] surfaceKeys = null;
    int keyindex = 0;
    //Set<String> surfaceKeys = null;
    String currentSurfaceKey = null;
    ShellSurface currentSurface = null;

    public void onNextSurface(View v){
	if ( keyindex < surfaceKeys.length - 1 )
	    keyindex++;
	else
	    keyindex = 0;

	currentSurfaceKey = surfaceKeys[keyindex];
	currentSurface = sr.table.get(currentSurfaceKey);
	iv.setImageDrawable( currentSurface.getSurfaceDrawable(getResources()) );
	tv.setText("current drawable key: " + currentSurfaceKey + 
		   ", animation count: " + currentSurface.getAnimationCount() +
		   ", collision count: " + currentSurface.getCollisionCount()
		   );

	if ( currentSurface.getAnimationCount() == 0 )
	    findViewById(R.id.btn2).setEnabled(false);
	else {
	    animeIndex = 0;
	    anime = (AnimationDrawable) currentSurface.getAnimation(animeIndex, getResources());
	    anime.setVisible(true, true);
	    iv.setImageDrawable(anime);
	    findViewById(R.id.btn2).setEnabled(true);
	}
    }
    int animeIndex = 0;

    public void onAnimate(View v) {
	//iv.setImageDrawable(anime);
	anime.start();


    }

    private void pickNextAnimation() {
	if ( currentSurface.getAnimationCount() > 1 ) {
	    animeIndex++;
	    if ( animeIndex >= currentSurface.getAnimationCount() )
		animeIndex = 0;

	    anime = (AnimationDrawable) currentSurface.getAnimation(animeIndex, getResources());
	    anime.setVisible(true, true);
	    iv.setImageDrawable(anime);
	}
    }

    public void onShowCollision(View v){
	showCollisionAreaOnImageView();
    }


    public void ivClick(View v) {
	//anime.start();
	//showCollisionAreaOnImageView();
	pickNextAnimation();
    }

    private void showCollisionAreaOnImageView() {
	ShellSurface surface = sr.table.get(currentSurfaceKey);
	int colsize = surface.getCollisionCount();
	if ( colsize == 0 ) return;
	Rect[] rz = new Rect[colsize];
	Set<Integer> colKey = surface.collisionAreas.keySet();
	int i = 0;
	for ( Integer k : colKey ) {
	    rz[i] = surface.collisionAreas.get(k).rect;
	    i++;
	}

	BitmapDrawable b = (BitmapDrawable) surface.getSurfaceDrawable(getResources());
	Bitmap bmpcopy = b.getBitmap().copy(Bitmap.Config.ARGB_8888, true);

	Canvas c = new Canvas(bmpcopy);
	Paint p = new Paint();
	p.setAntiAlias(true);
	p.setStrokeWidth(1);
	p.setStyle(Style.STROKE);
	p.setColor(Color.rgb(254, 0, 1));

	for ( Rect re : rz )
	    c.drawRect(re, p);


	BitmapDrawable nb = new BitmapDrawable( bmpcopy );

	iv.setImageDrawable( nb );
    }

}
