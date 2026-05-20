package com.cattailsw.nanidroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedList;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import com.cattailsw.nanidroid.util.NarUtil;
import com.cattailsw.nanidroid.util.NetworkUtil;
import com.cattailsw.nanidroid.util.AnalyticsUtils;
import android.util.Pair;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import org.apache.http.conn.ConnectTimeoutException;

/**
 * Describe class <code>HeadLineSensorService</code> here.
 *
 * @author <a href="mailto:"></a>
 * @version 1.0
 */
public class NanidroidService extends Service {
    private static final String TAG = "HeadLineSensorService";
    private SScriptRunner runner = null;

    public static final int START_HEADLINE_SENSOR = 9000;
    public static final int START_NAR_DL = 9001;

    public static final String ACTION_CAN_STOP = "canstopsensing";

    public static final String EXT_GID = "ghost_id_to_update";
    public static final String EXT_GROOT = "ghost_root_to_update";

    public static Intent createUpdateIntent(Context ctx, String homeurl, String ghostId, String ghostRoot) {
	Intent u = new Intent(ctx, NanidroidService.class);
	u.setAction(Intent.ACTION_SYNC);
	u.setData(Uri.parse(homeurl));
	u.putExtra(EXT_GID, ghostId);
	u.putExtra(EXT_GROOT, ghostRoot);
	return u;
    }

    @Override
    public IBinder onBind(Intent arg0) {
	return null;
    }

    @Override
    public void onCreate() {
	super.onCreate();
    }
    private static final int DEF_TIME = 600000;
    private void startHttpTask(long time) {
	mHandler.sendEmptyMessageDelayed(HTTP_TASK_START, time);
    }

    public final int onStartCommand(Intent intent, int flags, int startId){
	handleCommand(intent, startId);
	return START_NOT_STICKY;
    }

    @Override
    public void onStart(Intent intent, int startId) {
	super.onStart(intent, startId);
	handleCommand(intent, startId);
    }

    private void handleCommand(Intent i, int startId){
	String action = i.getAction();

	if ( action == null ) {
	    startHttpTask(1000);
	}
	else if (action.equalsIgnoreCase( Intent.ACTION_RUN ) ) {
	    Uri data = i.getData();
	    if ( data != null ) {
		NarDownloadTask n = new NarDownloadTask(data, startId);
		n.execute(this);		

	    }
	}
	else if ( Intent.ACTION_SYNC.equalsIgnoreCase(action) ) {
	    // need to check for data in the intent...
	    // we need homeurl
	    // ghostid
	    Uri homeurl = i.getData();
	    String gid = i.getStringExtra(EXT_GID);
	    String gr = i.getStringExtra(EXT_GROOT);
	    if(homeurl != null && gid != null ) {
		GhostUpdateTask g = new GhostUpdateTask(homeurl, gid, gr, startId);
		g.execute(this);
	    }
	    else {
		// need to flag error?
	    }
	}
	else if ( action.equalsIgnoreCase( ACTION_CAN_STOP ) ) {
	    stopSelf(startId);	    
	}

    }

    @Override
    public void onDestroy() {
	super.onDestroy();
	if (runner != null)
	    runner.stop();

	Log.d(TAG, "onDestory: called");

	mHandler.removeMessages(HTTP_TASK_START);
    }

    static final private int HTTP_TASK_START = 1;
    private Handler mHandler = new Handler() {
	    @Override
	    public void handleMessage(Message msg) {
		switch (msg.what) {
		case HTTP_TASK_START:
		    new SensingTask().execute(NanidroidService.this);
		    startHttpTask(DEF_TIME);
		    break;
		}
	    }
	};

    private class SensingTask extends AsyncTask<Context, String, String> {

	@Override
	protected void onPreExecute() {
	    // You can't call this in the background thread
	    runner = SScriptRunner.getInstance(NanidroidService.this);
	}

	@Override
	protected String doInBackground(Context... args) {
	    long start = System.currentTimeMillis();
	    LinkedList<String> pageContent;
	    try {

		// Try querying the Bottle log API for today's bottle
		pageContent = SSTPBottleSensor.getPageContent(args[0]);
		Log.d(TAG, "bottle.length() = " + pageContent.size());
		runner.addMsgToQueue(pageContent);
		
		pageContent = BottleLogSensor.getPageContent(args[0]);
		runner.addMsgToQueue(pageContent);

	    } catch (Exception e) {
	    }

	    Log.d(TAG, "time = " + (System.currentTimeMillis() - start)
		  + " [ms]");
	    return "End of conversions";
	}

	public void onPostExecute(String result) {
	    if ( runner != null )
		runner.run();
	}
    }

    private class NarDownloadTask extends AsyncTask<Context, String, String> {
	String targetUrl = null;
	Uri targeturi = null;
	int svcid = -1;
	NarDownloadTask(Uri uri, int sid){
	    targeturi = uri;
	    targetUrl = Uri.decode(targeturi.toString());
	    svcid = sid;
	}

	public String doInBackground(Context... args) {
	    try {
		Context ctx = args[0];
		File extDir = ctx.getExternalCacheDir();
		File targetPath = new File(extDir, targeturi.getLastPathSegment());
		Log.d(TAG, "downloading:"+targetUrl+" to " + targeturi.getLastPathSegment());

		if ( NetworkUtil.exists(args[0], targetUrl) == false ) {
		    Log.d(TAG, "file doesn't exist");
		    return null;
		}

		InputStream is = NetworkUtil.getURLStream(args[0], targetUrl);
		NarUtil.copyFile(is, new FileOutputStream(targetPath));
		
		return targetPath.toString();
	    }
	    catch(Exception e){
		e.printStackTrace();
		return null;
	    }
	}

	public void onPostExecute(String result) {
	    if ( result == null ) {
		Log.d(TAG, "download failed.");
		return;
	    }

	    Log.d(TAG, "download complete?");

	    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

	    Notification n = new Notification(R.drawable.notification, getString(R.string.dl_complete), 
					      System.currentTimeMillis());
	    Intent ni = new Intent(NanidroidService.this, Nanidroid.class);
	    ni.setData(Uri.fromFile(new File(result))).putExtra("DL_PKG",0);
	    PendingIntent pi = PendingIntent.getActivity(NanidroidService.this, 0, ni, 0);
	    String notetext = String.format(getString(R.string.dl_note), targeturi.getLastPathSegment());
	    n.setLatestEventInfo(getApplicationContext(), getString(R.string.dl_complete), notetext, pi);
	    n.flags = Notification.FLAG_AUTO_CANCEL;
	    nm.notify(42, n);

	    if ( svcid != -1 )
		stopSelf(svcid);
	}


    }

    private class GhostUpdateTask extends AsyncTask<Context, String, String>{
	private static final String U_FILE = "updates2.dau";
	private static final String U_FILE2 = "updates.txt";

	int sid = -1;
	Uri base;
	String ghostId;
	String ghostRoot;
	String failedReason = null;
	long startTime = 0;
	GhostUpdateTask(Uri baseUri, String gid, String groot, int svcid){
	    // need to ask shiori on UI thread? or should be passed into this task?
	    base = baseUri;
	    ghostId = gid;
	    ghostRoot = groot;
	    sid = svcid;
	}

	@Override
	protected void onPreExecute() {
	    // You can't call this in the background thread
	    startTime = System.currentTimeMillis();
	    if ( runner == null )
	    runner = SScriptRunner.getInstance(NanidroidService.this);

	}


	public String doInBackground(Context... args) {
	    // first, try to grab the file, either U_FILE or U_FILE2
	    try {
		Context ctx = args[0];
		String ufile = Uri.withAppendedPath(base, U_FILE).toString();
		if ( NetworkUtil.exists(ctx, ufile) == false ) {
		    ufile = Uri.withAppendedPath(base, U_FILE2).toString();
		    if ( NetworkUtil.exists(ctx, ufile) == false ) {
			failedReason = "404";
			return null;
		    }
		}

		String targetPath = ghostRoot + "/" + U_FILE;
		// now download ufile to /files/ghost/ghostId/U_FILE
		InputStream is = NetworkUtil.getURLStream(ctx, ufile);
		byte[]md5 = NarUtil.copyFile(is, new FileOutputStream(targetPath));
		Log.d(TAG, "downloaded " + targetPath + "w md5:" + NarUtil.md5ToString(md5));

		// now we can read the update file content and start compariing file
		int uCount = doFileComp(targetPath);
		if ( uCount == 0 ) {
		    failedReason = "none";
		    return null;
		}
		runner.doShioriEvent("OnUpdateReady", new String[]{"changed", 
								   csvFilelist});
		// perform OnUpdateReady

		// do download and compare...
		doDownloadCompare(ctx);
		
		// we can just insert msg to queue and don't worry about thread issues...
		return null;
	    }
	    catch(ConnectTimeoutException e) {
		failedReason = "timeout";
	    }
	    catch(Exception e) {
		failedReason = "exception during update";
	    }

	    return null;
	}

	List<Pair<String,String>> filesToUpdate = null;
	String csvFilelist = null;
	// return number of files to be updated,
	// also put files to filesToUpdate array...
	private int doFileComp(String ufile) throws IOException, FileNotFoundException{
	    // need to create a vector of pair<string, string> to store filepath and md5 from ufile
	    filesToUpdate = getUpdateMd5z(ufile);
	    Log.d(TAG, "got " + filesToUpdate.size() + " files to update");

	    return filesToUpdate.size();
	}

	private List<Pair<String,String>> getUpdateMd5z(String ufile) throws IOException, FileNotFoundException {
	    File f = new File(ufile);
	    BufferedReader br = new BufferedReader( new FileReader(f) );
	    ArrayList<Pair<String, String>> ret = new ArrayList<Pair<String, String>>();
	    String line = br.readLine();
	    while( line != null ) {
		// line is org in filename^Amd5^A\r\n 
		String p[] = line.split("\u0001");
		if ( p.length < 2 ) {// file format is wrong...
		    // try using comma
		    p = line.split(",");
		    if ( p.length < 2 ) {
			br.close();

			// we need to note the issue here...
			AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "update_error",
								    ghostId, -99);
			throw new IOException();
		    }

		}

		Log.d(TAG, "pair=" + p[0] + "," + p[1]);

		File fz = new File(ghostRoot, p[0]);
		Log.d(TAG, "file is=" + fz.getAbsolutePath());
		if ( fz.exists() == false ) {
		    // add the pair
		    Log.d(TAG, "local file not exist");
		    addToUpdateList(ret, p[0], p[1]);
		}
		else {
		    // check MD5
		    byte[] fmd5 = NarUtil.createMD5( new FileInputStream(fz) );
		    if ( p[1].compareTo(NarUtil.md5ToString(fmd5)) != 0 ) {
			Log.d(TAG, "MD5 checksum mismatch:" + p[1]);
			addToUpdateList(ret, p[0], p[1]);
		    }
		}
		line = br.readLine();
	    }

	    return ret;
	}

	private void addToUpdateList(List<Pair<String, String>> ret, String f, String s) {
	    Pair<String, String> pp = new Pair(f, s);
	    ret.add(pp);
	    if ( csvFilelist == null )
		csvFilelist = f;
	    else
		csvFilelist += "," + f;
	}

	private void doDownloadCompare(Context ctx) throws IOException, FileNotFoundException {
	    // go through the filesToUpdate list

	    int i = 0; int total = filesToUpdate.size();
	    for( Pair<String, String> p : filesToUpdate ) {
	    // for each file, first download to a tmp file
	    // then check MD5
	    // if correct, del orig file, rename tmp to file
		// OnUpdate.OnDownloadBegin
		runner.doShioriEvent("OnUpdate.OnDownloadBegin", 
				     new String[]{p.first, "" + i + 1, "" + total});
		Uri furi = Uri.withAppendedPath(base, p.first);
		String fname = ghostRoot + "/" + p.first + ".tmp";
		File fn = new File(fname);
		File fp = fn.getParentFile();
		if ( fp != null && fp.exists() == false) fp.mkdirs();
		Log.d(TAG, "dl:" + p.first + " to " + fname);
		Log.d(TAG, "from " + furi);
		
		InputStream is = NetworkUtil.getURLStream(ctx, furi.toString());
		byte[]md5 = NarUtil.copyFile(is, new FileOutputStream(fname));

		String md5s = NarUtil.md5ToString(md5);
		// OnUpdate.OnMD5CompareBegin
		runner.doShioriEvent("OnUpdate.OnMD5CompareBegin",
				     new String[]{p.first, p.second, md5s});
		if ( p.second.compareTo( md5s ) != 0 ) {
		    failedReason = "md5 miss";
		    Log.d(TAG, "md5 error on " + p.first);
		    // OnUpdate.OnMD5CompareFailure
		    runner.doShioriEvent("OnUpdate.OnMD5CompareFailure",
					 new String[]{p.first, p.second, md5s});
		    break;
		}
		// OnUpdate.OnMD5CompareComplete
		runner.doShioriEvent("OnUpdate.OnMD5CompareComplete",
				     new String[]{p.first, p.second, md5s});

		File ft = new File(ghostRoot + "/" +  p.first);
		if ( ft.exists() ) { 
		    boolean bdelete = ft.delete();
		    if ( bdelete == false ) { // delete fail
			Log.d(TAG, "cannot create file" + ft.getAbsolutePath());
			failedReason = "fileio";
			break;
		    }
		}
		boolean bRename = fn.renameTo(ft);
		if ( bRename == false ) {
		    Log.d(TAG, "cannot rename file");
		    failedReason = "fileio";
		    break;
		}
	    }

	}

	public void onPostExecute(String result) {
	    if ( failedReason == null ) {
		// OnUpdateComplete
		runner.doShioriEvent("OnUpdateComplete", new String[] {"changed",
								       csvFilelist});
	    }
	    else {
		// OnUpdateFilure
		Log.d(TAG, "do OnUpdateFilure because " + failedReason);
		runner.doShioriEvent("OnUpdateFilure", new String[]{failedReason,
								    csvFilelist});
	    }

	    if ( sid != -1 )
		stopSelf(sid);

	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PERF, "update_time", "", 
							(int)(System.currentTimeMillis() - startTime));
	}
    }

}
