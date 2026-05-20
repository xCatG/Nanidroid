package com.cattailsw.nanidroid

import android.os.SystemClock
import android.util.Log
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.ArrayList

class SurfaceReader {
    companion object {
        private const val TAG = "SurfaceReader"
    }

    var error = false
    var rootPath: String? = null
    var descPath: String? = null
    var mgr: SurfaceManager? = null
    var parseTime: Long = 0

    constructor(m: SurfaceManager) {
        mgr = m
    }

    constructor()

    constructor(m: SurfaceManager, shellRoot: String, descPath: String) {
        rootPath = shellRoot
        this.descPath = descPath
        mgr = m
        try {
            val isStream = FileInputStream(File(descPath))
            parse(isStream)
        } catch (e: FileNotFoundException) {
            error = true
        } catch (e: IOException) {
            error = true
        }

        try {
            scanFolderForPng(rootPath!!)
        } catch (e: Exception) {
            error = true
        }
    }

    constructor(f: File) {
        try {
            rootPath = f.parent
            Log.d(TAG, "rootpath = $rootPath")
            val isStream = FileInputStream(f)
            parse(isStream)
        } catch (e: FileNotFoundException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }

        try {
            scanFolderForPng(rootPath!!)
        } catch (e: Exception) {
            error = true
        }
    }

    private fun scanFolderForPng(folderPath: String) {
        val dir = File(folderPath)
        val filez = dir.listFiles { _, filename ->
            filename.lowercase().endsWith(".png")
        } ?: return

        for (f in filez) {
            Log.d(TAG, "got ${f.name}")
            val fn = f.name.lowercase()
            val m = PatternHolders.surface_file_scan.matcher(fn)
            if (!m.matches()) continue
            val idpart = m.group(1) ?: continue
            try {
                val id = idpart.toInt()
                val normalizedId = id.toString()
                val manager = mgr ?: continue
                if (manager.containsSurface(normalizedId)) {
                    val s = manager.getSurface(normalizedId)
                    if (s != null && f.absolutePath != s.selfFilename) {
                        Log.d(TAG, "update shell file path to correct filename:${f.absolutePath}")
                        s.updateFilename(f.absolutePath)
                    }
                } else {
                    manager.addSurface(normalizedId, ShellSurface(folderPath, f.name, id, null))
                }
            } catch (e: Exception) {
                continue
            }
        }
    }

    private fun getSurfaceIds(line: String): IntArray? {
        return if (line.contains(",")) {
            val ss = line.split(",")
            val idList = ArrayList<Int>()
            for (item in ss) {
                val m = PatternHolders.surface_desc_ptrn.matcher(item.trim())
                if (m.matches()) {
                    val idpart = m.group(1) ?: continue
                    idList.add(idpart.toInt())
                }
            }
            idList.toIntArray()
        } else {
            val m = PatternHolders.surface_desc_ptrn.matcher(line)
            if (m.find()) {
                val idpart = m.group(1) ?: return null
                intArrayOf(idpart.toInt())
            } else {
                null
            }
        }
    }

    private fun parse(isStream: InputStream) {
        parseTime = SystemClock.uptimeMillis()
        val reader = try {
            BufferedReader(InputStreamReader(isStream, Charset.forName("SJIS")))
        } catch (e: Exception) {
            Log.d(TAG, "error reading")
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface reading", descPath ?: "", 0)
            return
        }

        var lineCount = 0
        try {
            while (true) {
                val line = reader.readLine()
                lineCount++
                if (line == null) break
                if (line.isEmpty()) continue
                if (line.startsWith("//") || line.startsWith(",")) continue

                if (line.startsWith("surface")) {
                    val idz = getSurfaceIds(line)
                    if (idz == null) {
                        Log.d(TAG, "incorrect surface declaration:$line on line $lineCount")
                        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse", descPath ?: "", lineCount)
                        continue
                    }

                    var nextLine = reader.readLine()
                    lineCount++
                    if (nextLine != null && nextLine.trim().equals("{", ignoreCase = true)) {
                        val lines = ArrayList<String>()
                        while (true) {
                            nextLine = reader.readLine()
                            lineCount++
                            if (nextLine == null) {
                                Log.d(TAG, "error not expecting EOF at line:$lineCount")
                                break
                            }
                            if (nextLine.isEmpty()) continue
                            if (nextLine.trim().startsWith("}")) {
                                break
                            }
                            lines.add(nextLine)
                        }

                        for (sid in idz) {
                            val surface = ShellSurface(rootPath ?: "", sid, lines)
                            mgr?.addSurface(sid.toString(), surface)
                        }
                    } else {
                        Log.d(TAG, "error at line $lineCount, expecting { but got:$nextLine")
                        break
                    }
                }
            }
        } catch (e: IOException) {
            error = true
        } finally {
            try {
                reader.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        parseTime = SystemClock.uptimeMillis() - parseTime
        Log.d(TAG, "parse time:${parseTime}ms")
        AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_PERF, "parsing time[ms]", descPath ?: "", parseTime.toInt())
    }
}
