package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/** Kotlin parser and filesystem publisher for a shell's `surfaces.txt` file. */
class SurfaceReader {
    @JvmField var error = false
    private var rootPath: String? = null
    private var descPath: String? = null
    private var manager: SurfaceManager? = null
    private var parseTime = 0L

    constructor(manager: SurfaceManager) {
        this.manager = manager
    }

    constructor()

    constructor(manager: SurfaceManager, shellRoot: String, descriptorPath: String) {
        rootPath = shellRoot
        descPath = descriptorPath
        this.manager = manager
        try {
            FileInputStream(File(descriptorPath)).use(::parse)
        } catch (_: FileNotFoundException) {
            error = true
        } catch (_: IOException) {
            error = true
        }
        try {
            scanFolderForPng(rootPath!!)
        } catch (_: Exception) {
            error = true
        }
    }

    constructor(file: File) {
        try {
            rootPath = file.parent
            Log.d(TAG, "rootpath = $rootPath")
            FileInputStream(file).use(::parse)
        } catch (_: FileNotFoundException) {
            // Legacy behavior: parsing errors here do not set the reader error flag.
        } catch (_: IOException) {
            // Legacy behavior: parsing errors here do not set the reader error flag.
        }
        try {
            scanFolderForPng(rootPath!!)
        } catch (_: Exception) {
            error = true
        }
    }

    private fun scanFolderForPng(folderPath: String) {
        val files = File(folderPath).listFiles(FilenameFilter { _, filename ->
            filename.lowercase().endsWith(".png")
        })!!
        for (file in files) {
            Log.d(TAG, "got ${file.name}")
            val match = PatternHolders.surface_file_scan.matcher(file.name.lowercase())
            if (!match.matches()) continue
            var idPart = match.group(1)!!
            try {
                val id = idPart.toInt()
                idPart = id.toString()
                val catalog = manager!!
                if (catalog.containsSurface(idPart)) {
                    val surface = catalog.getSurface(idPart)!!
                    if (file.absolutePath != surface.selfFilename) {
                        Log.d(TAG, "update shell file path to correct filename:${file.absolutePath}")
                        surface.updateFilename(file.absolutePath)
                    }
                } else {
                    catalog.addSurface(idPart, ShellSurface(folderPath, file.name, id, null))
                }
            } catch (_: Exception) {
                continue
            }
        }
    }

    private fun getSurfaceIds(line: String): IntArray? {
        if (line.contains(",")) {
            return line.split(",").map { token ->
                val match = PatternHolders.surface_desc_ptrn.matcher(token)
                if (match.matches()) match.group(1)!!.toInt() else 0
            }.toIntArray()
        }
        val match = PatternHolders.surface_desc_ptrn.matcher(line)
        return if (match.find()) intArrayOf(match.group(1)!!.toInt()) else null
    }

    @Throws(IOException::class)
    private fun parse(input: InputStream) {
        parseTime = SystemClock.uptimeMillis()
        val reader = try {
            BufferedReader(InputStreamReader(input, Charset.forName("SJIS")))
        } catch (_: Exception) {
            Log.d(TAG, "error reading")
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface reading", descPath, 0)
            return
        }

        var lineCount = 0
        while (true) {
            val line = reader.readLine()
            lineCount++
            if (line == null) break
            if (line.isEmpty() || line.startsWith("//") || line.startsWith(",")) continue
            if (!line.startsWith("surface")) continue

            val ids = getSurfaceIds(line)
            if (ids == null) {
                Log.d(TAG, "incorrect surface declaration:$line on line $lineCount")
                AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse", descPath, lineCount)
            }

            var nextLine = reader.readLine()
            lineCount++
            if (nextLine.equals("{", ignoreCase = true)) {
                val entries = mutableListOf<String>()
                do {
                    nextLine = reader.readLine()
                    lineCount++
                    val current = nextLine
                    if (current == null) {
                        Log.d(TAG, "error not expecting EOF at line:$lineCount")
                        break
                    }
                    if (current.isEmpty()) continue
                    if (!current.startsWith("}")) entries += current
                } while (!nextLine.startsWith("}"))

                for (id in ids!!) {
                    manager!!.addSurface(id.toString(), ShellSurface(rootPath ?: "", id, entries))
                }
            } else {
                Log.d(TAG, "error at line $lineCount, expecting { but got:$nextLine")
                break
            }
        }
        parseTime = SystemClock.uptimeMillis() - parseTime
        Log.d(TAG, "parse time:${parseTime}ms")
        AnalyticsUtils.getInstance(null).trackEvent(
            Setup.ANA_PERF,
            "parsing time[ms]",
            descPath,
            parseTime.toInt(),
        )
    }

    private companion object {
        const val TAG = "SurfaceReader"
    }
}
