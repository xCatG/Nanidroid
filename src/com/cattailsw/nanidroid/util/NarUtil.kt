package com.cattailsw.nanidroid.util

import android.os.Environment
import android.util.Log
import com.cattailsw.nanidroid.DescReader
import com.cattailsw.nanidroid.Setup
import java.io.*
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class NarUtil private constructor() {
    companion object {
        const val UTF8_BOM = "\uFEFF"; private const val TAG = "NarUtil"
        @JvmStatic fun createNarDirOnSDCard() { val d=File(Environment.getExternalStorageDirectory(),"nar"); if (!(d.exists()&&d.isDirectory) && !d.mkdirs()) Log.d(TAG,"nar folder creation failed") }
        @JvmStatic fun listNarDir():Array<String>? { val d=File(Environment.getExternalStorageDirectory(),"nar"); return if (!d.exists()||!d.isDirectory) null else d.list { _, n -> n.endsWith(".nar")||n.endsWith(".zip") } }
        @JvmStatic fun readNarGhostId(path:String):String?=try { ZipFile(path).use { z -> val e=findRootInstallTxt(z.entries().toList()); val t=File.createTempFile("nanidroid","tmp",File("/mnt/sdcard/nar")); extractFileToPath(z,t.path,e,true,false); val r=DescReader(t.path);r.setTable(r.parse());val v=r.getTable()!!["directory"];t.delete();v } } catch(e:IOException){ AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR,"nar_extract","$path:${e.message}",-1);e.printStackTrace();null }
        @JvmStatic fun readNarArchive(path:String, root:String, tid:String?):Boolean=try { ZipFile(path).use { z -> val es=z.entries().toList(); if(tid==null){ val e=findRootInstallTxt(es); val strip=!e.name.lowercase().startsWith("install.txt"); val t=File.createTempFile("nanidroid","tmp",File("/mnt/sdcard/nar"));extractFileToPath(z,t.path,e,true,false);val r=DescReader(t.path);r.setTable(r.parse());t.delete();extractZipToPath(es,z,"$root/${r.getTable()!!["directory"]}",strip) }else extractZipToPath(es,z,"$root/$tid",false) };true }catch(e:IOException){AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR,"nar_extract","$tid:${e.message}",-1);e.printStackTrace();false}
        private fun findRootInstallTxt(es:List<ZipEntry>):ZipEntry=es.filter{it.name.contains("install.txt")}.sortedWith(compareBy<ZipEntry>{it.name.length}.thenBy{it.name})[0]
        private fun extractZipToPath(es:List<ZipEntry>,z:ZipFile,path:String,strip:Boolean){File(path).mkdirs();es.filter{!it.isDirectory}.forEach{extractFileToPath(z,path,it,false,strip)}}
        private fun extractFileToPath(z:ZipFile,path:String,e:ZipEntry,ignore:Boolean,strip:Boolean){val f=File(if(ignore)path else "$path/${if(strip)stripExtraLevel(e.name)else e.name}");f.parentFile?.mkdirs();FileOutputStream(f).use{o->z.getInputStream(e).use{i->copyFile(i,o)}}}
        private fun stripExtraLevel(s:String)=s.indexOf('/').takeIf{it>0}?.let{s.substring(it+1)}?:s
        @JvmStatic fun md5ToString(v:ByteArray):String=v.joinToString(""){Integer.toString((it.toInt()and 255)+256,16).substring(1)}
        @JvmStatic fun createMD5(i:FileInputStream):ByteArray=try{MessageDigest.getInstance("MD5").also{d->val b=ByteArray(16384);while(true){val n=i.read(b);if(n<=0)break;d.update(b,0,n)}}.digest()}catch(e:Exception){e.printStackTrace();throw NullPointerException()}finally{i.close()}
        @JvmStatic fun copyFile(i:InputStream,o:FileOutputStream):ByteArray=try{MessageDigest.getInstance("MD5").also{d->val b=ByteArray(16384);while(true){val n=i.read(b);if(n<=0)break;o.write(b,0,n);d.update(b,0,n)};o.flush()}.digest()}catch(e:Exception){e.printStackTrace();throw NullPointerException()}finally{i.close();o.close()}
        private fun hasUTF8BOM(f:File)=BufferedReader(InputStreamReader(FileInputStream(f),Charset.forName("UTF-8"))).use{it.readLine().startsWith(UTF8_BOM)}
        @JvmStatic fun readTxt(f:File):String { val s=StringBuilder("<html><body><pre>");try{BufferedReader(InputStreamReader(FileInputStream(f),if(hasUTF8BOM(f))Charset.forName("UTF-8")else Charset.forName("Shift_JIS"))).use{r->r.lineSequence().forEach{s.append(it).append('\n')}}}catch(e:Exception){AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR,"readme_error",e.message,-1)};return s.append("</pre></body></html>").toString() }
    }
}
