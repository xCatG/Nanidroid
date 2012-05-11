package com.cattailsw.nanidroid.util;

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

import com.cattailsw.nanidroid.DescReader;
import com.cattailsw.nanidroid.Setup;
import com.cattailsw.nanidroid.util.AnalyticsUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.io.FileNotFoundException;
import android.content.Context;
import android.os.Environment;
import java.io.FilenameFilter;

public class NarUtil {
    private static final String TAG = "NarUtil";

    private static final FilenameFilter narFilter = new FilenameFilter() {
	    public boolean accept(File dir, String filename) {
		if ( filename.endsWith(".nar") || filename.endsWith(".zip") )
		    return true;

		return false;
	    }
	};

    public static void createNarDirOnSDCard() {
	File narDir = new File(Environment.getExternalStorageDirectory(), "nar");
	if( narDir.exists() && narDir.isDirectory() )
	    return;

	boolean success = narDir.mkdirs();
	if ( success == false )
	    Log.d(TAG, "nar folder creation failed");
    }

    // should return null if narDir is not a dir
    // should return array of length 0 if no file match?
    /*    public static File[] listNarDir() {
	File narDir = new File(Environment.getExternalStorageDirectory(), "nar");
	if ( narDir.exists() == false || narDir.isDirectory() == false )
	    return null;

	File[] ret = narDir.listFiles(narFilter);
	return ret;
	}*/

    public static String[] listNarDir() {
	File narDir = new File(Environment.getExternalStorageDirectory(), "nar");
	if ( narDir.exists() == false || narDir.isDirectory() == false )
	    return null;

	String[] ret = narDir.list(narFilter);
	return ret;
    }


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

		    ret = r.getTable().get("directory");
		    is.close();
		    break;
		}
	    }
	    nar.close();

	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_extract", pathToArchive + ":" + e.getMessage(), -1);
	    e.printStackTrace();
	}
	    return ret;

    }

    public static boolean readNarArchive(String pathToArchive, String installRoot, String tid){
	ZipFile nar = null;
	boolean ret = false;
	try {
	    nar = new ZipFile(pathToArchive);
		
	    ArrayList<ZipEntry> entries = (ArrayList<ZipEntry>) Collections.list(nar.entries());
	    if ( tid == null ) {
	    for ( ZipEntry e : entries ) {
		// need to find "install.txt"
		if ( e.getName().contains("install.txt")) {
		    InputStream is = nar.getInputStream(e);
		    DescReader r = new DescReader(is);
		    String type = r.getTable().get("type");
		    if ( type.equalsIgnoreCase("ghost") == false ) { // do not support non-ghost archive
			Log.d(TAG, "do not support " + type + " archive yet");
			AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_install_not_support", type, -2);
			
			//return false;
		    }
		    String targetDirid = r.getTable().get("directory");
		    String targetPath = installRoot + "/" + targetDirid;
		    is.close();
		    extractZipToPath(entries, nar, targetPath);
		    break;
		}
	    }
	    }else{
	    	
	    	extractZipToPath(entries, nar, installRoot + "/" + tid);
	    	
	    }
	    nar.close();	
	    ret = true;
	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_extract", tid + ":" + e.getMessage(), -1);
	    e.printStackTrace();
	    ret = false;
	}
	finally {
	}
	return ret;
    }

    private static void extractZipToPath(ArrayList<ZipEntry> entries, 
					 ZipFile nar,  String targetPath) throws IOException {
	Log.d(TAG, " =>extracting to" + targetPath);
	checkAndMakeDir(targetPath);
	for ( ZipEntry e : entries ) {
	    if ( e.isDirectory() ){
		//checkAndMakeDir(targetPath + e.getName());
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

    public static byte[] copyFile(InputStream is, FileOutputStream os) 
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


    public static final String UTF8_BOM = "\uFEFF";

    private static boolean hasUTF8BOM(File f) throws FileNotFoundException, IOException {
	FileInputStream fis = new FileInputStream(f);
	BufferedReader br = new BufferedReader(new InputStreamReader(fis, Charset.forName("UTF-8")));
	String s = br.readLine();
	boolean ret = false;
	if ( s.startsWith(UTF8_BOM) )
	    ret = true;

	br.close();
	return ret;
    }

    public static String readTxt(File f){
	StringBuilder sb = new StringBuilder("<html><body><pre>");
	try {
	    boolean isUTF8Bom = hasUTF8BOM(f);
	    BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f),
									 (isUTF8Bom)?Charset.forName("UTF-8"):
									 Charset.forName("Shift_JIS")));
	    String line;
	    while((line = br.readLine())!= null){
		sb.append(line);
		sb.append('\n');
	    }
	    br.close();
	}
	catch(Exception e){
	    // do nothing
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "readme_error", e.getMessage(), -1);
	}
	sb.append("</pre></body></html>");

	return sb.toString();
    }

}
