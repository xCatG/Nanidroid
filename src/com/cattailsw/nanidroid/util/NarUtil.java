package com.cattailsw.nanidroid.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.os.Environment;
import android.util.Log;

import com.cattailsw.nanidroid.DescReader;
import com.cattailsw.nanidroid.Setup;
import java.util.Comparator;
import java.util.List;

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
	    ZipEntry e = findRootInstallTxt(entries);
		// need to find "install.txt"
		if ( e.getName().contains("install.txt")) {
		    File tmp = File.createTempFile("nanidroid", "tmp", new File("/mnt/sdcard/nar"));
		    extractFileToPath(nar, tmp.getAbsolutePath(), e, true, false);
		    DescReader r = new DescReader(tmp.getAbsolutePath());
		    r.setTable(r.parse());

		    ret = r.getTable().get("directory");
		    tmp.delete();
	    }
	    nar.close();

	} catch (IOException e) {
	    // TODO Auto-generated catch block
	    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_extract", pathToArchive + ":" + e.getMessage(), -1);
	    e.printStackTrace();
	}
	    return ret;

    }

    private static boolean shouldStrip(String filename) {
	Log.d(TAG, "check should strip:" + filename);
	return !filename.toLowerCase().startsWith("install.txt");
    }

    // sort by filename length first, then by string compareTo
    private static Comparator<ZipEntry> zipFilenameCompare = new Comparator<ZipEntry>() {
	public int compare(ZipEntry lhs, ZipEntry rhs) {
	    String ls = lhs.getName(); String rs = rhs.getName();
	    if ( ls.length() == rs.length() )
	    return lhs.getName().compareTo(rhs.getName());
	    else if ( ls.length() > rs.length() )
		return 1;
	    return -1;
	}
    };

    private static ZipEntry findRootInstallTxt(List<ZipEntry> entries) {
	List<ZipEntry> iz = new ArrayList<ZipEntry>();
	for ( ZipEntry e: entries ) {
	    if ( e.getName().contains("install.txt") )
		iz.add(e);
	}

	if ( iz.size() == 1 )
	    return iz.get(0);

	//Log.d(TAG, "has " + iz.size() + " install.txts");
	// sort to get shortest one 
	Collections.sort(iz, zipFilenameCompare);
	//Log.d(TAG, "first" + iz.get(0).getName() + ", last" + iz.get(iz.size() -1).getName());
	return iz.get(0);
    }

    public static boolean readNarArchive(String pathToArchive, String installRoot, String tid){
	ZipFile nar = null;
	boolean ret = false;
	try {
	    nar = new ZipFile(pathToArchive);
		
	    ArrayList<ZipEntry> entries = (ArrayList<ZipEntry>) Collections.list(nar.entries());
	    if ( tid == null ) {
		Collections.sort(entries, zipFilenameCompare);
		
		ZipEntry e = findRootInstallTxt(entries);

		Log.d(TAG, "entry name=" + e.getName());
		boolean strip = shouldStrip(e.getName());
		File tmp = File.createTempFile("nanidroid", "tmp", new File("/mnt/sdcard/nar"));
		extractFileToPath(nar, tmp.getAbsolutePath(), e, true, false);
		DescReader r = new DescReader(tmp.getAbsolutePath());
		r.setTable(r.parse());
		tmp.delete();
		String type = r.getTable().get("type");
		if ( "ghost".equalsIgnoreCase(type) == false ) { // do not support non-ghost archive
		    Log.d(TAG, "do not support " + type + " archive yet");
		    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_install_not_support", type, -2);
			
		    //return false;
		}
		String targetDirid = r.getTable().get("directory");
		String targetPath = installRoot + "/" + targetDirid;
		extractZipToPath(entries, nar, targetPath, strip);
	    
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

    private static void extractZipToPath(ArrayList<ZipEntry> entries, ZipFile nar,  String targetPath) throws IOException {
	extractZipToPath(entries, nar, targetPath, false);
    }

    private static void extractZipToPath(ArrayList<ZipEntry> entries, 
					 ZipFile nar,  String targetPath, boolean strip) throws IOException {
	Log.d(TAG, " =>extracting to" + targetPath);
	checkAndMakeDir(targetPath);
	for ( ZipEntry e : entries ) {
	    if ( e.isDirectory() ){
		//checkAndMakeDir(targetPath + e.getName());
	    }
	    else {
		extractFileToPath(nar, targetPath, e, false, strip);
	    }
	}
    }


    private static String stripExtraLevel(String inPath){
	Log.d(TAG, "inPath is:" + inPath);
	// find first '/' in the path and return substring
	int firstSlashIndex = inPath.indexOf('/');
	if ( firstSlashIndex > 0 ) {
	    return inPath.substring(firstSlashIndex+1);
	}
	return inPath;
    }

    private static void extractFileToPath(ZipFile nar, String targetPath, ZipEntry e, boolean ignorename, boolean strip) 
	throws FileNotFoundException, IOException {
	File f = null;
	if ( ignorename ) 
	    f = new File(targetPath);
	else {
	    
	    f = new File(targetPath + "/" +  (strip?stripExtraLevel(e.getName()):e.getName()) );
	}
	File fP = f.getParentFile();
	if ( fP != null && fP.exists() == false ) { boolean s =  fP.mkdirs();
	Log.d(TAG, "fp make" + s);
	}
	FileOutputStream os = new FileOutputStream(f);
	InputStream is = nar.getInputStream(e);
	copyFile(is, os);
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
