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
import android.content.Intent;

/**
 * Describe class <code>HeadLineSensorService</code> here.
 *
 * @author <a href="mailto:"></a>
 * @version 1.0
 */
public class NanidroidService extends Service {
    private static final String TAG = "HeadLineSensorService";
    private SScriptRunner runner = null;

    @Override
    public IBinder onBind(Intent arg0) {
	return null;
    }

    @Override
    public void onCreate() {
	super.onCreate();
	startHttpTask(1000);
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
    public final int onStartCommand(final Intent intent, final int n, final int n1) {
	handleCommand(intent);
	return START_NOT_STICKY;
    }

    @Override
    public void onStart(Intent intent, int startId) {
	super.onStart(intent, startId);
	handleCommand(intent);
    }

    private void handleCommand(Intent i){
	String action = i.getAction();

	if ( action == null ) {

	}
	else if (action.equalsIgnoreCase( Intent.ACTION_RUN ) ) {
	    String data = Uri.decode(i.getDataString());
	    NarDownloadTask n = new NarDownloadTask(data);
	    n.execute(this);
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
	NarDownloadTask(String url){
	    targetUrl = url;
	}
	
	public String doInBackground(Context... args) {
	    try {
		Log.d(TAG, "downloading:"+targetUrl);
		//InputStream is = NetworkUtil.getURLStream(args[0], targetUrl);
	    }
	    catch(Exception e){
		e.printStackTrace();
	    }
	    return null;
	}

	public void onPostExecute(String result) {
	    Log.d(TAG, "download complete?");
	}


    }

}
