package com.cattailsw.nanidroid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.content.Context;
import android.util.Log;

public class DirList {
    private static final String TAG = "DirList";

    public DirList() {

    }

    // need to check if ap directory exists...
    public static List<InfoOnlyGhost> parseDataDir(Context ctx) {
	File dataDir = new File(ctx.getExternalFilesDir(null), "ghost");
	Log.d(TAG, "dir path=" + dataDir.getAbsolutePath());
	String dP = dataDir.getAbsolutePath();
	String [] dirz = dataDir.list();
	if (dirz == null )
	    return null;

	List<InfoOnlyGhost> ret = new ArrayList<InfoOnlyGhost>(dirz.length);
	for ( String d : dirz ) {
	    InfoOnlyGhost g = new InfoOnlyGhost(dP + "/" + d);
	    if ( g.ghostError() ) {
		Log.d(TAG, "error in ghost in:" + d);
		continue;
	    }

	    Log.d(TAG, "got ghost [" + g.getGhostName() + "]");
	    Log.d(TAG, " craftman [" + g.getCrafterName() + "]");
	    Log.d(TAG, "   sakura [" + g.getSakuraName() + "]");
	    Log.d(TAG, "     kero [" + g.getKeroName() + "]");

	    // need to add this ghost to list of available ghosts
	    ret.add(g);
	}
	return ret;
    }
    
}
