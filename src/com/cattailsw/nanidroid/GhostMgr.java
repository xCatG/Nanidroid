package com.cattailsw.nanidroid;

import android.content.Context;
import java.util.List;

public class GhostMgr {

    List<InfoOnlyGhost> iglist = null;

    public GhostMgr(Context ctx) {
	iglist = DirList.parseDataDir(ctx);
    }

    public int getGhostId(String name){
	int id = 0;
	for ( InfoOnlyGhost g: iglist ){
	    if ( g.getGhostDirName().equalsIgnoreCase(name) )
		return id;
	    id++;
	}
	return -1;
    }

    public boolean hasSameGhostId(String id){
	if ( iglist == null || iglist.size() == 0 )
	    return false;

	return (getGhostId(id) == -1);
    }

    public String getGhostPath(int id){
	return iglist.get(id).getGhostPath();
    }

    public Ghost createGhost(String name){
	int id = getGhostId(name);
	if ( id == -1 )
	    return null;

	return new Ghost(getGhostPath(id));
    }

}
