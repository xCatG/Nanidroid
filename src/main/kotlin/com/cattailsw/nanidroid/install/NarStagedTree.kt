package com.cattailsw.nanidroid.install

import android.content.Context

/** Package-private capability boundary for a staged ghost tree. */
internal object NarStagedTree {
    private const val NATIVE_TOKEN_BYTES = 88
    enum class Error { OK, INVALID_OPTIONS, INVALID_TARGET, ROOT_TYPE, TARGET_TYPE, SYMLINK, SPECIAL_TYPE, INVALID_NAME, COMPONENT_LIMIT, PATH_LIMIT, DEPTH_LIMIT, ENTRY_COUNT_LIMIT, FILE_SIZE_LIMIT, TOTAL_SIZE_LIMIT, CYCLE, TREE_CHANGED, PERMISSION, RESOURCE, IO, VISITOR, CLOSE, INPUT, NATIVE, POLICY, CLOSED, CONSUMED, FOREIGN, WRONG_SESSION, BUSY }
    interface Handle
    interface Backend {
        fun begin(context: Context, root: NarFilesystemInspector.TrustedRoot, target: CharSequence): BeginResult?
        fun describe(handle: Handle): NarStagedTreeInventory.Description
        fun discard(context: Context, handle: Handle): Error?
    }
    private class NativeHandle(token: ByteArray?, val description: NarStagedTreeInventory.Description?) : Handle {
        val token: ByteArray
        init { require(token != null && token.size == NATIVE_TOKEN_BYTES && description != null) { "native handle" }; this.token = token.clone() }
    }
    class NativeBackend : Backend {
        private var loaded = false
        override fun begin(context: Context, root: NarFilesystemInspector.TrustedRoot, target: CharSequence): BeginResult? { ensureLoaded(); return nativeBegin(context.getDir("narfs-stage-v1", Context.MODE_PRIVATE).absolutePath, NarFilesystemInspector.sourceRootValue(root), target.toString()) }
        override fun describe(handle: Handle): NarStagedTreeInventory.Description = owned(handle).description!!
        override fun discard(context: Context, handle: Handle): Error? { ensureLoaded(); return fromNativeError(nativeDiscard(context.getDir("narfs-stage-v1", Context.MODE_PRIVATE).absolutePath, owned(handle).token)) }
        @Synchronized private fun ensureLoaded() { if (!loaded) { System.loadLibrary("narfs"); loaded = true } }
        private fun owned(handle: Handle): NativeHandle = handle as? NativeHandle ?: throw IllegalArgumentException("native handle")
    }
    class BeginResult private constructor(@get:JvmSynthetic internal val kind: Int, @get:JvmSynthetic internal val storageDevice: Long, @get:JvmSynthetic internal val storageInode: Long, @get:JvmSynthetic internal val handle: Handle?, @get:JvmSynthetic internal val primaryError: Error?, @get:JvmSynthetic internal val cleanupError: Error?) {
        companion object { private const val ABSENT=1; private const val PRESENT=2; private const val FAILURE=3
            @JvmStatic fun absent(device:Long,inode:Long)=BeginResult(ABSENT,device,inode,null,null,Error.OK)
            @JvmStatic fun present(handle:Handle)=BeginResult(PRESENT,0,0,handle,null,Error.OK)
            @JvmStatic fun failure(primary:Error?, cleanup:Error?, handle:Handle?)=BeginResult(FAILURE,0,0,handle,primary,cleanup)
        }
    }
    class Cleanup(private val nativeErrorValue: Error?, @get:JvmSynthetic @set:JvmSynthetic internal var discardError: Error?, @get:JvmSynthetic @set:JvmSynthetic internal var recovery: DiscardOwner?) {
        private val nativeError = normalize(nativeErrorValue); init { discardError=normalize(discardError) }
        fun nativeError()=nativeError; fun discardError()=discardError!!; fun discard()=recovery?.discard() ?: Error.OK
    }
    class StageResult private constructor(val tree: Tree?, val error: Error?, val cleanup: Cleanup, val detail: String?) {
        companion object { @JvmStatic fun success(tree:Tree)=StageResult(tree,Error.OK,Cleanup(Error.OK,Error.OK,null),""); @JvmStatic fun failure(error:Error?, cleanup:Error?, detail:String?)=StageResult(null,normalizeFailure(error),Cleanup(cleanup,Error.OK,null),detail ?: "") }
        fun isSuccess()=tree != null
    }
    class Stager(@get:JvmSynthetic internal val backend: Backend = NativeBackend()) { init { requireNotNull(backend) { "backend" } }; fun session(context:Context):Session { requireNotNull(context) { "context" }; return Session(this,context) } }
    class Session(private val owner:Stager, private val context:Context) {
        fun stage(root:NarFilesystemInspector.TrustedRoot?, target:String?):StageResult {
            if(root==null||target==null)return StageResult.failure(Error.INPUT,Error.OK,"input")
            var pending:Handle?=null; var pendingOwner:DiscardOwner?=null; var result:StageResult?=null
            try { val begun=owner.backend.begin(context,root,target); if(begun==null) result=StageResult.failure(Error.NATIVE,Error.OK,"begin") else { pending=begun.handle; if(pending!=null)pendingOwner=DiscardOwner(owner.backend,context,pending!!)
                result=when { begun.kind==1&&pending==null -> fromInventory(owner,this,context,NarStagedTreeInventory.absent(target,begun.storageDevice,begun.storageInode),null)
                    begun.kind==2&&pending!=null -> { val r=fromInventory(owner,this,context,NarStagedTreeInventory.present(target,owner.backend.describe(pending!!)),pending); if(r.isSuccess()){pending=null;pendingOwner=null};r }
                    begun.kind==3 -> StageResult.failure(begun.primaryError,begun.cleanupError,"begin")
                    else -> StageResult.failure(Error.NATIVE,Error.OK,"begin shape") }
            }} catch(_:RuntimeException){result=StageResult.failure(Error.NATIVE,Error.OK,"backend")} catch(_:LinkageError){result=StageResult.failure(Error.NATIVE,Error.OK,"backend")}
            finally { if(pendingOwner!=null){ if(result!=null)result!!.cleanup.recovery=pendingOwner; val discarded=try{pendingOwner!!.discard()}catch(_:OutOfMemoryError){Error.NATIVE}; if(result!=null){result!!.cleanup.discardError=discarded;if(discarded==Error.OK)result!!.cleanup.recovery=null} } else if(pending!=null) try{Resource.discard(owner.backend,context,pending!!)}catch(_:OutOfMemoryError){} }
            return result!!
        }
        fun consume(tree:Tree?):ConsumeResult { if(tree==null)return ConsumeResult.failure(Error.INPUT); synchronized(tree){ return when { tree.owner!==owner->ConsumeResult.failure(Error.FOREIGN); tree.session!==this->ConsumeResult.failure(Error.WRONG_SESSION); tree.consumed->ConsumeResult.failure(Error.CONSUMED); tree.resource.isClosed()->ConsumeResult.failure(Error.CLOSED); else->{tree.consumed=true;ConsumeResult.success(Claim(tree.resource))} } } }
    }
    class Tree internal constructor(@get:JvmSynthetic internal val owner:Stager, @get:JvmSynthetic internal val session:Session, @get:JvmSynthetic internal val resource:Resource) { @get:JvmSynthetic @set:JvmSynthetic internal var consumed=false; fun manifest()=resource.inventory.manifest()!!; fun entries()=resource.inventory.entries(); @Synchronized fun discard()=if(consumed)Error.CONSUMED else resource.discard() }
    class Claim(private val resource:Resource) { enum class State{READY,BUSY,CONSUMED}; private var state=State.READY; private var current:Lease?=null; private var directCleanup=false
        @Synchronized fun state()=state; @Synchronized fun lease():Lease? { if(state!=State.READY)return null; return Lease(this,resource).also{current=it;state=State.BUSY} }
        @Synchronized fun release(lease:Lease?):Error { if(lease==null||lease.owner!==this)return Error.FOREIGN;if(!lease.active||state==State.CONSUMED)return Error.CONSUMED;if(state!=State.BUSY||current!==lease)return Error.BUSY;lease.active=false;current=null;state=State.READY;return Error.OK }
        @Synchronized fun consume(lease:Lease?):Error { if(lease==null||lease.owner!==this)return Error.FOREIGN;if(!lease.active||state==State.CONSUMED)return Error.CONSUMED;if(state!=State.BUSY||current!==lease)return Error.BUSY;lease.active=false;lease.consumed=true;current=null;state=State.CONSUMED;return Error.OK }
        fun discard():Error { synchronized(this){if(state==State.BUSY)return Error.BUSY;if(state==State.READY){state=State.CONSUMED;directCleanup=true}else if(!directCleanup)return Error.CONSUMED};return resource.discard() }
        class Lease internal constructor(@get:JvmSynthetic internal val owner:Claim, private val resource:Resource) { @get:JvmSynthetic @set:JvmSynthetic internal var active=true; @get:JvmSynthetic @set:JvmSynthetic internal var consumed=false; fun manifest():NarGhostTreePolicy.Manifest { synchronized(owner){check(active&&owner.state==State.BUSY&&owner.current===this){"stale baseline lease"};return resource.inventory.manifest()!!} }; fun entries():List<NarStagedTreeInventory.Entry>{synchronized(owner){check(active&&owner.state==State.BUSY&&owner.current===this){"stale baseline lease"};return resource.inventory.entries()}}; fun discard()=synchronized(owner){if(!consumed)Error.CONSUMED else resource.discard()} }
    }
    class ConsumeResult private constructor(val claim:Claim?, val error:Error?) { companion object { @JvmStatic fun success(claim:Claim)=ConsumeResult(claim,Error.OK); @JvmStatic fun failure(error:Error?)=ConsumeResult(null,normalize(error)) }; fun isSuccess()=claim!=null }
    internal class Resource(val backend:Backend,val context:Context,var handle:Handle?,val inventory:NarStagedTreeInventory.Result) { private var closed=false; @Synchronized fun isClosed()=closed; @Synchronized fun discard():Error { if(closed)return Error.OK;if(handle==null){closed=true;return Error.OK};val r=discard(backend,context,handle!!);if(r==Error.OK){handle=null;closed=true};return r }; companion object { fun discard(backend:Backend,context:Context,handle:Handle)=try{normalize(backend.discard(context,handle))}catch(_:RuntimeException){Error.NATIVE}catch(_:LinkageError){Error.NATIVE} } }
    internal class DiscardOwner(private val backend:Backend,private val context:Context,private var handle:Handle?) { @Synchronized fun discard():Error { if(handle==null)return Error.OK;val r=Resource.discard(backend,context,handle!!);if(r==Error.OK)handle=null;return r } }
    private fun normalize(error:Error?)=error?:Error.NATIVE; private fun normalizeFailure(error:Error?)=if(error==null||error==Error.OK)Error.NATIVE else error
    @JvmStatic fun fromNativeBegin(stateCode:Int,errorCode:Int,cleanupCode:Int,storageDevice:Long,storageInode:Long,token:ByteArray?,paths:Array<String>?,types:IntArray?,sizes:LongArray?,ordinals:IntArray?,digests:ByteArray?):BeginResult { val error=fromNativeError(errorCode);val cleanup=fromNativeError(cleanupCode);val description=NarStagedTreeInventory.Description(storageDevice,storageInode,paths,types,sizes,ordinals,digests);val handle=if(token==null)null else NativeHandle(token,description);if(stateCode==1&&error==Error.OK&&cleanup==Error.OK&&handle==null&&paths?.isEmpty()==true&&types?.isEmpty()==true&&sizes?.isEmpty()==true&&ordinals?.isEmpty()==true&&digests?.isEmpty()==true)return BeginResult.absent(storageDevice,storageInode);if(stateCode==2&&error==Error.OK&&cleanup==Error.OK&&handle!=null)return BeginResult.present(handle);return BeginResult.failure(if(error==Error.OK)Error.NATIVE else error,cleanup,handle) }
    private fun fromNativeError(code:Int)=when { code in 0..20->Error.entries[code];code==100->Error.INPUT;else->Error.NATIVE }
    private fun fromInventory(owner:Stager,session:Session,context:Context,inventory:NarStagedTreeInventory.Result,handle:Handle?):StageResult { if(!inventory.isSuccess())return StageResult.failure(if(inventory.error()==NarStagedTreeInventory.Error.POLICY)Error.POLICY else Error.NATIVE,Error.OK,inventory.detail());return StageResult.success(Tree(owner,session,Resource(owner.backend,context,handle,inventory))) }
    @JvmStatic private external fun nativeBegin(stagingRoot:String,trustedRoot:String,target:String):BeginResult
    @JvmStatic private external fun nativeDiscard(stagingRoot:String,token:ByteArray):Int
}
