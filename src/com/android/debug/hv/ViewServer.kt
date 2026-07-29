package com.android.debug.hv

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.text.TextUtils
import android.view.View
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Kotlin transcription staging source for the legacy HierarchyViewer server. */
open class ViewServer private constructor(private val port: Int = -1) : Runnable {
    companion object {
        private const val DEFAULT_PORT = 4939
        private const val BUILD_TYPE_USER = "user"
        private var server: ViewServer? = null
        @JvmStatic fun get(context: Context): ViewServer {
            val info = context.applicationInfo
            server = if (Build.TYPE == BUILD_TYPE_USER && info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                (server ?: ViewServer(DEFAULT_PORT)).also { if (!it.isRunning) try { it.start() } catch (_: IOException) {} }
            } else NoopViewServer()
            return server!!
        }
    }
    private var socket: ServerSocket? = null; private var thread: Thread? = null; private var pool = Executors.newFixedThreadPool(10)
    private val windows = ConcurrentHashMap<View,String>(); @Volatile private var focused: View? = null
    private val listeners=java.util.concurrent.CopyOnWriteArrayList<WindowListener>()
    @Throws(IOException::class) open fun start(): Boolean { if(thread!=null)return false;socket=ServerSocket(port,10);thread=Thread(this,"Local View Server [port=$port]").also{it.start()};return true }
    open fun stop(): Boolean { thread?.interrupt();pool.shutdownNow();socket?.close();thread=null;socket=null;return true }
    open val isRunning:Boolean get()=thread?.isAlive==true
    open fun addWindow(activity:Activity) { val name=activity.title.toString().ifEmpty { "${activity.javaClass.canonicalName}/0x${System.identityHashCode(activity)}" }; addWindow(activity.window.decorView,name) }
    open fun removeWindow(activity:Activity) { removeWindow(activity.window.decorView) }
    open fun addWindow(view:View,name:String) { windows[view.rootView]=name;listeners.forEach{it.windowsChanged()} }
    open fun removeWindow(view:View) { windows.remove(view.rootView);listeners.forEach{it.windowsChanged()} }
    open fun setFocusedWindow(activity:Activity) { setFocusedWindow(activity.window.decorView) }
    open fun setFocusedWindow(view:View?) { focused=view?.rootView;listeners.forEach{it.focusChanged()} }
    override fun run() { while(Thread.currentThread()===thread) try { socket?.accept()?.let { pool.submit(Worker(it)) } } catch (_:Exception) {} }
    private interface WindowListener { fun windowsChanged();fun focusChanged() }
    private inner class Worker(private val client:Socket):Runnable,WindowListener { @Volatile private var listUpdate=false;@Volatile private var focusUpdate=false;private val lock=Object(); override fun run(){ try { val request=BufferedReader(InputStreamReader(client.getInputStream()),1024).readLine() ?: return;val split=request.indexOf(' ');val command=if(split<0)request else request.substring(0,split);val params=if(split<0)"" else request.substring(split+1);when(command.uppercase()){"PROTOCOL","SERVER"->writeValue(client,"4");"LIST"->listWindows(client);"GET_FOCUS"->getFocusedWindow(client);"AUTOLIST"->autolist();else->windowCommand(client,command,params)} } catch (_:Exception) {} finally { listeners.remove(this);try{client.close()}catch(_:Exception){} } }
        private fun listWindows(client:Socket){BufferedWriter(OutputStreamWriter(client.getOutputStream()),8192).use{out->windows.forEach{(view,name)->out.write(Integer.toHexString(System.identityHashCode(view))+" "+name+"\n")};out.write("DONE.\n");out.flush()}}
        private fun getFocusedWindow(client:Socket){BufferedWriter(OutputStreamWriter(client.getOutputStream()),8192).use{out->focused?.let{out.write(Integer.toHexString(System.identityHashCode(it))+" "+(windows[it] ?: ""))};out.write("\n");out.flush()}}
        private fun windowCommand(client:Socket,command:String,params:String){ val first=params.indexOf(' ').let{if(it<0)params.length else it};val code=params.substring(0,first).toLong(16).toInt();val target=if(code==-1)focused else windows.keys.firstOrNull{System.identityHashCode(it)==code};if(target==null)return;try{val method=android.view.ViewDebug::class.java.getDeclaredMethod("dispatchCommand",View::class.java,String::class.java,String::class.java,java.io.OutputStream::class.java);method.isAccessible=true;method.invoke(null,target,command,if(first<params.length)params.substring(first+1)else "",client.getOutputStream());if(!client.isOutputShutdown)client.getOutputStream().write("DONE\n".toByteArray())}catch(_:Exception){}}
        private fun autolist(){listeners.add(this);BufferedWriter(OutputStreamWriter(client.getOutputStream())).use{out->while(!Thread.interrupted()){synchronized(lock){while(!listUpdate&&!focusUpdate)lock.wait();if(listUpdate){listUpdate=false;out.write("LIST UPDATE\n")};if(focusUpdate){focusUpdate=false;out.write("FOCUS UPDATE\n")};out.flush()}}}}
        override fun windowsChanged(){synchronized(lock){listUpdate=true;lock.notifyAll()}};override fun focusChanged(){synchronized(lock){focusUpdate=true;lock.notifyAll()}}
    }
    private class UncloseableOutputStream(private val stream:OutputStream):OutputStream(){override fun close(){};override fun flush()=stream.flush();override fun write(b:Int)=stream.write(b);override fun write(b:ByteArray)=stream.write(b);override fun write(b:ByteArray,off:Int,len:Int)=stream.write(b,off,len);override fun equals(other:Any?)=stream==other;override fun hashCode()=stream.hashCode();override fun toString()=stream.toString()}
    private fun writeValue(client:Socket,value:String){BufferedWriter(OutputStreamWriter(client.getOutputStream()),8192).use{it.write(value);it.write("\n");it.flush()}}
    private class NoopViewServer : ViewServer() { override fun start()=false;override fun stop()=false;override val isRunning:Boolean get()=false;override fun addWindow(activity:Activity){};override fun removeWindow(activity:Activity){};override fun addWindow(view:View,name:String){};override fun removeWindow(view:View){};override fun setFocusedWindow(activity:Activity){};override fun setFocusedWindow(view:View?){};override fun run(){} }
}
