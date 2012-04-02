package com.cattailsw.nanidroid;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import android.util.Log;
import java.io.FileOutputStream;
import java.io.File;
import java.security.MessageDigest;

public class NarUtil {
    private static final String TAG = "NarUtil";

    public static String readNarGhostId(String pathToArchive){
	ZipFile nar = null;
	String ret = null;
	try {
	    nar = new ZipFile(pathToArchive);
		
	    ArrayList<ZipEntry> entries = (ArrayList<ZipEntry>) Collections.list(nar.entries());
	    for ( ZipEntry e : entries ) {
		// need to find "install.txt"
		if ( e.getName().contains("install.txt")) {
		    InputStream is = nar.getInputStream(e);
		    DescReader r = new DescReader(is);
		    String type = r.table.get("type");
// 		    if ( type.equalsIgnoreCase("ghost") == false ) { // do not support non-ghost archive
// 			Log.d(TAG, "do not support " + type + " archive yet");
// 			return null;
// 		    }

		    ret = r.table.get("directory");
		    is.close();
		}
	    }
	    nar.close();

	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
	finally {
	    return ret;
	}

    }

    public static void readNarArchive(String pathToArchive, String installRoot){
	ZipFile nar = null;
	try {
	    nar = new ZipFile(pathToArchive);
		
	    ArrayList<ZipEntry> entries = (ArrayList<ZipEntry>) Collections.list(nar.entries());
	    for ( ZipEntry e : entries ) {
		// need to find "install.txt"
		if ( e.getName().contains("install.txt")) {
		    InputStream is = nar.getInputStream(e);
		    DescReader r = new DescReader(is);
		    String type = r.table.get("type");
		    if ( type.equalsIgnoreCase("ghost") == false ) { // do not support non-ghost archive
			Log.d(TAG, "do not support " + type + " archive yet");
			return;
		    }
		    String targetDirid = r.table.get("directory");
		    String targetPath = installRoot + "/" + targetDirid;
		    is.close();
		    extractZipToPath(entries, nar, targetPath);
		}
	    }
	    nar.close();	    
	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	}
	finally {
	}

    }

    private static void extractZipToPath(ArrayList<ZipEntry> entries, 
					 ZipFile nar,  String targetPath) throws IOException {
	Log.d(TAG, " =>extracting to" + targetPath);
	checkAndMakeDir(targetPath);
	for ( ZipEntry e : entries ) {
	    if ( e.isDirectory() ){
		checkAndMakeDir(targetPath + e.getName());
	    }
	    else {
		File f = new File(targetPath + "/" +  e.getName());
		File fP = f.getParentFile();
		if ( fP != null && fP.exists() == false ) fP.mkdirs();
		FileOutputStream os = new FileOutputStream(f);
		InputStream is = nar.getInputStream(e);
		copyFile(is, os);
	    }
	}
    }

    private static void checkAndMakeDir(String dir) {
	File f = new File(dir);
	if ( f.isDirectory() == false ) {
	    Log.d(TAG, " ->creating dir:" + dir);
	    if ( f.mkdirs() == false ) Log.d(TAG, "failed to create dir");
	}
    }

    private static byte[] copyFile(InputStream is, FileOutputStream os) 
	throws IOException{
	MessageDigest digester = null;
	try {
	    digester = MessageDigest.getInstance("MD5");

	    byte[] buffer = new byte[16*1024];
	    int length;
	    while ((length = is.read(buffer)) > 0){
		os.write(buffer, 0, length);
		digester.update(buffer, 0, length);
	    }
	    os.flush();
	    os.close();

	    //os.getFD().sync();
	    //Log.d(TAG, "done copying");
	}
	catch (Exception e){
	    e.printStackTrace();
	}
	finally {
	    is.close();
	    Log.d(TAG, "done copying");
	}
	if ( digester != null ) {
	    byte[] digest = digester.digest();
	    return digest;
	}

	return null;
    };
    

}
