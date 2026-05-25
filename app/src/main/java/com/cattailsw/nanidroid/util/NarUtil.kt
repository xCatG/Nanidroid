package com.cattailsw.nanidroid.util

import android.content.Context
import android.os.Environment
import android.util.Log
import com.cattailsw.nanidroid.DescReader
import com.cattailsw.nanidroid.Setup
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object NarUtil {
    private const val TAG = "NarUtil"
    const val UTF8_BOM = "\uFEFF"

    private val narFilter = FilenameFilter { _, filename ->
        filename.endsWith(".nar") || filename.endsWith(".zip")
    }

    private val zipFilenameCompare = java.util.Comparator<ZipEntry> { lhs, rhs ->
        val ls = lhs.name
        val rs = rhs.name
        if (ls.length == rs.length) {
            ls.compareTo(rs)
        } else if (ls.length > rs.length) {
            1
        } else {
            -1
        }
    }

    @JvmStatic
    fun createNarDirOnSDCard(context: Context) {
        val narDir = context.getExternalFilesDir("nar") ?: return
        if (narDir.exists() && narDir.isDirectory) return

        val success = narDir.mkdirs()
        if (!success) {
            Log.d(TAG, "nar folder creation failed")
        }
    }

    @JvmStatic
    fun listNarDir(context: Context): Array<String>? {
        val narDir = context.getExternalFilesDir("nar") ?: return null
        if (!narDir.exists() || !narDir.isDirectory) return null
        return narDir.list(narFilter)
    }

    @JvmStatic
    fun readNarGhostId(pathToArchive: String): String? {
        var nar: ZipFile? = null
        var ret: String? = null
        try {
            nar = ZipFile(pathToArchive)
            val entries = ArrayList(Collections.list(nar.entries()))
            val e = findRootInstallTxt(entries)
            if (e != null && e.name.contains("install.txt")) {
                val tmp = File.createTempFile("nanidroid", "tmp")
                extractFileToPath(nar, tmp.absolutePath, e, ignorename = true, strip = false)
                val r = DescReader(tmp.absolutePath)
                r.table = r.parse()
                ret = r.table?.get("directory")
                tmp.delete()
            }
            nar.close()
        } catch (e: IOException) {
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_extract", "$pathToArchive:${e.message}", -1)
            e.printStackTrace()
        }
        return ret
    }

    private fun shouldStrip(filename: String): Boolean {
        Log.d(TAG, "check should strip:$filename")
        return !filename.lowercase().startsWith("install.txt")
    }

    private fun findRootInstallTxt(entries: List<ZipEntry>): ZipEntry? {
        val iz = ArrayList<ZipEntry>()
        for (e in entries) {
            if (e.name.contains("install.txt")) {
                iz.add(e)
            }
        }
        if (iz.isEmpty()) return null
        if (iz.size == 1) return iz[0]

        Collections.sort(iz, zipFilenameCompare)
        return iz[0]
    }

    @JvmStatic
    fun readNarArchive(pathToArchive: String, installRoot: String, tid: String?): Boolean {
        var nar: ZipFile? = null
        var ret = false
        try {
            nar = ZipFile(pathToArchive)
            val entries = ArrayList(Collections.list(nar.entries()))
            if (tid == null) {
                Collections.sort(entries, zipFilenameCompare)
                val e = findRootInstallTxt(entries)
                if (e == null) {
                    nar.close()
                    return false
                }
                Log.d(TAG, "entry name=${e.name}")
                val strip = shouldStrip(e.name)
                val tmp = File.createTempFile("nanidroid", "tmp")
                extractFileToPath(nar, tmp.absolutePath, e, ignorename = true, strip = false)
                val r = DescReader(tmp.absolutePath)
                r.table = r.parse()
                tmp.delete()

                val type = r.table?.get("type")
                if (!"ghost".equals(type, ignoreCase = true)) {
                    Log.d(TAG, "do not support $type archive yet")
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_install_not_support", type ?: "null", -2)
                }
                val targetDirid = r.table?.get("directory") ?: "unknown"
                val targetPath = "$installRoot/$targetDirid"
                extractZipToPath(entries, nar, targetPath, strip)
            } else {
                extractZipToPath(entries, nar, "$installRoot/$tid")
            }
            nar.close()
            ret = true
        } catch (e: IOException) {
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "nar_extract", "$tid:${e.message}", -1)
            e.printStackTrace()
            ret = false
        }
        return ret
    }

    private fun extractZipToPath(entries: ArrayList<ZipEntry>, nar: ZipFile, targetPath: String) {
        extractZipToPath(entries, nar, targetPath, false)
    }

    private fun extractZipToPath(entries: ArrayList<ZipEntry>, nar: ZipFile, targetPath: String, strip: Boolean) {
        Log.d(TAG, " =>extracting to$targetPath")
        checkAndMakeDir(targetPath)
        for (e in entries) {
            if (!e.isDirectory) {
                extractFileToPath(nar, targetPath, e, ignorename = false, strip = strip)
            }
        }
    }

    private fun stripExtraLevel(inPath: String): String {
        Log.d(TAG, "inPath is:$inPath")
        val firstSlashIndex = inPath.indexOf('/')
        return if (firstSlashIndex > 0) {
            inPath.substring(firstSlashIndex + 1)
        } else {
            inPath
        }
    }

    private fun extractFileToPath(nar: ZipFile, targetPath: String, e: ZipEntry, ignorename: Boolean, strip: Boolean) {
        val f = if (ignorename) {
            File(targetPath)
        } else {
            File(targetPath + "/" + if (strip) stripExtraLevel(e.name) else e.name)
        }
        val fP = f.parentFile
        if (fP != null && !fP.exists()) {
            val s = fP.mkdirs()
            Log.d(TAG, "fp make$s")
        }
        val os = FileOutputStream(f)
        val isStream = nar.getInputStream(e)
        copyFile(isStream, os)
    }

    private fun checkAndMakeDir(dir: String) {
        val f = File(dir)
        if (!f.isDirectory) {
            Log.d(TAG, " ->creating dir:$dir")
            if (!f.mkdirs()) {
                Log.d(TAG, "failed to create dir")
            }
        }
    }

    @JvmStatic
    fun md5ToString(md5in: ByteArray): String {
        var signature2 = ""
        for (b in md5in) {
            signature2 += Integer.toString((b.toInt() and 0xff) + 0x100, 16).substring(1)
        }
        return signature2
    }

    @JvmStatic
    fun createMD5(isStream: FileInputStream): ByteArray? {
        var digester: MessageDigest? = null
        try {
            digester = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val length = isStream.read(buffer)
                if (length <= 0) break
                digester.update(buffer, 0, length)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                isStream.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        return digester?.digest()
    }

    @JvmStatic
    fun copyFile(isStream: InputStream, os: FileOutputStream): ByteArray? {
        var digester: MessageDigest? = null
        try {
            digester = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val length = isStream.read(buffer)
                if (length <= 0) break
                os.write(buffer, 0, length)
                digester.update(buffer, 0, length)
            }
            os.flush()
            os.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                isStream.close()
            } catch (e: Exception) {
                // ignore
            }
            Log.d(TAG, "done copying")
        }
        return digester?.digest()
    }

    private fun hasUTF8BOM(f: File): Boolean {
        val fis = FileInputStream(f)
        val br = BufferedReader(InputStreamReader(fis, Charset.forName("UTF-8")))
        val s = br.readLine()
        val ret = s != null && s.startsWith(UTF8_BOM)
        br.close()
        return ret
    }

    @JvmStatic
    fun readTxt(f: File): String {
        val sb = java.lang.StringBuilder("<html><body><pre>")
        try {
            val isUTF8Bom = hasUTF8BOM(f)
            val br = BufferedReader(
                InputStreamReader(
                    FileInputStream(f),
                    if (isUTF8Bom) Charset.forName("UTF-8") else Charset.forName("Shift_JIS")
                )
            )
            while (true) {
                val line = br.readLine() ?: break
                sb.append(line)
                sb.append('\n')
            }
            br.close()
        } catch (e: Exception) {
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "readme_error", e.message ?: "null", -1)
        }
        sb.append("</pre></body></html>")
        return sb.toString()
    }
}
