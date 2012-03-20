package com.cattailsw.nanidroid;

import java.util.Map;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashMap;


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

    Map<String, ShellSurface> table = null;
    String rootPath = null;
    public SurfaceReader() {

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
    }

    private void parse(InputStream is) throws IOException {
	if ( table == null )
	    table = new HashMap<String, ShellSurface>();

	BufferedReader reader = null;
	try {
	    reader = new BufferedReader(new InputStreamReader(is, Charset.forName("SJIS")));
	}
	catch(Exception e) {
	    Log.d(TAG, "error reading");
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

		    ShellSurface surface = new ShellSurface(rootPath, sid, lines);
		    table.put(id, surface);
		}
		else {
		    Log.d(TAG, "error at line " + lineCount + ", expecting { but got:" + nextLine );
		    break;
		}
	    }
	}

    }

}
