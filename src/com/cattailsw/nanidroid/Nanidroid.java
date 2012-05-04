package com.cattailsw.nanidroid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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
import com.cattailsw.nanidroid.util.AnalyticsUtils;
import com.cattailsw.nanidroid.util.NarUtil;

import android.widget.FrameLayout;
import android.view.Gravity;
import android.content.Intent;
import java.util.List;
import android.net.Uri;
import android.widget.FrameLayout.LayoutParams;
import android.content.Intent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Bundle;

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

    private static final String MIN_TAG = "minimized";
    private boolean restoreFromMinimize = false;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        initGA();
	sv = (SakuraView) findViewById(R.id.sakura_display);
	kv = (KeroView) findViewById(R.id.kero_display);
	bSakura = (Balloon) findViewById(R.id.bSakura);
	bKero = (Balloon) findViewById(R.id.bKero);
	fl = (FrameLayout) findViewById(R.id.fl);
	// need to get a list of ghosts on sd card
	if ( Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED) == false ) {
	    bSakura.setText("sd card error");
	    return;
	    // need to prompt SD card issue
	}

	if ( savedInstanceState != null ) {
	    Log.d(TAG, "was minimized");
	    restoreFromMinimize = savedInstanceState.getBoolean(MIN_TAG, false);
	}

	//mgr = SurfaceManager.getInstance();
	lm = LayoutManager.getInstance(this);
	runner = SScriptRunner.getInstance(this);
	gm = new GhostMgr(this);
	if ( gm.getGhostCount() == 0 )
		runOnFirstExecution(); // extract first to file dir on sd card
	
	String lastId = gm.getLastRunGhostId();
	if ( lastId == null ) lastId = "first";
	Ghost g = gm.createGhost(lastId);
	runner.setGhost(g);
	gm.setLastRunGhost(g);

	runner.setViews(sv, kv, bSakura, bKero);
	sv.setMgr(g.mgr);
	kv.setMgr(g.mgr);
	lm.setViews(fl, sv, kv, bSakura, bKero);
	runner.setLayoutMgr(lm);


	Intent launchingIntent = getIntent();
	handleIntent(launchingIntent);

	updateSurfaceKeys(g);
	currentSurfaceKey = surfaceKeys[0];
	currentSurface = g.mgr.getSakuraSurface(currentSurfaceKey);
	//checkAndLoadAnimation();
	sv.changeSurface(currentSurfaceKey);
	kv.changeSurface("10");
	
	ViewServer.get(this).addWindow(this);
    }

    private void initGA(){
    	AnalyticsUtils.getInstance(getApplicationContext(), Setup.UA_CODE, true);
    }

    private void updateSurfaceKeys(Ghost g) {
	int keycount = g.mgr.getTotalSurfaceCount();
	surfaceKeys = new String[keycount];
	Set<String> k = g.mgr.getSurfaceKeys();
	surfaceKeys = k.toArray(surfaceKeys);
	Arrays.sort(surfaceKeys);
    }
 

    public void onPause() {
	super.onPause();
	if ( runner!= null ) { 
	    runner.stopClock();

	}
	sendStopIntent();
    }

    public void onDestroy() {
	super.onDestroy();
	ViewServer.get(this).removeWindow(this);
	sendStopIntent();
    }

    public void onResume() {
	super.onResume();
	if ( runner != null ) { 
	    runner.startClock();
	}
	AnalyticsUtils.getInstance(getApplicationContext()).trackPageView("/");
	ViewServer.get(this).setFocusedWindow(this);
    }

    public void onBackPressed() {
	if ( runner!= null ) { 
	    runner.stopClock();
	    runner.setCallback(mscb);
	    runner.stop();
	    runner.doExit();
	}
	else
	    super.onBackPressed();

    }

    private SScriptRunner.StatusCallback mscb = new SScriptRunner.StatusCallback() {
	    public void stop() {}
	    public void canExit() {
		runner.setCallback(null);
		finish();
	    }
	};


    public void onWindowFocusChanged(boolean flag) {
	lm.checkAndUpdateLayoutParam();
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
	kv.showCollisionArea();
    }

    public void runClick(View v){
	/*	String cmd ="\\habcdefghijklmnop\\uponmlkjihgfedcba\\h\\s[4]ksdjaklajdkasdjkl\\uasndklandklan\\s[300]\\nksjdklasjdk\\halalalsk\\e";
	runner.addMsgToQueue(new String[]{cmd});
	runner.run();*/
	startService(new Intent(this, NanidroidService.class));	
    }

    private void sendStopIntent(){
	Intent i = new Intent(this, NanidroidService.class);
	i.setAction(NanidroidService.ACTION_CAN_STOP);
	startService(i);	
    }


    private void addNarToDownload(Uri target){
	Intent i = new Intent(this, NanidroidService.class);
	i.setAction(Intent.ACTION_RUN);
	i.setData(target);

	startService(i);	
    }
    
    public void narTest(View v){
	//extractNarTest();
	//addNarToDownload(Uri.parse("http://xx.xx.xxx/path/to/the/blab.nar"));
	//showReadme(new File("/mnt/sdcard/Android/data/com.cattailsw.nanidroid/files/ghost/mana/readme.txt"), "mana");
    	//extractNar("/mnt/sdcard/Android/data/com.cattailsw.nanidroid/cache/yumenikki.nar");
	//runOnUiThread(runner);//.startClock();
	//runner.startClock();
	//showCollisionAreaOnImageView();
    	extractNar("/mnt/sdcard/2elf-2.41.nar", true);
    }

    private void extractNar(String targetPath) {
    	extractNar(targetPath, false);
    }
    
    private void extractNar(String targetPath, boolean force){
	String ghostId = NarUtil.readNarGhostId(targetPath);

	if ( (gm.hasSameGhostId(ghostId) == false )|| force == true) {
		if ( runner != null ) runner.doInstallBegin(ghostId);
		
		InstallTask i = new InstallTask(targetPath, ghostId);
		i.execute(targetPath);

	}
	else {
		if ( runner != null )runner.doShioriEvent("OnInstallRefuse", null);
	}
		
    }

	private void onSuccessGhostInstall(String ghostId, String gPath) {
		if (runner != null)
			runner.doInstallComplete(ghostId);
		// should show readme if one present
		Log.d(TAG, "ghost:" + ghostId + " installed at:" + gPath);
		File readme = new File(gPath, "readme.txt");
		if (readme.exists()) {
			showReadme(readme, ghostId);
		} else {
			showGhostInstalledDlg(ghostId);
		}
	}
    
    private class InstallTask extends AsyncTask<String, Integer, String> {
		private String targetPath;
		private String ghostId;

    	InstallTask(String tPath, String gId) {
    		targetPath = tPath;
    		ghostId = gId;
    	}
    	
		@Override
		protected String doInBackground(String... params) {
			String gPath = gm.installGhost(ghostId, targetPath);

			return gPath;
		}
    	
		public void onPostExecute(String gPath) {
			if ( gPath != null ) {
				onSuccessGhostInstall(ghostId, gPath);
			}
			else {
				if ( runner != null )runner.doShioriEvent("OnInstallFailure", null);
			}
		}
    }
    
    private void runOnFirstExecution(){
    	AssetManager a = getAssets();
    	try {
    		InputStream is = a.open("first.zip");
    		File extDir = getExternalCacheDir();
    		File targetPath = new File(extDir, "first.nar");
    		NarUtil.copyFile(is, new FileOutputStream(targetPath));
    		gm.installFirstGhost("first", targetPath.getPath());			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    private void showReadme(File readme, final String ghostId){
	View readmeView = View.inflate(this, R.layout.installdlg, null);
	WebView webView = (WebView) readmeView.findViewById(R.id.readme_view);
	webView.setWebViewClient(new WebViewClient() {

	    });
 	webView.loadDataWithBaseURL(Uri.fromFile(readme).toString(), NarUtil.readTxt(readme), 
 				    "text/html", "UTF-8", null);

	AlertDialog.Builder builder = new AlertDialog.Builder(this);
	builder.setTitle("installed new ghost");	
	builder.setView(readmeView);
	builder.setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener(){
		public void onClick(DialogInterface dlg, int which){
		    dlg.dismiss();
		}
	    });
	builder.setPositiveButton("switch to ghost", new DialogInterface.OnClickListener(){
		public void onClick(DialogInterface dlg, int which){
		    dlg.dismiss();
		    switchGhost(ghostId);
		}
	    });

	builder.show();
	
    }

    private void showGhostInstalledDlg(String ghostId){

    }

    int cGindex = 0;
    public void onNextGhost(View v){
	String [] gname = gm.getGnames();// {"first","yohko","2elf"};
	if ( gname == null )
	    return;

	switchGhost(gname[cGindex]);
	cGindex++;
	if ( cGindex > gname.length -1)
	    cGindex = 0;
    }

    private void switchGhost(String nextId){
    	Ghost g = null;
    	try {
	g = gm.createGhost(nextId);
    	}
    	catch(Exception e) {
    		Log.d(TAG, "failed to switch to ghost:" + nextId);
    		e.printStackTrace();
    		return;
    	}
	sv.setMgr(g.mgr);
	kv.setMgr(g.mgr);
	runner.setGhost(g);
	updateSurfaceKeys(g);
	
	keyindex = 0;
	currentSurfaceKey = surfaceKeys[keyindex];	
	sv.changeSurface(currentSurfaceKey);
	kv.changeSurface("10");
	lm.checkAndUpdateLayoutParam();
	gm.setLastRunGhost(g);
    }

    private void handleIntent(Intent intent){
		String action = intent.getAction();
		if (intent.hasExtra("DL_PKG")) {
			Uri data = intent.getData();
			bKero.setText("launching to extract nar at:" + data);

			extractNar(data.getPath());// "/mnt/sdcard/2elf-2.41.nar");
		}
		if (action != null && action.equalsIgnoreCase(Intent.ACTION_VIEW)) {
			// need to check the data?
			Log.d(TAG, " action_view with data:" + intent.getData());
			Uri target = intent.getData();
			if (target != null)
				addNarToDownload(target);
		}
	}
    
	public void onNewIntent(Intent intent) {
		handleIntent(intent);
	}

	public void onUpdate(View v) {
		
	}
	
	public void onListGhost(View v){
		
	}
	
	public void onHelp(View v) {
		
	}
	
	public void onMoreGhost(View v) {
		
	}
}
