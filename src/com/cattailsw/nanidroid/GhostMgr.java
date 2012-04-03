package com.cattailsw.nanidroid;

import android.content.Context;
import java.util.List;

import com.cattailsw.nanidroid.util.PrefUtil;
import com.cattailsw.nanidroid.util.NarUtil;
import java.io.File;

public class GhostMgr {
    private static final String TAG = "GhostMgr";
    private static final String PREF_LAST_RUN_GHOST = "lastrunghost";
    Context mCtx;

    List<InfoOnlyGhost> iglist = null;

    public GhostMgr(Context ctx) {
	mCtx = ctx.getApplicationContext();
	iglist = DirList.parseDataDir(mCtx);
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

	return (getGhostId(id) != -1);
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

    public String getLastRunGhostId(){
	if ( PrefUtil.hasKey(mCtx, PREF_LAST_RUN_GHOST))
	    return PrefUtil.getKeyValue(mCtx, PREF_LAST_RUN_GHOST);

	return null;
    }

    public void setLastRunGhost(Ghost g){
	String gid = g.getGhostDirName();

	PrefUtil.setKey(mCtx, PREF_LAST_RUN_GHOST, gid);
    }

    public String installGhost(String ghostId, String narPath){
	File dataDir = new File(mCtx.getExternalFilesDir(null), "ghost");
	boolean success = NarUtil.readNarArchive(narPath, dataDir.getAbsolutePath());
	refreshGhost();
	int gid =  getGhostId( ghostId);
	if ( gid == -1 ) return null;
	String path = getGhostPath(gid);
	return path;
    }

    public void refreshGhost(){
	iglist = DirList.parseDataDir(mCtx);
    }

    public String[] getGnames(){
	if ( iglist == null || iglist.size() == 0 )
	    return null;

	String []ret = new String[iglist.size()];
	int i =0;
	for ( InfoOnlyGhost g: iglist ) {
	    ret[i] = g.getGhostDirName();
	}
	return ret;
    }
}
