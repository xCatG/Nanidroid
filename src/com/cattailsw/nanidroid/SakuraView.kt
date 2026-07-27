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
import java.util.Collections

/** Retained surface renderer, now Kotlin while preserving Java View contracts. */
open class SakuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : ImageView(context, attrs, defStyle) {
    interface UIEventCallback {
        fun onHit(type: Int, x: Int, y: Int, orientation: Int, collId: Int, buttonId: Int)
        companion object { const val TYPE_SINGLE_CLICK = 0; const val TYPE_DOUBLE_CLICK = 1; const val TYPE_WHEEL = 2; const val TYPE_MOVE = 3 }
    }

    @JvmField var mgr: SurfaceManager? = null
    @JvmField var currentSurfaceId: String? = null
    @JvmField var currentSurface: ShellSurface? = null
    @JvmField var mCtx: Context = context.applicationContext
    @JvmField var animation: AnimationDrawable? = null
    @JvmField var currentAnimationId: String? = null
    @JvmField var mUCB: UIEventCallback? = null

    fun setUiEventCallback(callback: UIEventCallback?) { mUCB = callback }
    fun setMgr(manager: SurfaceManager?) { mgr = manager; currentSurfaceId = null }
    val currentSurfaceDefinition: SurfaceDefinition? get() = currentSurface?.toSurfaceDefinition()
    protected open fun loadSurface(surfaceId: String) { currentSurface = mgr!!.getSakuraSurface(surfaceId) }

    open fun changeSurface(surfaceId: String) {
        if (surfaceId.equals("-1", true)) { visibility = View.INVISIBLE; return }
        if (!surfaceId.equals(currentSurfaceId, true)) try {
            currentSurfaceId = surfaceId; loadSurface(surfaceId)
            setImageDrawable(currentSurface!!.getSurfaceDrawable(mCtx.resources))
            animation = null; currentAnimationId = null
        } catch (e: Exception) {
            var message = mgr!!.ghostId + ":" + currentSurfaceId
            message += ":" + e.message
            AnalyticsUtils.getInstance(mCtx).trackEvent(Setup.ANA_ERR, "surface load", message, -1)
        }
        visibility = View.VISIBLE
    }
    fun hasAnimation() = currentSurface!!.animationCount > 0
    fun loadFirstAvailableAnimation(): Int { val id = currentSurface!!.firstAnimationIndex; loadAnimation(id.toString()); return id }
    open fun loadAnimation(id: String) {
        if (animation == null || !id.equals(currentAnimationId, true)) {
            Log.d(TAG, "loading animation:$id")
            animation = currentSurface!!.getAnimation(id, mCtx.resources, mgr!!) as AnimationDrawable
            currentAnimationId = id
        }
        animation?.let { it.setVisible(true, true); setImageDrawable(it) }
    }
    open fun startAnimation() { animation?.let { it.stop(); it.start() } }
    fun startRarelyAnimation() { startAnimation(ShellSurface.A_TYPE_RARELY); invalidate() }
    fun startSometimesAnimation() { startAnimation(ShellSurface.A_TYPE_SOMETIMES); invalidate() }
    open fun startTalkingAnimation() { startAnimation(ShellSurface.A_TYPE_TALK); invalidate() }
    fun startAnimation(type: Int) {
        val id = currentSurface!!.getAnimationIdByType(type) ?: return
        if (!id.equals(currentAnimationId, true)) loadAnimation(id) else animation?.setVisible(true, true)
        startAnimation()
    }
    fun showCollisionArea() {
        val surface = currentSurface ?: return
        if (surface.collisionCount == 0) return
        try {
            val drawable = surface.getSurfaceDrawable(mCtx.resources) as BitmapDrawable
            val bitmap = drawable.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val paint = Paint().apply { isAntiAlias = true; strokeWidth = 1f; style = Paint.Style.STROKE; color = Color.rgb(254, 0, 1) }
            val canvas = Canvas(bitmap)
            surface.collisionAreas.values.forEach { canvas.drawRect(it.rect, paint) }
            setImageDrawable(BitmapDrawable(bitmap))
        } catch (_: Exception) { }
    }
    fun testColDect(x: Int, y: Int) = findCollisionId(currentSurfaceDefinition, x, y)
    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d(TAG, "onTouchEvent")
        val collisionId = testColDect(event.getX(0).toInt(), event.getY(0).toInt())
        Log.d(TAG, "test col at: $collisionId")
        mUCB?.onHit(UIEventCallback.TYPE_DOUBLE_CLICK, event.getX(0).toInt(), event.getY(0).toInt(), 0, collisionId, 0)
        return super.onTouchEvent(event)
    }
    fun surfaceExercise() = try {
        val keys = mgr!!.getSurfaceKeys().toMutableList(); Collections.sort(keys)
        keys.forEach { key -> changeSurface(key); for (type in ShellSurface.A_TYPE_SOMETIMES until ShellSurface.A_TYPE_LAST) startAnimation(type) }
    } catch (e: Exception) { e.printStackTrace() }
    companion object { private const val TAG = "SakuraView" }
}
