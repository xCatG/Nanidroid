package com.cattailsw.nanidroid;

import java.util.HashMap;
import java.util.Map;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import java.util.Set;

public class SurfaceManager {

    private static SurfaceManager _self = null;
    protected SurfaceManager() {

    }

    public static SurfaceManager getInstance() {
	if ( _self == null )
	    _self = new SurfaceManager();

	return _self;
    }

    Map<String, ShellSurface> surfaces = null;
    Map<String, String[]> sakuraAliasTable = null; // alias can be N-to-one?
    Map<String, String[]> keroAliasTable = null;

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

    public ShellSurface getSakuraSurface(String id) {
	if ( surfaces.containsKey(id) )
	return surfaces.get(id);
	else
	    return surfaces.get("0");
    }

    public ShellSurface getKeroSurface(String id) {
	if ( surfaces.containsKey(id) )
	    return surfaces.get(id);
	else
	    return surfaces.get("10");
    }


    public Drawable getSurfaceDrawable(String id, Resources res) {
	return surfaces.get(id).getSurfaceDrawable(res);
    }
}