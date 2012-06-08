package com.cattailsw.nanidroid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import java.util.Set;
import android.graphics.Rect;

public class SurfaceManager {

    //private static SurfaceManager _self = null;
    public SurfaceManager(String ghostid) {

    }

//     public static SurfaceManager getInstance() {
// 	if ( _self == null )
// 	    _self = new SurfaceManager();

// 	return _self;
//     }
    
    String ghostId = null;

    Map<String, ShellSurface> surfaces = null;
    Map<String, String[]> sakuraAliasTable = null; // alias can be N-to-one?
    Map<String, String[]> keroAliasTable = null;

    private static ShellSurface nulSurface = new ShellSurface(); // a null surface
    
    public int addSurface(String id, ShellSurface s) {
	if ( surfaces == null )
	    surfaces = new HashMap<String, ShellSurface>();

	surfaces.put(id, s);

	return surfaces.size();
    }

    public boolean containsSurface(String id) {
	if ( surfaces == null )
	    return false;
	return surfaces.containsKey(id);
    }

    public int getTotalSurfaceCount() {
	return surfaces.size();
    }

    public Set<String> getSurfaceKeys() {
	return surfaces.keySet();
    }

    // this can result in null!!
    public ShellSurface getSurface(String id){
    	if ( surfaces.containsKey(id))
    		return surfaces.get(id);
    	
    	return null;
    }
    
    public ShellSurface getSakuraSurface(String id) {
	if ( surfaces.containsKey(id) )
	return surfaces.get(id);
	else if ( surfaces.containsKey("0"))
	    return surfaces.get("0");
	else
		return nulSurface;
    }

    public ShellSurface getKeroSurface(String id) {
	if ( surfaces.containsKey(id) )
	    return surfaces.get(id);
	else if ( surfaces.containsKey("10"))
	    return surfaces.get("10");
	else 
		return nulSurface;//we should have a dummy shellsurface...
    }


    public Drawable getSurfaceDrawable(String id, Resources res) {
    	try {
	return surfaces.get(id).getSurfaceDrawable(res);
    	}
    	catch (Exception e) {
    		return null;
    	}
    }

    public Rect getSurfaceRect(String id, Resources res){
    	try {
	    return surfaces.get(id).getSurfaceDim();
    	}
    	catch (Exception e) {
    		return null;
    	}	
    }
    
    public String dumpSurfaces() {
    	StringBuilder sb = new StringBuilder();
    	ArrayList<String> kz = new ArrayList<String>(surfaces.keySet());
    	Collections.sort(kz);
    	for ( String k : kz) {
    		sb.append(surfaces.get(k).dumpSurfaceStat());    	
    	}
    	return sb.toString();
    }
}
