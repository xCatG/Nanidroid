package com.cattailsw.nanidroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.util.ArrayList
import java.util.Collections

open class SakuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SakuraView"
    }

    interface UIEventCallback {
        companion object {
            const val TYPE_SINGLE_CLICK = 0
            const val TYPE_DOUBLE_CLICK = 1
            const val TYPE_WHEEL = 2
            const val TYPE_MOVE = 3
        }

        fun onHit(type: Int, x: Int, y: Int, orientation: Int, collId: Int, buttonId: Int)
    }

    var mgr: SurfaceManager? = null
        set(value) {
            field = value
            currentSurfaceId = null
        }
    var currentSurfaceId: String? = null
    var currentSurface: ShellSurface? = null
    val mCtx: Context = context.applicationContext
    var animation: AnimationDrawable? = null
    var currentAnimationId: String? = null
    var mUCB: UIEventCallback? = null

    private var colRz: Array<Rect>? = null
    private var colKeyz: IntArray? = null

    fun setUiEventCallback(cb: UIEventCallback?) {
        mUCB = cb
    }


    protected open fun loadSurface(sid: String) {
        currentSurface = mgr?.getSakuraSurface(sid)
    }

    open fun changeSurface(surfaceid: String) {
        if (surfaceid.equals("-1", ignoreCase = true)) {
            visibility = View.INVISIBLE
            return
        }

        if (!surfaceid.equals(currentSurfaceId, ignoreCase = true)) {
            try {
                currentSurfaceId = surfaceid
                loadSurface(surfaceid)
                val surf = currentSurface
                if (surf != null) {
                    setImageDrawable(surf.getSurfaceDrawable(mCtx.resources))
                    animation = null
                    currentAnimationId = null
                    populateColRz()
                }
            } catch (e: Exception) {
                val msg = "${mgr?.ghostId ?: ""}:$currentSurfaceId:${e.message ?: ""}"
                AnalyticsUtils.getInstance(mCtx).trackEvent(Setup.ANA_ERR, "surface load", msg, -1)
            }
        }
        visibility = View.VISIBLE
    }

    fun hasAnimation(): Boolean {
        return (currentSurface?.getAnimationCount() ?: 0) > 0
    }

    fun loadFirstAvailableAnimation(): Int {
        val surf = currentSurface ?: return -1
        val animeIndex = surf.getFirstAnimationIndex()
        loadAnimation(animeIndex.toString())
        return animeIndex
    }

    open fun loadAnimation(id: String) {
        val surf = currentSurface ?: return
        val manager = mgr ?: return
        if (animation == null || !id.equals(currentAnimationId, ignoreCase = true)) {
            Log.d(TAG, "loading animation:$id")
            animation = surf.getAnimation(id, mCtx.resources, manager) as? AnimationDrawable
            currentAnimationId = id
        }
        val anim = animation
        if (anim != null) {
            anim.setVisible(true, true)
            setImageDrawable(anim)
        }
    }

    open fun startAnimation() {
        val anim = animation ?: return
        anim.stop()
        anim.start()
    }

    fun startRarelyAnimation() {
        startAnimation(ShellSurface.A_TYPE_RARELY)
        invalidate()
    }

    fun startSometimesAnimation() {
        startAnimation(ShellSurface.A_TYPE_SOMETIMES)
        invalidate()
    }

    open fun startTalkingAnimation() {
        startAnimation(ShellSurface.A_TYPE_TALK)
        invalidate()
    }

    fun startAnimation(type: Int) {
        val surf = currentSurface ?: return
        val id = surf.getAnimationIdByType(type) ?: return

        if (!id.equals(currentAnimationId, ignoreCase = true)) {
            loadAnimation(id)
        } else {
            animation?.setVisible(true, true)
        }

        startAnimation()
    }

    private fun populateColRz() {
        val surf = currentSurface ?: return
        val colSize = surf.getCollisionCount()
        if (colSize == 0) {
            colRz = null
            colKeyz = null
            return
        }

        colRz = Array(colSize) { Rect() }
        colKeyz = IntArray(colSize)

        val colKey = surf.collisionAreas.keys
        var i = 0
        for (k in colKey) {
            val area = surf.collisionAreas[k]
            if (area != null) {
                colRz!![i] = area.rect
                colKeyz!![i] = k
            }
            i++
        }
    }

    fun showCollisionArea() {
        val surf = currentSurface ?: return
        val colsize = surf.getCollisionCount()
        if (colsize == 0) return
        val rz = Array(colsize) { Rect() }
        val colKey = surf.collisionAreas.keys
        var i = 0
        for (k in colKey) {
            val area = surf.collisionAreas[k]
            if (area != null) {
                rz[i] = area.rect
            }
            i++
        }

        try {
            val b = surf.getSurfaceDrawable(mCtx.resources) as BitmapDrawable
            val bmpcopy = b.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(bmpcopy)
            val p = Paint().apply {
                isAntiAlias = true
                strokeWidth = 1f
                style = Paint.Style.STROKE
                color = Color.rgb(254, 0, 1)
            }

            for (re in rz) {
                c.drawRect(re, p)
            }

            val nb = BitmapDrawable(mCtx.resources, bmpcopy)
            setImageDrawable(nb)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun testColDect(x: Int, y: Int): Int {
        val rz = colRz ?: return -1
        val kz = colKeyz ?: return -1

        for (i in rz.indices) {
            if (rz[i].contains(x, y)) {
                return kz[i]
            }
        }

        return -1
    }

    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        Log.d(TAG, "onTouchEvent")
        val x = motionEvent.getX(0)
        val y = motionEvent.getY(0)

        val origW = currentSurface?.origW ?: 200
        val origH = currentSurface?.origH ?: 300

        val scaleX = if (width > 0) width.toFloat() / origW else 1.0f
        val scaleY = if (height > 0) height.toFloat() / origH else 1.0f

        val origX = (x / scaleX).toInt()
        val origY = (y / scaleY).toInt()

        val cid = testColDect(origX, origY)
        Log.d(TAG, "onTouchEvent: raw(${x.toInt()}, ${y.toInt()}) -> mapped($origX, $origY), col at: $cid")

        mUCB?.onHit(
            UIEventCallback.TYPE_DOUBLE_CLICK,
            origX,
            origY,
            0,
            cid,
            0
        )
        return super.onTouchEvent(motionEvent)
    }

    fun surfaceExercise() {
        val manager = mgr ?: return
        try {
            val keyz = ArrayList(manager.getSurfaceKeys())
            Collections.sort(keyz)
            for (s in keyz) {
                changeSurface(s)
                for (i in ShellSurface.A_TYPE_SOMETIMES until ShellSurface.A_TYPE_LAST) {
                    startAnimation(i)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
