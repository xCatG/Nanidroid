package com.cattailsw.nanidroid.test;

import java.util.regex.Matcher;
import android.util.Log;

public class Util {
    private static final String TAG = "Util";
    public static void printMatch(Matcher m) {
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

}
