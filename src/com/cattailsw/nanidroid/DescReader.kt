package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.NarUtil
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Hashtable

/** Parses a ghost descriptor using its declared character set. */
class DescReader {
    @JvmField
    var infilePath: String? = null

    @JvmField
    var dbgOutput: Boolean = false

    private var table: MutableMap<String, String>? = null
    private var parseTime: Long = 0

    constructor()

    constructor(infile: String) {
        infilePath = infile
    }

    constructor(file: File) {
        try {
            file.inputStream().use(::parse)
        } catch (_: FileNotFoundException) {
            // Preserve the legacy constructor's absent-file behavior.
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    constructor(input: InputStream) {
        try {
            dbgOutput = true
            parse(input)
        } catch (exception: Exception) {
            Log.d(TAG, "parsing inputstream error")
            exception.printStackTrace()
        }
    }

    fun setDbgOutput(debug: Boolean) {
        dbgOutput = debug
    }

    private fun charsetForFirstLine(firstLine: String?): Charset {
        if (firstLine == null) throw NullPointerException()
        val line = firstLine.removePrefix(NarUtil.UTF8_BOM)
        val charsetFields = line.split(",".toRegex())
        if (charsetFields.size != 2 || !charsetFields[0].contains("charset")) {
            return DEFAULT_CHARSET
        }
        return try {
            Charset.forName(charsetFields[1])
        } catch (_: Exception) {
            Log.d(TAG, "trouble charset is:${charsetFields[1]}")
            DEFAULT_CHARSET
        }
    }

    private fun parse(input: InputStream) {
        if (table == null) table = Hashtable()
        parseBytes(input.readBytes(), table!!)
    }

    private fun parseBytes(bytes: ByteArray, destination: MutableMap<String, String>) {
        if (bytes.isEmpty()) throw NullPointerException()
        val defaultLines = bytes.toString(DEFAULT_CHARSET).lineSequence().toList()
        val charset = charsetForFirstLine(defaultLines.firstOrNull())
        bytes.toString(charset).lineSequence().forEach { line ->
            val pair = line.split(",".toRegex())
            if (pair.size != 2) return@forEach
            if (dbgOutput) Log.d(TAG, "putting [${pair[0]},${pair[1]}]")
            destination[pair[0]] = pair[1]
        }
    }

    fun parse(): MutableMap<String, String> {
        parseTime = SystemClock.uptimeMillis()
        val result = Hashtable<String, String>()
        val path = infilePath ?: throw NullPointerException()
        File(path).inputStream().use { input -> parseBytes(input.readBytes(), result) }
        parseTime = SystemClock.uptimeMillis() - parseTime
        Log.d(TAG, "parsing took:${parseTime}ms")
        AnalyticsUtils.getInstance(null).trackEvent(
            Setup.ANA_PERF,
            "parsing time[ms]",
            infilePath,
            parseTime.toInt(),
        )
        return result
    }

    fun getTable(): MutableMap<String, String>? = table

    fun setTable(table: MutableMap<String, String>?) {
        this.table = table
    }

    private companion object {
        val DEFAULT_CHARSET: Charset = Charset.forName("Shift_JIS")
        const val TAG = "DescReader"
    }
}
