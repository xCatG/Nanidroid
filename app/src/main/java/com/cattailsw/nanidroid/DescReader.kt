package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.NarUtil
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Hashtable

class DescReader {
    companion object {
        private val DEF_CHARSET = Charset.forName("Shift_JIS")
        private const val TAG = "DescReader"
    }

    var table: MutableMap<String, String>? = null
    var infilePath: String? = null
    var dbgOutput = false
    var parseTime: Long = 0

    constructor()

    constructor(infile: String) {
        infilePath = infile
    }

    constructor(f: File) {
        try {
            FileInputStream(f).use { isStream ->
                parse(isStream)
            }
        } catch (e: FileNotFoundException) {
            // ignore
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    constructor(isStream: InputStream) {
        try {
            dbgOutput = true
            isStream.use { parse(it) }
        } catch (e: Exception) {
            Log.d(TAG, "parsing inputstream error")
            e.printStackTrace()
        }
    }


    private fun readFirstLineForCharset(br: BufferedReader): Charset {
        var c = DEF_CHARSET
        val line = br.readLine() ?: return c

        var cleanLine = line
        if (cleanLine.startsWith(NarUtil.UTF8_BOM)) {
            cleanLine = cleanLine.substring(1)
        }

        val cs = cleanLine.split(",")
        if (cs.size != 2) return c
        if (!cs[0].contains("charset")) return c
        try {
            c = Charset.forName(cs[1])
        } catch (e: Exception) {
            Log.d(TAG, "trouble charset is:${cs[1]}")
        }
        return c
    }

    private fun parse(isStream: InputStream) {
        if (table == null) {
            table = Hashtable()
        }
        val bytes = isStream.readBytes()
        val defaultReader = BufferedReader(InputStreamReader(bytes.inputStream(), DEF_CHARSET))
        val c = readFirstLineForCharset(defaultReader)
        defaultReader.close()

        val reader = BufferedReader(InputStreamReader(bytes.inputStream(), c))
        readLoop(reader, table!!)
        reader.close()
    }

    private fun readLoop(reader: BufferedReader, table: MutableMap<String, String>) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.indexOf(',') == -1) continue

            val pair = line.split(",")
            if (pair.size != 2) continue
            val label = pair[0]
            val value = pair[1]
            if (dbgOutput) {
                Log.d(TAG, "putting [$label,$value]")
            }
            table[label] = value
        }
    }

    fun parse(): MutableMap<String, String> {
        parseTime = SystemClock.uptimeMillis()
        val ret = Hashtable<String, String>()
        val infile = File(infilePath!!)
        val c = FileInputStream(infile).use { fis ->
            BufferedReader(InputStreamReader(fis, DEF_CHARSET)).use { reader ->
                readFirstLineForCharset(reader)
            }
        }
        FileInputStream(infile).use { fis ->
            BufferedReader(InputStreamReader(fis, c)).use { reader ->
                readLoop(reader, ret)
            }
        }
        parseTime = SystemClock.uptimeMillis() - parseTime
        Log.d(TAG, "parsing took:${parseTime}ms")
        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PERF, "parsing time[ms]", infilePath ?: "", parseTime.toInt())
        return ret
    }
}
