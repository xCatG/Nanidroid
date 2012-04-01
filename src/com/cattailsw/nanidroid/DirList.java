package com.cattailsw.nanidroid;

import android.os.Environment;
import java.io.File;
import android.content.Context;
import android.util.Log;

public class DirList {
    private static final String TAG = "DirList";

    public DirList() {

    }

    // need to check if ap directory exists...
    public static void parseDataDir(Context ctx) {
	File dataDir = new File(ctx.getExternalFilesDir(null), "ghost");
	Log.d(TAG, "dir path=" + dataDir.getAbsolutePath());
	String dP = dataDir.getAbsolutePath();
	String [] dirz = dataDir.list();
	for ( String d : dirz ) {
	    InfoOnlyGhost g = new InfoOnlyGhost(dP + "/" + d);

	    Log.d(TAG, "got ghost [" + g.getGhostName() + "]");
	    Log.d(TAG, " craftman [" + g.getCrafterName() + "]");
	    Log.d(TAG, "   sakura [" + g.getSakuraName() + "]");
	    Log.d(TAG, "     kero [" + g.getKeroName() + "]");

	}
    }
}
