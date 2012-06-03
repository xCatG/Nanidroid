package com.cattailsw.nanidroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

import android.os.SystemClock;
import android.util.Log;

import com.cattailsw.nanidroid.util.AnalyticsUtils;

/* surface txt has structure like

surface*
{
name,value;
name,v1,v2,v3,v4;
name,v1,v2,v3,v4,v5;
name,v1,v2,v3,v4,v5,v6;
}

so we need a state machine like parser? other than that, we need a way to get the definition of surface



*/



public class SurfaceReader {
    private static final String TAG = "SurfaceReader";

    //Map<String, ShellSurface> table = null;
    boolean error = false;
    String rootPath = null;
    String descPath = null;
    SurfaceManager mgr = null;
    
    public SurfaceReader(SurfaceManager m) {
    	mgr = m;
    }
    
    public SurfaceReader() {

    }

    public SurfaceReader(SurfaceManager m, String shell_root, String desc_path){
	rootPath = shell_root;
	descPath = desc_path;
	mgr = m;
	try {
	    InputStream is = new FileInputStream( new File(desc_path) );
	    parse(is);
	}
	catch(FileNotFoundException e) {error = true;}
	catch(IOException e) {error = true;}

	try {
	    scanFolderForPng(rootPath);
	}
	catch(Exception e) { error = true;}

    }

    public SurfaceReader(File f) {
	try {
	    rootPath = f.getParent();
	    Log.d(TAG, "rootpath = " + rootPath);
	    InputStream is = new FileInputStream(f);
	    parse(is);
	}
	catch(FileNotFoundException e) {}
	catch(IOException e) {}

	try {
	    scanFolderForPng(rootPath);
	}
	catch(Exception e) { error = true;}

    }

    private void scanFolderForPng(String folderPath){
	// need to scan the whole folder for surfaces not listed in the surfaces.txt?
	// add them to surfacemgr as well
	
	File dir = new File(folderPath);
	File[] filez = dir.listFiles(new FilenameFilter(){
		public boolean accept(File dir, String filename) {
		    if ( filename.toLowerCase().endsWith(".png") )
			return true;
		    else
			return false;
		}
	    });
	for ( File f : filez ) {
	    Log.d(TAG, "got " + f.getName() );
	    // need to get id 
	    String fn = f.getName().toLowerCase();
	    Matcher m = PatternHolders.surface_file_scan.matcher(fn);
	    if ( m.matches() == false )
		continue;
	    String idpart = m.group(1);
	    try {
		int id = Integer.parseInt(idpart);
		// we do this to strip out 0s
		idpart = "" + id;
		if ( mgr.containsSurface(idpart) ){
			// check if the surface filename is correct...
			ShellSurface s = mgr.getSurface(idpart);
			if ( f.getAbsolutePath().compareTo(s.selfFilename) != 0){
				Log.d(TAG, "update shell file path to correct filename:"+f.getAbsolutePath());
				//s.selfFilename = f.getAbsolutePath();
				s.updateFilename(f.getAbsolutePath());
			}
		    continue;
		}
		else {
		    mgr.addSurface(idpart, new ShellSurface(folderPath, f.getName(), id, null));
		}
	    }
	    catch(Exception e) {
		continue;
	    }
	}
    }

    long parseTime = 0;


    private int[] getSurfaceIds(String line) {
	if ( line.contains(",") ) {
	    String [] ss = line.split(",");
	    String [] idz = new String[ss.length];
	    int [] id = new int[ss.length];
	    for ( int i = 0; i < ss.length; i++ ) {
		Matcher m = PatternHolders.surface_desc_ptrn.matcher(ss[i]);
		if ( m.matches() ) {
		    idz[i] = m.group(1);
		    id[i] = Integer.parseInt(idz[i]);
		}
	    }
	    return id;

	}
	else {
	    Matcher m = PatternHolders.surface_desc_ptrn.matcher(line);
	    if ( m.find() ) {
		return new int[]{ Integer.parseInt(m.group(1)) };
	    }
	    return null;
	}
    }
    

    private void parse(InputStream is) throws IOException {
	parseTime = SystemClock.uptimeMillis();
// 	if ( table == null )
// 	    table = new HashMap<String, ShellSurface>();

	BufferedReader reader = null;
	try {
	    reader = new BufferedReader(new InputStreamReader(is, Charset.forName("SJIS")));
	}
	catch(Exception e) {
	    Log.d(TAG, "error reading");
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface reading", descPath, 0);
	    return;
	}

	// go through the file
	int lineCount = 0;
	String line = null;
	while(true) {
	    line = reader.readLine();
	    lineCount++;
	    if ( line == null ) // assume readLine() returns null on eof
		break;
	    
	    if ( line.length() == 0 )
		continue;

	    if ( line.startsWith("//") || line.startsWith(",") ) // skip comments
		continue;

	    // if line starts with surface*
	    // check next line for {
	    // then check for }
	    // put all info to create surface
	    if ( line.startsWith("surface") ) {
		int idz[] = getSurfaceIds(line);
		/*
		String id = line.substring(7); // strip off "surface"
		if ( id.length() < 1 ) {
		    Log.d(TAG, "incorrect surface declaration:" + line + " on line " + lineCount);
		    continue; // not a correct surface declearation 
		}
		int sid = -1;
		try {
		    sid = Integer.parseInt(id);
		}
		catch(Exception e) {
		    Log.d(TAG, "incorrect surface declaration:" + line + " on line " + lineCount );
		    continue;
		}
		*/
		if ( idz == null ) {
		    Log.d(TAG, "incorrect surface declaration:" + line + " on line " + lineCount);
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse", descPath, lineCount);
		}

		String nextLine = reader.readLine();
		lineCount++;
		if ( nextLine.equalsIgnoreCase("{" ) ) {
		    List<String> lines = new ArrayList<String>();
		    do {
			nextLine = reader.readLine();
			lineCount++;
			if ( nextLine == null ) {
			    Log.d(TAG, "error not expecting EOF at line:" + lineCount);
			    break;
			}

			if ( nextLine.length() == 0) // skip zero length line
			    continue;

			if ( nextLine.startsWith("}") == false )
			lines.add(nextLine);
		    }
		    while ( nextLine.startsWith("}") == false ) ;
		    
		    for ( int sid : idz ) {
		    ShellSurface surface = new ShellSurface(rootPath, sid, lines);
		    //table.put(id, surface);
		    mgr.addSurface("" + sid, surface);
		    }
		}
		else {
		    Log.d(TAG, "error at line " + lineCount + ", expecting { but got:" + nextLine );
		    break;
		}
	    }
	}
	parseTime = SystemClock.uptimeMillis() - parseTime;
	Log.d(TAG, "parse time:" + parseTime + "ms");
	AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PERF, "parsing time[ms]", descPath, (int)parseTime);
    }

}
