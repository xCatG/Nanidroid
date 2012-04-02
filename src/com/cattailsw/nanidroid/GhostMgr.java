package com.cattailsw.nanidroid;

import android.content.Context;
import java.util.List;

public class GhostMgr {

    List<InfoOnlyGhost> iglist = null;

    public GhostMgr(Context ctx) {
	iglist = DirList.parseDataDir(ctx);
    }

    public boolean hasSameGhostId(String id){
	if ( iglist == null || iglist.size() == 0 )
	    return false;

	for ( InfoOnlyGhost g : iglist ) {
	    if ( g.getGhostDirName().equalsIgnoreCase(id) )
		return true;
	}

	return false;
    }

    public String getGhostPath(int id){
	return iglist.get(id).getGhostPath();
    }

}
