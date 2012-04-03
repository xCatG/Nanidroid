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
import java.util.Collections;
import java.util.Arrays;
import android.util.Log;

import com.android.debug.hv.ViewServer;
import com.cattailsw.nanidroid.util.NarUtil;

import android.widget.FrameLayout;
import android.view.Gravity;
import android.content.Intent;
import java.util.List;
import android.net.Uri;
import android.widget.FrameLayout.LayoutParams;
import android.content.Intent;

public class Nanidroid extends Activity
{
    private static final String TAG = "Nanidroid";
    //private ImageView sv = null;
    private SakuraView sv = null;
    private KeroView kv = null;
    private Balloon bSakura = null;
    private Balloon bKero = null;
    private FrameLayout fl = null;

    AnimationDrawable anime = null;
    //SurfaceManager mgr = null;
    LayoutManager lm = null;
    SScriptRunner runner = null;

    GhostMgr gm = null;
    List<InfoOnlyGhost> iglist = null;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	sv = (SakuraView) findViewById(R.id.sakura_display);
	kv = (KeroView) findViewById(R.id.kero_display);
	bSakura = (Balloon) findViewById(R.id.bSakura);
	bKero = (Balloon) findViewById(R.id.bKero);
	fl = (FrameLayout) findViewById(R.id.fl);

	//mgr = SurfaceManager.getInstance();
	lm = LayoutManager.getInstance(this);
	runner = SScriptRunner.getInstance(this);
	gm = new GhostMgr(this);
	Ghost g = gm.createGhost("first");
	runner.setGhost(g);
	gm.setLastRunGhost(g);

	runner.setViews(sv, kv, bSakura, bKero);
	sv.setMgr(g.mgr);
	kv.setMgr(g.mgr);
	lm.setViews(fl, sv, kv, bSakura, bKero);
	runner.setLayoutMgr(lm);
	// need to get a list of ghosts on sd card
	if ( Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED) == false ) {
	    bSakura.setText("sd card error");
	    return;
	    // need to prompt SD card issue
	}


	Intent launchingIntent = getIntent();
	String action = launchingIntent.getAction();
	if ( launchingIntent.hasExtra("DL_PKG") ){
	    Uri data = launchingIntent.getData();
	    bKero.setText("launching to extract nar at:" + data);
	}
	else if (action!= null && action.equalsIgnoreCase(Intent.ACTION_VIEW) ) {
	    // need to check the data?
	    Log.d(TAG, " action_view with data:" + launchingIntent.getData());
	    Uri target = launchingIntent.getData();
	    if ( target != null )
	    addNarToDownload(target);
	    // enqueue to download
	}

	updateSurfaceKeys(g);
	currentSurfaceKey = surfaceKeys[0];
	currentSurface = g.mgr.getSakuraSurface(currentSurfaceKey);
	checkAndLoadAnimation();
	sv.changeSurface("0");
	kv.changeSurface("10");
	
	ViewServer.get(this).addWindow(this);
    }

	private void updateSurfaceKeys(Ghost g) {
		int keycount = g.mgr.getTotalSurfaceCount();
		surfaceKeys = new String[keycount];
		Set<String> k = g.mgr.getSurfaceKeys();
		surfaceKeys = k.toArray(surfaceKeys);
		Arrays.sort(surfaceKeys);
	}

    public void onDestroy() {
	super.onDestroy();
	ViewServer.get(this).removeWindow(this);
    }

    public void onResume() {
	super.onResume();
	ViewServer.get(this).setFocusedWindow(this);
    }


    private void checkAndUpdateLayoutParam() {
	// we need to get actual width of fl
	// then get width of sakura and kero
	int layoutWidth = fl.getWidth();
	int layoutHeight = fl.getHeight();
	if ( layoutHeight <= 0 || layoutWidth <= 0 )
	    return;

	int sH = sv.currentSurface.origH;
	int sW = sv.currentSurface.origW;
	int kH = kv.currentSurface.origH;
	int kW = kv.currentSurface.origW;

	// need to compute the maximum allowed width and height for both kero and sakura
	// first compare sW + kW and lW
	float wScale = 1.0f;
	if ( sW + kW > layoutWidth ) {
	    wScale = ((float) layoutWidth / (float)(sW + kW) );
	}

	float hScale = 1.0f;
	int vH = Math.max(sH, kH);
	if ( vH > layoutHeight ) {
	    hScale = ((float) layoutHeight / (float)vH );
	}
	
	float scale = Math.min(wScale, hScale);
	Log.d(TAG, "wScale:hScale="+wScale+":"+hScale+", [lH:lW]=["+layoutHeight+":"+layoutWidth+"],[sh:sw]="
	      + sH + ":" + sW +"]");
	FrameLayout.LayoutParams lpS = new FrameLayout.LayoutParams((int)(sW * scale),
								    (int)(sH * scale),
								    Gravity.BOTTOM | Gravity.RIGHT);
	sv.setLayoutParams(lpS);
	FrameLayout.LayoutParams lpK = new FrameLayout.LayoutParams((int)(kW*scale),
								    (int)(kH*scale),
								    Gravity.BOTTOM | Gravity.LEFT);
	kv.setLayoutParams(lpK);
	fl.invalidate();
    }

    private void checkAndLoadAnimation() {
	sv.changeSurface(currentSurfaceKey);
	kv.changeSurface("10");
	lm.checkAndUpdateLayoutParam();
	//checkAndUpdateLayoutParam();


	if (sv.hasAnimation() == false ){
	    findViewById(R.id.btn2).setEnabled(false);
	}
	else {
	    //iv.loadFirstAvailableAnimation();
	    animeIndex = currentSurface.getFirstAnimationIndex();
	    sv.loadAnimation(""+animeIndex);
	    /*anime = (AnimationDrawable) currentSurface.getAnimation(animeIndex, getResources());
	    anime.setVisible(true, true);
	    iv.setImageDrawable(anime);*/
	    findViewById(R.id.btn2).setEnabled(true);
	}

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
	Log.d(TAG, "loading surface:" + currentSurfaceKey);
	currentSurface = sv.mgr.getSakuraSurface(currentSurfaceKey);
	bSakura.setText("current drawable key: " + currentSurfaceKey + 
		   ", animation count: " + currentSurface.getAnimationCount() +
		   ", collision count: " + currentSurface.getCollisionCount()
		   );
	checkAndLoadAnimation();
    }
    int animeIndex = 0;

    public void onAnimate(View v) {
	//iv.setImageDrawable(anime);
// 	anime.stop(); // stop previous animation?
// 	anime.start();
	sv.startAnimation();
	
    }

    private void pickNextAnimation() {
	if ( currentSurface.getAnimationCount() > 1 ) {
	    animeIndex++;
	    if ( animeIndex >= currentSurface.getAnimationCount() )
		animeIndex = 0;

	    sv.loadAnimation(""+animeIndex);
	}
    }

    public void onShowCollision(View v){
	showCollisionAreaOnImageView();
    }


    public void ivClick(View v) {
	//anime.start();
	//showCollisionAreaOnImageView();
	pickNextAnimation();
	//runner.run();
    }

    private void showCollisionAreaOnImageView() {
	sv.showCollisionArea();
    }

    public void runClick(View v){
	/*	String cmd ="\\habcdefghijklmnop\\uponmlkjihgfedcba\\h\\s[4]ksdjaklajdkasdjkl\\uasndklandklan\\s[300]\\nksjdklasjdk\\halalalsk\\e";
	runner.addMsgToQueue(new String[]{cmd});
	runner.run();*/
	startService(new Intent(this, NanidroidService.class));	
    }

    private void addNarToDownload(Uri target){
	Intent i = new Intent(this, NanidroidService.class);
	i.setAction(Intent.ACTION_RUN);
	i.setData(target);

	startService(i);	
    }
    
    public void narTest(View v){
	//extractNarTest();
	addNarToDownload(Uri.parse("http://xx.xx.xxx/path/to/the/blab.nar"));
    }

    private void extractNar(String targetPath){
	File dataDir = new File(getExternalFilesDir(null), "ghost");
	String ghostId = NarUtil.readNarGhostId(targetPath);

	if ( gm.hasSameGhostId(ghostId) == false ) {
	    String gPath = gm.installGhost(ghostId, targetPath);
	    if ( gPath != null ){
		// should show readme if one present
		Log.d(TAG, "ghost:" + ghostId + " installed at:" + gPath);
		File readme = new File(gPath, "readme.txt");
		if ( readme.exists() ) {
		    showReadme(readme, ghostId);
		}
		else {
		    showGhostInstalledDlg(ghostId);
		}
	    }
	}
    }

    private void showReadme(File readme, String ghostId){
	
    }

    private void showGhostInstalledDlg(String ghostId){

    }

    int cGindex = 0;
    public void onNextGhost(View v){
	String [] gname = {"first","yohko","2elf"};
	switchGhost(gname[cGindex]);
	cGindex++;
	if ( cGindex > gname.length -1)
	    cGindex = 0;
    }

    private void switchGhost(String nextId){
	Ghost g = gm.createGhost(nextId);
	sv.setMgr(g.mgr);
	kv.setMgr(g.mgr);
	runner.setGhost(g);
	updateSurfaceKeys(g);
	
	keyindex = 0;
	currentSurfaceKey = surfaceKeys[keyindex];	
	sv.changeSurface(currentSurfaceKey);
	kv.changeSurface("10");
	lm.checkAndUpdateLayoutParam();
    }

    public void onNewIntent(Intent intent) {
	if ( intent.hasExtra("DL_PKG") ){
	    Uri data = intent.getData();
	    bKero.setText("launching to extract nar at:" + data);

	    extractNar("/mnt/sdcard/2elf-2.41.nar");
	}
	
    }


}
