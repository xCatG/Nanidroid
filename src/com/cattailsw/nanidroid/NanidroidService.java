package com.cattailsw.nanidroid;

import java.util.LinkedList;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import java.io.InputStream;

import com.cattailsw.nanidroid.util.NetworkUtil;
import com.cattailsw.nanidroid.util.NarUtil;
import android.content.Intent;
import android.app.NotificationManager;
import android.app.Notification;
import java.io.File;
import android.app.PendingIntent;
import java.util.List;
import java.io.FileOutputStream;

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

    /**
     * Describe <code>onStartCommand</code> method here.
     *
     * @param intent an <code>Intent</code> value
     * @param n an <code>int</code> value
     * @param n1 an <code>int</code> value
     * @return an <code>int</code> value
     */
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

	NarDownloadTask(Uri uri){
	    this(uri, -1);
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

	    Notification n = new Notification(R.drawable.ic_launcher, "Download completed", System.currentTimeMillis());
	    Intent ni = new Intent(NanidroidService.this, Nanidroid.class);
	    ni.setData(Uri.fromFile(new File(result))).putExtra("DL_PKG",0);
	    PendingIntent pi = PendingIntent.getActivity(NanidroidService.this, 0, ni, 0);
	    n.setLatestEventInfo(getApplicationContext(), "Download completed", "dl", pi);
	    n.flags = Notification.FLAG_AUTO_CANCEL;
	    nm.notify(42, n);

	    if ( svcid != -1 )
		stopSelf(svcid);
	}


    }

}
