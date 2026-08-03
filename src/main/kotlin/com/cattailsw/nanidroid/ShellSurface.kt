package com.cattailsw.nanidroid

import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.res.Resources
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import java.io.File
import com.cattailsw.nanidroid.util.AnalyticsUtils
import java.util.TreeMap

/** Kotlin transcription staging source for the legacy shell-surface parser. */
open class ShellSurface {
    companion object {
        const val S_TYPE_NULL = -1; const val S_TYPE_BASE = 0; const val S_TYPE_ELEMENT = 1
        const val TYPE_RESET = -1; const val TYPE_BASE = 0; const val TYPE_OVERLAY = 1; const val TYPE_MOVE = 2
        const val A_TYPE_SOMETIMES = 0; const val A_TYPE_RARELY = 1; const val A_TYPE_TALK = 2; const val A_TYPE_RUNONCE = 3
        const val A_TYPE_YEN_E = 4; const val A_TYPE_BIND = 5; const val A_TYPE_RANDOM = 6; const val A_TYPE_LOOP = 7
        const val A_TYPE_NEVER = 8; const val A_TYPE_LAST = 9
        internal const val MAX_ANIMATION_PATTERN_ID = 4_095
    }
    @JvmField var surfaceId = 0; @JvmField var origW = 0; @JvmField var origH = 0; @JvmField var targetW = 0; @JvmField var targetH = 0
    @JvmField var basePath: String? = null; @JvmField var selfFilename: String? = null; @JvmField var bp2: String? = null
    var surfaceDrawable: Drawable? = null; var surfaceType = S_TYPE_NULL
    @JvmField var animationTable: MutableMap<String, Animation>? = null
    @JvmField var animationTypeTable: MutableMap<Int, String>? = null
    var elementList: MutableList<AnimationFrame>? = null
    @JvmField var collisionAreas: MutableMap<Int, CollisionArea> = TreeMap()
    var dim: Rect? = null

    constructor()
    constructor(path: String, selfName: String?, id: Int, elements: List<String?>?) { surfaceType = S_TYPE_BASE; basePath = path; surfaceId = id; selfFilename = selfName?.let { path + it } ?: "${path}surface$id.png"; bp2 = "%ssurface%04d.png".format(path, id); loadSurface(elements) }
    internal constructor(path: String, selfName: String?, id: Int, elements: List<String?>?, probeBitmap: Boolean) { surfaceType = S_TYPE_BASE; basePath = path; surfaceId = id; selfFilename = selfName?.let { path + it } ?: "${path}surface$id.png"; bp2 = "%ssurface%04d.png".format(path, id); loadSurface(elements, probeBitmap) }
    constructor(path: String, id: Int, elements: List<String?>?) : this(path, null, id, elements)
    constructor(path: String, id: Int?, elements: List<String?>?) : this(path, id ?: 0, elements)
    constructor(path: String, id: Int) : this(path, id, null)

    open inner class Animation { @JvmField var interval = 0; @JvmField var id: String = ""; @JvmField var exclusive = false; @JvmField var hasError = false; @JvmField var frames: MutableList<AnimationFrame>? = null; @JvmField var animation: AnimationDrawable? = null
        private val indexedFrames = TreeMap<Int, MutableList<AnimationFrame>>()
        private var framesDirty = false
        constructor(); constructor(id: String, interval: Int) { this.id=id; this.interval=interval }
        fun addFrame(index: Int, frame: AnimationFrame) { if(index !in 0..MAX_ANIMATION_PATTERN_ID)return;indexedFrames.getOrPut(index){mutableListOf()}.add(frame);framesDirty=true;animation=null }
        fun materializeFrames() { if(!framesDirty)return;frames=ArrayList<AnimationFrame>().also { result->indexedFrames.values.forEach(result::addAll) };framesDirty=false }
        open fun getAnimation(res:Resources, mgr:SurfaceManager?=null):AnimationDrawable? { animation?.let{return it};if(hasError)return null;materializeFrames();val result=AnimationDrawable();for(frame in frames.orEmpty()){val drawable=frame.getDrawable(res,mgr) ?: run {hasError=true;return null};result.addFrame(drawable,frame.time)};return result.also{animation=it} }
    }
    inner class AltAnimation : Animation { var refidz: Array<String> = emptyArray(); var refAnimationz: Array<AnimationDrawable?> = emptyArray()
        constructor(); constructor(id:String, ids:Array<String>) { this.id=id; refidz=ids; refAnimationz=arrayOfNulls(ids.size) }
        override fun getAnimation(res:Resources,mgr:SurfaceManager?):AnimationDrawable? { if(refidz.isEmpty())return null;val index=(Math.random()*refidz.size).toInt();return refAnimationz[index] ?: animationTable?.get(refidz[index])?.getAnimation(res,mgr)?.also{refAnimationz[index]=it} }
    }
    inner class AnimationFrame { @JvmField var sid: String?=null; @JvmField var filePath:String?=null; @JvmField var time=0; @JvmField var frameType=TYPE_BASE; @JvmField var startX=0; @JvmField var startY=0; @JvmField var W=0; @JvmField var H=0; @JvmField var d:Drawable?=null; @JvmField var hasError=false
        fun getDrawable(res:Resources,mgr:SurfaceManager?=null):Drawable? { d?.let{return it};if(hasError)return null;return when(frameType){TYPE_RESET->getSurfaceDrawable(res);TYPE_BASE->filePath?.let{loadTransparentBitmapFromFile(it,res,BitmapFactory.Options()).also{drawable->d=drawable}};TYPE_MOVE->getSurfaceDrawable(res);TYPE_OVERLAY->{val base=getSurfaceDrawable(res) ?: return null;val overlay=if(mgr!=null&&sid!=null&&mgr.containsSurface(sid!!)){mgr.getSurfaceRect(sid!!,res)?.let { W=it.width(); H=it.height() } ?: run { W=origW; H=origH };mgr.getSurfaceDrawable(sid!!,res)} else filePath?.let{loadTransparentBitmapFromFile(it,res,BitmapFactory.Options())};if(overlay==null){hasError=true;null}else LayerDrawable(arrayOf(base,overlay)).also{it.setLayerInset(1,startX,startY,origW-startX-W,origH-startY-H);d=it}};else->null} }
    }
    inner class CollisionArea(id:Int, sx:Int, sy:Int, ex:Int, ey:Int, name:String?) { @JvmField var id=id; @JvmField var name=name; @JvmField var startX=sx; @JvmField var startY=sy; @JvmField var W=ex-sx; @JvmField var H=ey-sy; @JvmField var rect=LegacyPlatform.rectangle(sx,sy,ex,ey) }

    private fun prepareAnimationTable() { if(animationTable==null) animationTable=TreeMap(); if(animationTypeTable==null) animationTypeTable=TreeMap() }
    private fun prepareCollisionAreas() { }
    private fun prepareElementList() { if(elementList==null) elementList=ArrayList() }
    private fun lookupInterval(value:String) = when { value.equals("sometimes",true)->A_TYPE_SOMETIMES; value.equals("rarely",true)->A_TYPE_RARELY; value.equals("random",true)->A_TYPE_RANDOM; value.equals("always",true)->A_TYPE_LOOP; value.equals("yen-e",true)->A_TYPE_YEN_E; value.equals("talk",true)->A_TYPE_TALK; value.equals("runonce",true)->A_TYPE_RUNONCE; value.equals("never",true)->A_TYPE_NEVER; value.equals("bind",true)->A_TYPE_BIND; else->A_TYPE_SOMETIMES }
    private fun lookupPatternType(value:String) = when { value.equals("base",true)->TYPE_BASE; value.equals("overlay",true)||value.equals("overlayfast",true)->TYPE_OVERLAY; value.equals("move",true)->TYPE_MOVE; else->TYPE_BASE }
    private fun handleInterval(id: String, interval: String) { prepareAnimationTable(); val type=lookupInterval(interval); val table=animationTable!!; val animation=table[id] ?: Animation(id,type).also { table[id]=it }; animation.interval=type; animationTypeTable!![type]=(animationTypeTable!![type]?.plus(",") ?: "")+id }
    private fun handleCollision(id:String, sx:String, sy:String, ex:String, ey:String, name:String?) { try { val key=id.toInt(); val area=CollisionArea(key,sx.toInt(),sy.toInt(),ex.toInt(),ey.toInt(),name); collisionAreas[key]=area } catch (_: Exception) {} }
    private fun addFrameToAnimation(id:String, index:Int, frame:AnimationFrame) { prepareAnimationTable(); val animation=animationTable!![id] ?: Animation().also { it.id=id; animationTable!![id]=it }; animation.addFrame(index,frame) }
    private fun handlePattern(id:String, sequence:String, filename:String, wait:String, type:String, x:String?, y:String?) { try { val frame=AnimationFrame(); frame.time=wait.toInt(); if(filename.equals("-1",true)) frame.frameType=TYPE_RESET else { frame.startX=x?.toInt() ?: 0; frame.startY=y?.toInt() ?: 0; frame.sid=filename.toInt().toString(); frame.frameType=lookupPatternType(type) }; addFrameToAnimation(id,sequence.toInt(),frame) } catch (_: Exception) {} }
    private fun addAltAnimation(id:String, ids:Array<String>) { prepareAnimationTable(); val animation=AltAnimation(id,ids); animation.interval=animationTable!![id]?.interval ?: 0; animationTable!![id]=animation }
    val collisionCount: Int get() = collisionAreas.size
    val animationCount: Int get() { prepareAnimationTable(); return animationTable!!.size }
    val firstAnimationIndex: Int get() = animationTable?.keys?.minByOrNull { it.toIntOrNull() ?: 0 }?.toIntOrNull() ?: -1
    fun getAnimationFrequency(patternId:Int): Int = animationTable?.get(patternId.toString())?.interval ?: -1
    fun getAnimationFrameCount(patternId:Int): Int = animationTable?.get(patternId.toString())?.let { it.materializeFrames(); it.frames?.size } ?: 0
    fun getAnimationIdByType(type:Int): String? = animationTypeTable?.get(type)?.split(",")?.random()
    fun resize(height:Int, width:Int) { targetW=width; targetH=height }
    fun getSurfaceDim(): Rect = dim ?: Rect(0,0,origW,origH).also { dim=it }
    fun getAnimation(id:String, res:Resources): Drawable? = getAnimation(id,res,null)
    fun getAnimation(id:String, res:Resources, mgr:SurfaceManager?): Drawable? = animationTable?.get(id)?.getAnimation(res,mgr)
    fun updateFilename(filename:String?) { if(filename==null)return; selfFilename=filename; if(surfaceType!=S_TYPE_ELEMENT) updateOrigWH() }
    private fun updateOrigWH() { val name=selfFilename ?: return; val opt=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(name,opt); origW=opt.outWidth; origH=opt.outHeight; if(origW!=-1&&origH!=-1){targetW=origW;targetH=origH} }
    fun getSurfaceDrawable(res:Resources): Drawable? { surfaceDrawable?.let{return it}; val result=when(surfaceType){S_TYPE_BASE->selfFilename?.let { loadTransparentBitmapFromFile(it,res,BitmapFactory.Options()) }; S_TYPE_ELEMENT->{val elements=elementList ?: return null; if(elements.isEmpty()) return null; val layers=Array<Drawable?>(elements.size){ elements[it].d ?: elements[it].filePath?.let { path->loadTransparentBitmapFromFile(path,res,BitmapFactory.Options()).also { drawable->elements[it].d=drawable } } }; if(layers.any{it==null}) null else LayerDrawable(layers.requireNoNulls()).also { layer-> val ow=elements[0].W; val oh=elements[0].H; for(index in 1 until elements.size){val f=elements[index];layer.setLayerInset(index,f.startX,f.startY,ow-f.startX-f.W,oh-f.startY-f.H)} } }; else->null}; surfaceDrawable=result;return result }
    private fun loadTransparentBitmapFromFile(filename:String,res:Resources,opt:BitmapFactory.Options):Drawable? { val bitmap=BitmapFactory.decodeFile(filename,opt) ?: return null; return BitmapDrawable(res,createTransparentBmp(bitmap,bitmap.getPixel(0,0))) }
    private fun createTransparentBmp(bitmap:Bitmap?, replace:Int):Bitmap? { bitmap ?: return null; val width=bitmap.width;val height=bitmap.height;val pixels=IntArray(width*height);bitmap.getPixels(pixels,0,width,0,0,width,height); for(index in pixels.indices)if(pixels[index]==replace)pixels[index]=Color.TRANSPARENT;return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888) }
    private fun loadSurface(elements:List<String?>?, probeBitmap:Boolean = true) { if(elements!=null)loadElements(elements); if(surfaceType==S_TYPE_ELEMENT&&(elementList?.isNotEmpty()==true)){origW=elementList!![0].W;origH=elementList!![0].H;targetW=origW;targetH=origH;return}; val primary=selfFilename?.let(::File); if(primary?.exists()!=true){val fallback=bp2?.let(::File) ?: return;if(!fallback.exists())return;selfFilename=bp2};if(probeBitmap)updateOrigWH() }
    private fun loadElements(elements:List<String?>) { for(raw in elements){val line=raw ?: continue;if(PatternHolders.comment_ptrn.matcher(line).find())continue; var m=PatternHolders.collision.matcher(line);if(m.find()){handleCollision(m.group(1),m.group(2),m.group(3),m.group(4),m.group(5),m.group(6));continue};m=PatternHolders.element.matcher(line);if(m.find()){surfaceType=S_TYPE_ELEMENT;handleElement(m.group(1),m.group(3),m.group(4),m.group(5));continue};m=PatternHolders.interval.matcher(line);if(m.find()){handleInterval(m.group(1),m.group(2));continue};m=PatternHolders.animation_interval.matcher(line);if(m.find()){handleInterval(m.group(1),m.group(2));continue};m=PatternHolders.pattern.matcher(line);if(m.find()){handlePattern(m.group(1),m.group(2),m.group(3),m.group(4),m.group(5),m.group(6),m.group(7));continue};m=PatternHolders.animation.matcher(line);if(m.find()){if(m.group(4)==null)handlePattern(m.group(1),m.group(2),m.group(6),m.group(7),m.group(5),m.group(8),m.group(9))else addAltAnimation(m.group(1),m.group(4));continue};m=PatternHolders.animation_base.matcher(line);if(m.find()){handlePattern(m.group(1),m.group(2),m.group(6) ?: m.group(4),m.group(7),m.group(3),null,null);continue};m=PatternHolders.pattern_base.matcher(line);if(m.find()){handlePattern(m.group(1),m.group(2),m.group(3),m.group(4),m.group(5),null,null);continue};m=PatternHolders.option.matcher(line);if(m.find()){handleOptions(m.group());continue};m=PatternHolders.pattern_alt.matcher(line);if(m.find())addAltAnimation(m.group(1),m.group(3),"\\.") };animationTable?.values?.forEach { it.materializeFrames() } }
    private fun handleElement(index:String, filename:String, x:String, y:String) { try { val path=(basePath ?: "")+filename;val file=File(path);if(!file.exists())return;val opt=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,opt);val frame=AnimationFrame().apply{filePath=path;frameType=TYPE_BASE;startX=x.toInt();startY=y.toInt();W=opt.outWidth;H=opt.outHeight};prepareElementList();elementList!!.add(index.toInt(),frame)}catch(_:Exception){} }
    private fun handleOption(id:String, option:String) { prepareAnimationTable();val animation=animationTable!![id] ?: Animation().also{it.id=id;animationTable!![id]=it};animation.exclusive=option.equals("exclusive",true) }
    fun dumpSurfaceStat():String = StringBuilder("==================\n").let{dumpAnimation(dumpElementList(dumpStat(it))).append("==================\n").toString()}
    fun dumpStat(sb:StringBuilder):StringBuilder { sb.append("[dumping surface status]").append("surface id:").append(surfaceId).append('\n').append("surface type:").append(surfaceType).append('\n').append("surface filename:").append(selfFilename).append('\n').append("[origW,origH]:[").append(origW).append(',').append(origH).append("]\n");animationTable?.let{sb.append("animation count:").append(it.size).append('\n')};return sb }
    fun dumpElementList(sb:StringBuilder):StringBuilder { elementList?.takeIf{it.isNotEmpty()}?.let{list->sb.append("[dumping element list]\n");list.forEach{sb.append("[AnimationFrame] sid=").append(it.sid).append(",FType=").append(it.frameType).append(",[W,H]=[").append(it.W).append(',').append(it.H).append("],(startX,startY)=(").append(it.startX).append(',').append(it.startY).append(")\n")};sb.append("[end of element list]\n")};return sb }
    fun dumpAnimation(sb:StringBuilder):StringBuilder { animationTable?.let{table->sb.append("[dumping animation]\n");table.values.forEach{a->sb.append("[Animation] id:").append(a.id).append(",interval:").append(a.interval).append('\n')}};animationTypeTable?.let{table->sb.append("[dumping animation type table]\n");table.values.forEach{sb.append(it).append('\n')};sb.append("[done dumping animation type table]\n")};return sb }
    private fun readBitmapInfo(filename:String):BitmapFactory.Options = BitmapFactory.Options().apply{inJustDecodeBounds=true;BitmapFactory.decodeFile(filename,this)}
    private fun findIdInCollision(id:Int):CollisionArea? = collisionAreas[id]
    private fun handleOptions(value:String) { LegacyPlatform.debug("ShellSurface", "surface parse not support: $value"); try { AnalyticsUtils.getInstance(null).trackEvent(Setup.ANA_ERR,"surface parse not support",value,-1) } catch (_:Exception) {} }
    private fun addAltAnimation(id:String, ids:String, splitter:String) = addAltAnimation(id,ids.split(Regex(splitter)).toTypedArray())
    private fun addAltAnimation(id:String, ids:String) = addAltAnimation(id,ids.split(",").toTypedArray())
    fun getAnimation(patternId:Int,res:Resources):Drawable? = getAnimation(patternId.toString(),res)
    private fun getAnimationFrame(patternId:String,frameIndex:Int):AnimationFrame = animationTable!![patternId]!!.frames!![frameIndex]
}
