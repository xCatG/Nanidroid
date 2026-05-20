package com.cattailsw.nanidroid

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.io.File
import java.util.ArrayList
import java.util.Hashtable
import java.util.TreeSet
import java.util.regex.Matcher

class ShellSurface {
    companion object {
        private const val TAG = "ShellSurface"
        const val S_TYPE_NULL = -1
        const val S_TYPE_BASE = 0
        const val S_TYPE_ELEMENT = 1
        const val TYPE_RESET = -1
        const val TYPE_BASE = 0
        const val TYPE_OVERLAY = 1
        const val TYPE_MOVE = 2

        const val A_TYPE_SOMETIMES = 0
        const val A_TYPE_RARELY = 1
        const val A_TYPE_TALK = 2
        const val A_TYPE_RUNONCE = 3
        const val A_TYPE_YEN_E = 4
        const val A_TYPE_BIND = 5
        const val A_TYPE_RANDOM = 6
        const val A_TYPE_LOOP = 7
        const val A_TYPE_NEVER = 8
        const val A_TYPE_LAST = A_TYPE_NEVER + 1
    }

    var surfaceId: Int = 0
    var origW: Int = 0
    var origH: Int = 0
    var targetW: Int = 0
    var targetH: Int = 0
    var basePath: String? = null
    var selfFilename: String? = null
    var bp2: String? = null
    var surfaceDrawable: Drawable? = null
    var surfaceType: Int = S_TYPE_NULL

    val animationTable = Hashtable<String, Animation>()
    val animationTypeTable = Hashtable<Int, String>()
    val elementList = ArrayList<AnimationFrame>()
    val collisionAreas = Hashtable<Int, CollisionArea>()

    constructor()

    constructor(path: String, selfName: String?, id: Int, elements: List<String>?) {
        surfaceType = S_TYPE_BASE
        surfaceId = id
        basePath = path
        selfFilename = if (selfName == null) {
            path + "surface" + surfaceId + ".png"
        } else {
            path + selfName
        }
        bp2 = String.format("%s%04d%s", path + "surface", surfaceId, ".png")
        loadSurface(elements)
    }

    constructor(path: String, id: Int, elements: List<String>?) : this(path, null, id, elements)

    constructor(path: String, id: Int) : this(path, id, null)

    private fun readBitmapInfo(filename: String): BitmapFactory.Options {
        val opt = BitmapFactory.Options()
        opt.inJustDecodeBounds = true
        BitmapFactory.decodeFile(filename, opt)
        return opt
    }

    private fun loadSurface(elements: List<String>?) {
        if (elements != null) {
            loadElements(elements)
        }
        if (surfaceType == S_TYPE_ELEMENT && elementList.isNotEmpty()) {
            origW = elementList[0].W
            origH = elementList[0].H
            targetW = origW
            targetH = origH
            return
        }

        var self = File(selfFilename!!)
        if (!self.exists()) {
            self = File(bp2!!)
            if (!self.exists()) {
                Log.d(TAG, "loadSurface error: surface$surfaceId not found")
                return
            } else {
                selfFilename = bp2
            }
        }
        updateOrigWH()
    }

    private fun updateOrigWH() {
        val opt = readBitmapInfo(selfFilename!!)
        origW = opt.outWidth
        origH = opt.outHeight

        if (origH == -1 || origW == -1) {
            Log.d(TAG, "surface dimension error for surface$surfaceId")
            return
        } else {
            Log.d(TAG, "surface$surfaceId (w,h)=($origW,$origH)")
        }
        targetW = origW
        targetH = origH
    }

    fun updateFilename(fname: String?) {
        if (fname == null) return
        selfFilename = fname
        if (surfaceType != S_TYPE_ELEMENT) {
            updateOrigWH()
        }
    }

    open inner class Animation {
        var interval: Int = 0
        var id: String = ""
        var exclusive: Boolean = false
        var hasError = false
        var frames: ArrayList<AnimationFrame>? = null
        var animation: AnimationDrawable? = null

        constructor()
        constructor(id: String, interval: Int) {
            this.id = id
            this.interval = interval
        }

        fun addFrame(index: Int, f: AnimationFrame) {
            var list = frames
            if (list == null) {
                list = ArrayList()
                frames = list
            }
            if (list.size <= index) {
                for (i in list.size until index) {
                    list.add(i, f)
                }
            }
            list.add(index, f)
        }

        open fun getAnimation(res: Resources): AnimationDrawable? {
            return getAnimation(res, null)
        }

        open fun getAnimation(res: Resources, mgr: SurfaceManager?): AnimationDrawable? {
            val animeDrawable = animation
            if (animeDrawable != null) return animeDrawable
            if (hasError) return null

            val list = frames ?: return null
            val anime = AnimationDrawable()
            for (i in list.indices) {
                val f = list[i]
                val ad = f.getDrawable(res, mgr)
                if (ad == null) {
                    AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "load animation", "$selfFilename:$id", -100)
                    hasError = true
                    return null
                }
                anime.addFrame(ad, f.time)
            }
            this.animation = anime
            return anime
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("[Animation] id:").append(id).append(if (hasError) "[ERROR]" else "").append(",interval:").append(interval).append("\n")
            sb.append("  => dumping frames:\n")
            val list = frames
            if (list != null) {
                for (f in list) {
                    sb.append(f.toString()).append("\n")
                }
            }
            return sb.toString()
        }
    }

    inner class AltAnimation : Animation {
        var refidz: Array<String>
        var refAnimationz: Array<AnimationDrawable?>

        constructor(id: String, idz: Array<String>) : super() {
            this.id = id
            this.refidz = idz
            this.refAnimationz = arrayOfNulls(refidz.size)
            Log.d(TAG, "alt ani:$id,with ${refidz.size} animations")
        }

        override fun getAnimation(res: Resources): AnimationDrawable? {
            return getAnimation(res, null)
        }

        override fun getAnimation(res: Resources, mgr: SurfaceManager?): AnimationDrawable? {
            val index = (Math.random() * refidz.size).toInt()
            if (refAnimationz[index] == null) {
                val baseAni = animationTable[refidz[index]]
                if (baseAni != null) {
                    refAnimationz[index] = baseAni.getAnimation(res)
                }
            }
            return refAnimationz[index]
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append(super.toString())
            sb.append("  => refidz:")
            for (i in refidz) {
                sb.append(i).append(",")
            }
            sb.append("\n")
            return sb.toString()
        }
    }

    inner class AnimationFrame {
        var sid: String? = null
        var filePath: String? = null
        var time: Int = 0
        var frameType: Int = 0
        var startX: Int = 0
        var startY: Int = 0
        var W: Int = 0
        var H: Int = 0
        var d: Drawable? = null
        var hasError = false

        fun getDrawable(res: Resources): Drawable? {
            return getDrawable(res, null)
        }

        fun getDrawable(res: Resources, mgr: SurfaceManager?): Drawable? {
            val cached = d
            if (cached != null) return cached
            if (hasError) return null

            return when (frameType) {
                TYPE_OVERLAY -> {
                    val dz = arrayOfNulls<Drawable>(2)
                    dz[0] = getSurfaceDrawable(res)
                    val opt = BitmapFactory.Options()
                    opt.inPreferredConfig = Bitmap.Config.ARGB_8888

                    val targetSid = sid
                    val targetPath = filePath
                    if (mgr != null && targetSid != null && mgr.containsSurface(targetSid)) {
                        dz[1] = mgr.getSurfaceDrawable(targetSid, res)
                        val r = mgr.getSurfaceRect(targetSid, res)
                        if (r != null) {
                            W = r.width()
                            H = r.height()
                        } else {
                            W = origW
                            H = origH
                        }
                    } else if (targetPath != null) {
                        dz[1] = loadTransparentBitmapFromFile(targetPath, res, opt)
                    } else {
                        hasError = true
                        return null
                    }

                    val ld = LayerDrawable(dz.filterNotNull().toTypedArray())
                    ld.setLayerInset(1, startX, startY, origW - startX - W, origH - startY - H)
                    d = ld
                    d
                }
                TYPE_RESET -> {
                    getSurfaceDrawable(res)
                }
                TYPE_BASE -> {
                    val opt = BitmapFactory.Options()
                    opt.inPreferredConfig = Bitmap.Config.ARGB_8888
                    val path = filePath
                    if (path != null) {
                        d = loadTransparentBitmapFromFile(path, res, opt)
                    }
                    d
                }
                TYPE_MOVE -> {
                    d = getSurfaceDrawable(res)
                    d
                }
                else -> {
                    d = getSurfaceDrawable(res)
                    d
                }
            }
        }

        override fun toString(): String {
            return "[AnimationFrame] sid=$sid${if (hasError) "[ERROR]" else ""},FType=$frameType,[W,H]=[$W,$H],(startX,startY)=($startX,$startY)"
        }
    }

    inner class CollisionArea(
        var id: Int,
        var startX: Int,
        var startY: Int,
        endX: Int,
        endY: Int,
        var name: String
    ) {
        var W: Int = endX - startX
        var H: Int = endY - startY
        var rect: Rect = Rect(startX, startY, endX, endY)
    }

    private fun findIdInCollision(id: Int): CollisionArea? {
        return collisionAreas[id]
    }

    fun getCollisionCount(): Int {
        return collisionAreas.size
    }

    private fun loadElements(elements: List<String>) {
        Log.d(TAG, "loading ${elements.size} elements")

        for (s in elements) {
            var m = PatternHolders.comment_ptrn.matcher(s)
            if (m.find()) continue

            m = PatternHolders.collision.matcher(s)
            if (m.find()) {
                handleCollision(
                    m.group(1) ?: "",
                    m.group(2) ?: "",
                    m.group(3) ?: "",
                    m.group(4) ?: "",
                    m.group(5) ?: "",
                    m.group(6) ?: ""
                )
                continue
            }

            m = PatternHolders.element.matcher(s)
            if (m.find()) {
                surfaceType = S_TYPE_ELEMENT
                handleElement(
                    m.group(1) ?: "",
                    m.group(2) ?: "",
                    m.group(3) ?: "",
                    m.group(4) ?: "",
                    m.group(5) ?: ""
                )
                continue
            }

            m = PatternHolders.point.matcher(s)
            if (m.find()) continue

            m = PatternHolders.interval.matcher(s)
            if (m.find()) {
                handleInterval(m.group(1) ?: "", m.group(2) ?: "")
                continue
            }
            m = PatternHolders.animation_interval.matcher(s)
            if (m.find()) {
                handleInterval(m.group(1) ?: "", m.group(2) ?: "")
                continue
            }

            m = PatternHolders.pattern.matcher(s)
            if (m.find()) {
                handlePattern(
                    m.group(1) ?: "",
                    m.group(2) ?: "",
                    m.group(3) ?: "",
                    m.group(4) ?: "",
                    m.group(5) ?: "",
                    m.group(6),
                    m.group(7)
                )
                continue
            }

            m = PatternHolders.animation.matcher(s)
            if (m.find()) {
                if (m.group(4) == null) {
                    handlePattern(
                        m.group(1) ?: "",
                        m.group(2) ?: "",
                        m.group(6) ?: "",
                        m.group(7) ?: "",
                        m.group(5) ?: "",
                        m.group(8),
                        m.group(9)
                    )
                } else {
                    Log.d(TAG, "have altstart case, need to do something")
                    addAltAnimation(m.group(1) ?: "", m.group(4) ?: "")
                }
                continue
            }

            m = PatternHolders.animation_base.matcher(s)
            if (m.find()) {
                val filename = m.group(6) ?: m.group(4) ?: ""
                handlePattern(
                    m.group(1) ?: "",
                    m.group(2) ?: "",
                    filename,
                    m.group(7) ?: "",
                    m.group(3) ?: "",
                    null,
                    null
                )
                continue
            }

            m = PatternHolders.pattern_base.matcher(s)
            if (m.find()) {
                printMatch(m)
                handlePattern(
                    m.group(1) ?: "",
                    m.group(2) ?: "",
                    m.group(3) ?: "",
                    m.group(4) ?: "",
                    m.group(5) ?: "",
                    null,
                    null
                )
                continue
            }

            m = PatternHolders.option.matcher(s)
            if (m.find()) {
                handleOptions(m.group() ?: "")
                continue
            }

            m = PatternHolders.pattern_alt.matcher(s)
            if (m.find()) {
                addAltAnimation(m.group(1) ?: "", m.group(3) ?: "", "\\.")
                continue
            }

            Log.d(TAG, "$s matched nothing.")
            try {
                AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse no_match", s, -1)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun handleOptions(s: String) {
        try {
            AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR, "surface parse not support", s, -1)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun handleElement(id: String, method: String, filename: String, startX: String, startY: String) {
        val index = id.toIntOrNull() ?: return
        val x = startX.toIntOrNull() ?: 0
        val y = startY.toIntOrNull() ?: 0

        val fz = basePath + filename
        val file = File(fz)
        if (file.exists()) {
            val opt = readBitmapInfo(fz)
            val f = AnimationFrame()
            f.filePath = fz
            f.frameType = TYPE_BASE
            f.startX = x
            f.startY = y
            f.W = opt.outWidth
            f.H = opt.outHeight

            elementList.add(index, f)
        }
    }

    private fun handleCollision(id: String, startX: String, startY: String, endX: String, endY: String, name: String) {
        try {
            val cid = id.toInt()
            val sx = startX.toInt()
            val sy = startY.toInt()
            val ex = endX.toInt()
            val ey = endY.toInt()

            val ca = findIdInCollision(cid)
            if (ca == null) {
                val newCa = CollisionArea(cid, sx, sy, ex, ey, name)
                collisionAreas.put(cid, newCa)
            } else {
                ca.startX = sx
                ca.startY = sy
                ca.W = ex - sx
                ca.H = ey - sy
                ca.name = name
                ca.rect = Rect(sx, sy, ex, ey)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun lookupInterval(inVal: String): Int {
        return when {
            inVal.equals("sometimes", ignoreCase = true) -> A_TYPE_SOMETIMES
            inVal.equals("rarely", ignoreCase = true) -> A_TYPE_RARELY
            inVal.equals("random", ignoreCase = true) -> A_TYPE_RANDOM
            inVal.equals("always", ignoreCase = true) -> A_TYPE_LOOP
            inVal.equals("yen-e", ignoreCase = true) -> A_TYPE_YEN_E
            inVal.equals("talk", ignoreCase = true) -> A_TYPE_TALK
            inVal.equals("runonce", ignoreCase = true) -> A_TYPE_RUNONCE
            inVal.equals("never", ignoreCase = true) -> A_TYPE_NEVER
            inVal.equals("bind", ignoreCase = true) -> A_TYPE_BIND
            else -> 0
        }
    }

    private fun handleInterval(id: String, interval: String) {
        val valInterval = lookupInterval(interval)

        if (animationTable.containsKey(id)) {
            animationTable[id]?.interval = valInterval
        } else {
            animationTable[id] = Animation(id, valInterval)
            if (animationTypeTable.containsKey(valInterval)) {
                val exist = animationTypeTable[valInterval] ?: ""
                animationTypeTable[valInterval] = "$exist,$id"
            } else {
                animationTypeTable[valInterval] = id
            }
        }
    }

    private fun lookupPatternType(type: String): Int {
        return when {
            type.equals("base", ignoreCase = true) -> TYPE_BASE
            type.equals("overlay", ignoreCase = true) || type.equals("overlayfast", ignoreCase = true) -> TYPE_OVERLAY
            type.equals("move", ignoreCase = true) -> TYPE_MOVE
            else -> TYPE_BASE
        }
    }

    private fun addAltAnimation(aId: String, refidz: String, splitter: String) {
        val ridz = refidz.split(splitter.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        addAltAnimation(aId, ridz)
    }

    private fun addAltAnimation(aId: String, refidz: String) {
        val ridz = refidz.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        addAltAnimation(aId, ridz)
    }

    private fun addAltAnimation(aId: String, ridz: Array<String>) {
        val a = AltAnimation(aId, ridz)
        val baseAni = animationTable[aId]
        if (baseAni != null) {
            a.interval = baseAni.interval
        }
        animationTable.put(aId, a)
    }

    private fun addFrameToAnimation(aId: String, index: Int, frame: AnimationFrame) {
        var a = animationTable[aId]
        if (a == null) {
            a = Animation()
            a.id = aId
            animationTable[aId] = a
        }
        a.addFrame(index, frame)
    }

    private fun handlePattern(
        animationId: String,
        seq: String,
        filename: String,
        waitTime: String,
        pattern: String,
        x_in: String?,
        y_in: String?
    ) {
        val index = seq.toIntOrNull() ?: -1
        val wait = waitTime.toIntOrNull() ?: -1
        val x = x_in?.toIntOrNull() ?: -1
        val y = y_in?.toIntOrNull() ?: -1
        val findex = filename.toIntOrNull() ?: -1

        if (filename.equals("-1", ignoreCase = true)) {
            val f = AnimationFrame()
            f.frameType = TYPE_RESET
            f.time = wait
            addFrameToAnimation(animationId, index, f)
        } else {
            val fz = basePath + "surface" + filename + ".png"
            val file = File(fz)
            if (file.exists()) {
                val opt = readBitmapInfo(fz)
                val f = AnimationFrame()
                f.sid = findex.toString()
                f.filePath = fz
                f.frameType = lookupPatternType(pattern)
                f.time = wait
                f.startX = x
                f.startY = y
                f.W = opt.outWidth
                f.H = opt.outHeight

                addFrameToAnimation(animationId, index, f)
            } else {
                val f = AnimationFrame()
                f.sid = findex.toString()
                f.startX = x
                f.startY = y
                f.frameType = lookupPatternType(pattern)
                addFrameToAnimation(animationId, index, f)
            }
        }
    }

    private fun handleOption(id: String, option: String) {
        val exclusive = option.equals("exclusive", ignoreCase = true)
        val a = animationTable[id]
        if (a != null) {
            a.exclusive = exclusive
        } else {
            val newA = Animation()
            newA.exclusive = exclusive
            newA.id = id
            animationTable[id] = newA
        }
    }

    private fun printMatch(m: Matcher) {
        Log.d(TAG, "matcher has ${m.groupCount()} groups")
        for (i in 0 until m.groupCount()) {
            Log.d(TAG, "($i) => ${m.group(i)}")
        }
        try {
            Log.d(TAG, "(${m.groupCount()}) => ${m.group(m.groupCount())}")
        } catch (e: Exception) {
            // ignore
        }
    }

    fun resize(height: Int, width: Int) {
        targetW = width
        targetH = height
    }

    fun getSurfaceDrawable(res: Resources): Drawable? {
        val cached = surfaceDrawable
        if (cached != null) return cached

        return when (surfaceType) {
            S_TYPE_BASE -> {
                val opt = BitmapFactory.Options()
                if (origH != targetH) opt.outHeight = targetH
                if (origW != targetW) opt.outWidth = targetW
                surfaceDrawable = loadTransparentBitmapFromFile(selfFilename!!, res, opt)
                surfaceDrawable
            }
            S_TYPE_NULL -> null
            else -> {
                val layerCount = elementList.size
                val dz = arrayOfNulls<Drawable>(layerCount)
                for (i in 0 until layerCount) {
                    dz[i] = elementList[i].getDrawable(res)
                }
                val ld = LayerDrawable(dz.filterNotNull().toTypedArray())
                val oW = elementList[0].W
                val oH = elementList[0].H
                for (j in 1 until layerCount) {
                    val el = elementList[j]
                    ld.setLayerInset(j, el.startX, el.startY, oW - el.startX - el.W, oH - el.startY - el.H)
                }
                surfaceDrawable = ld
                surfaceDrawable
            }
        }
    }

    private fun loadTransparentBitmapFromFile(filename: String, res: Resources, opt: BitmapFactory.Options): Drawable? {
        Log.d(TAG, "loading $filename")
        val bmp = BitmapFactory.decodeFile(filename, opt) ?: return null
        val colorVal = bmp.getPixel(0, 0)
        return BitmapDrawable(res, createTransparentBmp(bmp, colorVal))
    }

    private fun createTransparentBmp(bitmap: Bitmap?, replaceThisColor: Int): Bitmap? {
        if (bitmap != null) {
            val picw = bitmap.width
            val pich = bitmap.height
            val pix = IntArray(picw * pich)
            bitmap.getPixels(pix, 0, picw, 0, 0, picw, pich)
            for (y in 0 until pich) {
                val startY = y * picw
                for (x in 0 until picw) {
                    val index = startY + x
                    if (pix[index] == replaceThisColor) {
                        pix[index] = Color.TRANSPARENT
                    }
                }
            }
            return Bitmap.createBitmap(pix, picw, pich, Bitmap.Config.ARGB_8888)
        } else {
            return null
        }
    }

    fun getFirstAnimationIndex(): Int {
        if (animationTable.isEmpty()) return -1
        val key = TreeSet(animationTable.keys)
        var first = 0
        try {
            first = key.first().toInt()
        } catch (e: Exception) {
            // ignore
        }
        return first
    }

    fun getAnimation(id: String, res: Resources): Drawable? {
        return getAnimation(id, res, null)
    }

    fun getAnimation(id: String, res: Resources, mgr: SurfaceManager?): Drawable? {
        return if (animationTable.containsKey(id)) {
            animationTable[id]?.getAnimation(res, mgr)
        } else {
            null
        }
    }

    fun getAnimation(patternId: Int, res: Resources): Drawable? {
        if (animationTable.size < patternId && !animationTable.containsKey(patternId.toString())) {
            return null
        }
        val pid = patternId.toString()
        return getAnimation(pid, res)
    }

    private fun getAnimationFrame(patternId: String, frameIndex: Int): AnimationFrame? {
        return animationTable[patternId]?.frames?.get(frameIndex)
    }

    fun getAnimationFrequency(patternId: Int): Int {
        if (animationTable.size < patternId) return -1
        return animationTable[patternId.toString()]?.interval ?: 0
    }

    fun getAnimationFrameCount(patternId: Int): Int {
        if (animationTable.size < patternId) return 0
        return animationTable[patternId.toString()]?.frames?.size ?: 0
    }

    fun getAnimationCount(): Int {
        return animationTable.size
    }

    fun getAnimationIdByType(type: Int): String? {
        if (animationTypeTable.containsKey(type)) {
            val idstring = animationTypeTable[type] ?: return null
            val idz = idstring.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            return if (idz.size == 1) {
                idstring
            } else {
                idz[(Math.random() * idz.size).toInt()]
            }
        }
        return null
    }

    var dim: Rect? = null
    fun getSurfaceDim(): Rect {
        var d = dim
        if (d == null) {
            d = Rect(0, 0, origW, origH)
            dim = d
        }
        return d
    }

    fun dumpSurfaceStat(): String {
        val sb = StringBuilder()
        sb.append("==================\n")
        dumpStat(sb)
        dumpElementList(sb)
        dumpAnimation(sb)
        sb.append("==================\n")
        return sb.toString()
    }

    fun dumpAnimation(sb: StringBuilder): StringBuilder {
        sb.append("[dumping animation]\n")
        for (k in animationTable.keys) {
            sb.append(animationTable[k]?.toString() ?: "")
        }

        sb.append("[dumping animation type table]\n")
        for (i in animationTypeTable.keys) {
            sb.append(animationTypeTable[i]).append("\n")
        }
        sb.append("[done dumping animation type table]\n")
        return sb
    }

    fun dumpStat(sb: StringBuilder): StringBuilder {
        sb.append("[dumping surface status]")
        sb.append("surface id:").append(surfaceId).append("\n")
        sb.append("surface type:").append(surfaceType).append("\n")
        sb.append("surface filename:").append(selfFilename).append("\n")
        sb.append("[origW,origH]:[").append(origW).append(",").append(origH).append("]\n")
        sb.append("animation count:").append(animationTable.size).append("\n")
        return sb
    }

    fun dumpElementList(sb: StringBuilder): StringBuilder {
        if (elementList.isNotEmpty()) {
            sb.append("[dumping element list]\n")
            for (e in elementList) {
                sb.append(e.toString()).append("\n")
            }
            sb.append("[end of element list]\n")
        }
        return sb
    }
}
