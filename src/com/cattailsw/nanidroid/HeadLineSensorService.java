package com.cattailsw.nanidroid;

import java.util.LinkedList;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

public class HeadLineSensorService extends Service {
	private static final String TAG = "HeadLineSensorService";
	private SScriptRunner runner = null;

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		startHttpTask();
	}

	private void startHttpTask() {
		mHandler.sendEmptyMessageDelayed(HTTP_TASK_START, 180000);
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		runner = SScriptRunner.getInstance(this);

		String action = intent.getAction();
		if (action == null) {
			// do nothing...
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
				new SensingTask().execute(HeadLineSensorService.this);
				startHttpTask();
				break;
			}
		}
	};

	private class SensingTask extends AsyncTask<Context, String, String> {

		@Override
		protected void onPreExecute() {
			// You can't call this in the background thread
			runner = SScriptRunner.getInstance(HeadLineSensorService.this);
		}

		@Override
		protected String doInBackground(Context... args) {
			long start = System.currentTimeMillis();
			LinkedList<String> pageContent;
			try {
				// Try querying the Twitter Search API for today's twit
				/*
				 * SimpleTwitterHelper.prepareUserAgent(args[0]); pageContent =
				 * SimpleTwitterHelper
				 * .getPageContent(HeadLineSensorService.this); Log.d("TEST",
				 * "results.length() = " + pageContent.size());
				 * runner.addMsgToQueue(pageContent);
				 */

				// Try querying the Bottle log API for today's bottle
				SSTPBottleSensor.prepareUserAgent(args[0]);
				pageContent = SSTPBottleSensor.getPageContent();
				Log.d("TEST", "bottle.length() = " + pageContent.size());
				runner.addMsgToQueue(pageContent);

			} catch (Exception e) {
			}

			Log.d(TAG, "time = " + (System.currentTimeMillis() - start)
					+ " [ms]");
			return "End of conversions";
		}

	}
}
