package com.cattailsw.nanidroid.install

import android.content.Context
import android.test.mock.MockContext
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import sun.misc.Unsafe
import org.junit.Assert.*
import org.junit.Test

class NarStagedTreeTest {
    @Test fun absentHasNoOwnershipAndPresentEmptyOwnsHandle() {
        val absentBackend = FakeBackend().also { it.begin = NarStagedTree.BeginResult.absent(7, 11) }
        val absent = success(session(absentBackend).stage(ROOT, "ghost"))
        val presentBackend = FakeBackend().also { it.present(empty()) }
        val present = success(session(presentBackend).stage(ROOT, "ghost"))
        assertEquals(NarGhostTreePolicy.State.ABSENT, absent.manifest().state)
        assertEquals(NarGhostTreePolicy.State.PRESENT, present.manifest().state)
        assertTrue(absent.entries().isEmpty()); assertTrue(present.entries().isEmpty())
        assertEquals(NarStagedTree.Error.OK, absent.discard()); assertEquals(0, absentBackend.discards)
        assertEquals(NarStagedTree.Error.OK, present.discard()); assertEquals(1, presentBackend.discards)
        val transferredAbsent = FakeBackend().also { it.begin = NarStagedTree.BeginResult.absent(7, 11) }
        val absentSession = session(transferredAbsent)
        val absentClaim = absentSession.consume(success(absentSession.stage(ROOT, "other")))
        assertTrue(absentClaim.isSuccess()); assertEquals(NarStagedTree.Error.OK, absentClaim.claim!!.discard()); assertEquals(NarStagedTree.Error.OK, absentClaim.claim!!.discard()); assertEquals(0, transferredAbsent.discards)
    }

    @Test fun malformedRuntimeLinkageAndOomeAlwaysReleaseReturnedHandle() {
        val nullBegin=FakeBackend(); assertFailure(NarStagedTree.Error.NATIVE, session(nullBegin).stage(ROOT,"ghost")); assertEquals(0,nullBegin.discards)
        val linkBegin=FakeBackend().also { it.beginFailure=UnsatisfiedLinkError("missing") };assertFailure(NarStagedTree.Error.NATIVE,session(linkBegin).stage(ROOT,"ghost"));assertEquals(0,linkBegin.discards)
        val nullHandle=FakeBackend().also { it.begin=presentWithNullHandle() }; assertFailure(NarStagedTree.Error.NATIVE,session(nullHandle).stage(ROOT,"ghost"));assertEquals(0,nullHandle.discards)
        val malformedFailure=FakeBackend().also { it.begin=NarStagedTree.BeginResult.failure(null,null,it.handle);it.discardResults=arrayOf(null) }
        val malformed=session(malformedFailure).stage(ROOT,"ghost");assertFailure(NarStagedTree.Error.NATIVE,malformed);assertEquals(NarStagedTree.Error.NATIVE,malformed.cleanup.nativeError());assertEquals(NarStagedTree.Error.NATIVE,malformed.cleanup.discardError());malformedFailure.discardResults=arrayOf(NarStagedTree.Error.OK);assertEquals(NarStagedTree.Error.OK,malformed.cleanup.discard());assertEquals(2,malformedFailure.discards)
        val okFailure=FakeBackend().also { it.begin=NarStagedTree.BeginResult.failure(NarStagedTree.Error.OK,NarStagedTree.Error.OK,it.handle) };val okAsFailure=session(okFailure).stage(ROOT,"ghost");assertFailure(NarStagedTree.Error.NATIVE,okAsFailure);assertEquals(1,okFailure.discards)
        val nullDescription=FakeBackend().also { it.present(null) };assertFailure(NarStagedTree.Error.NATIVE,session(nullDescription).stage(ROOT,"ghost"));assertEquals(1,nullDescription.discards)
        val runtime=failingDescription(IllegalStateException("hostile"));assertFailure(NarStagedTree.Error.NATIVE,session(runtime).stage(ROOT,"ghost"));assertEquals(1,runtime.discards)
        val linkage=failingDescription(UnsatisfiedLinkError("missing")).also { it.discardResults=arrayOf(NarStagedTree.Error.PERMISSION,NarStagedTree.Error.OK) };val linked=session(linkage).stage(ROOT,"ghost");assertFailure(NarStagedTree.Error.NATIVE,linked);assertEquals(NarStagedTree.Error.PERMISSION,linked.cleanup.discardError());assertEquals(NarStagedTree.Error.OK,linked.cleanup.discard());assertEquals(2,linkage.discards)
        val original=OutOfMemoryError("hostile");val oome=failingDescription(original).also { it.discardFailure=OutOfMemoryError("cleanup must not mask") };assertSame(original,assertThrows(OutOfMemoryError::class.java){session(oome).stage(ROOT,"ghost")});assertEquals(1,oome.discards)
        val collision=FakeBackend().also { it.present(description(arrayOf("é","é"),intArrayOf(1,1),longArrayOf(1,1),intArrayOf(0,1),flat(digest("a"),digest("b")))) };assertFailure(NarStagedTree.Error.POLICY,session(collision).stage(ROOT,"ghost"));assertEquals(1,collision.discards)
    }

    @Test fun primaryAndCleanupFailuresStaySeparate() {
        val backend=FakeBackend().also { it.begin=NarStagedTree.BeginResult.failure(NarStagedTree.Error.IO,NarStagedTree.Error.CLOSE,it.handle);it.discardResults=arrayOf(NarStagedTree.Error.PERMISSION,NarStagedTree.Error.OK) }
        val result=session(backend).stage(ROOT,"ghost");assertFailure(NarStagedTree.Error.IO,result);assertEquals(NarStagedTree.Error.CLOSE,result.cleanup.nativeError());assertEquals(NarStagedTree.Error.PERMISSION,result.cleanup.discardError());assertEquals(NarStagedTree.Error.OK,result.cleanup.discard());assertEquals(NarStagedTree.Error.OK,result.cleanup.discard());assertEquals(2,backend.discards)
        val throwingCleanup=failingDescription(UnsatisfiedLinkError("primary")).also { it.discardFailure=UnsatisfiedLinkError("cleanup") };val throwing=session(throwingCleanup).stage(ROOT,"ghost");assertFailure(NarStagedTree.Error.NATIVE,throwing);assertEquals(NarStagedTree.Error.NATIVE,throwing.cleanup.discardError());throwingCleanup.discardFailure=null;assertEquals(NarStagedTree.Error.OK,throwing.cleanup.discard());assertEquals(2,throwingCleanup.discards)
    }

    @Test fun transferTypesMisuseAndTreeClaimDiscardAreRetrySafe() {
        val backend=FakeBackend().also { it.present(empty()) };val owner=NarStagedTree.Stager(backend);val current=owner.session(CONTEXT);val tree=success(current.stage(ROOT,"ghost"));val wrongSession=owner.session(CONTEXT);val foreign=NarStagedTree.Stager(FakeBackend())
        assertEquals(NarStagedTree.Error.WRONG_SESSION,wrongSession.consume(tree).error);assertEquals(NarStagedTree.Error.FOREIGN,foreign.session(CONTEXT).consume(tree).error);val consumed=current.consume(tree);assertTrue(consumed.isSuccess());assertNotNull(consumed.claim)
        val treeResource=NarStagedTree.Tree::class.java.getDeclaredField("resource");val claimResource=NarStagedTree.Claim::class.java.getDeclaredField("resource");treeResource.isAccessible=true;claimResource.isAccessible=true;assertSame(treeResource[tree],claimResource[consumed.claim])
        assertEquals(NarStagedTree.Error.CONSUMED,current.consume(tree).error);assertEquals(NarStagedTree.Error.CONSUMED,tree.discard());backend.discardResults=arrayOf(NarStagedTree.Error.PERMISSION,NarStagedTree.Error.OK);assertEquals(NarStagedTree.Error.PERMISSION,consumed.claim!!.discard());assertEquals(NarStagedTree.Error.OK,consumed.claim!!.discard());assertEquals(NarStagedTree.Error.OK,consumed.claim!!.discard());assertEquals(2,backend.discards)
        backend.present(empty());val closed=success(current.stage(ROOT,"other"));backend.discardResults=arrayOf(NarStagedTree.Error.IO,NarStagedTree.Error.OK);assertEquals(NarStagedTree.Error.IO,closed.discard());assertEquals(NarStagedTree.Error.OK,closed.discard());assertEquals(NarStagedTree.Error.OK,closed.discard());assertEquals(NarStagedTree.Error.CLOSED,current.consume(closed).error);assertEquals(4,backend.discards)
        val nullThenSuccess=FakeBackend().also { it.present(empty()) };val nullSession=session(nullThenSuccess);val nullTree=success(nullSession.stage(ROOT,"ghost"));nullThenSuccess.discardResults=arrayOf(null,NarStagedTree.Error.OK);assertEquals(NarStagedTree.Error.NATIVE,nullTree.discard());assertEquals(NarStagedTree.Error.OK,nullTree.discard());assertEquals(NarStagedTree.Error.OK,nullTree.discard());assertEquals(2,nullThenSuccess.discards)
        val failedThenTransferred=FakeBackend().also { it.present(empty()) };val transferSession=session(failedThenTransferred);val transferTree=success(transferSession.stage(ROOT,"ghost"));failedThenTransferred.discardResults=arrayOf(NarStagedTree.Error.IO,NarStagedTree.Error.OK);assertEquals(NarStagedTree.Error.IO,transferTree.discard());val afterFailure=transferSession.consume(transferTree);assertTrue(afterFailure.isSuccess());assertEquals(NarStagedTree.Error.OK,afterFailure.claim!!.discard());assertEquals(2,failedThenTransferred.discards)
        val throwingRetry=FakeBackend().also { it.present(empty()) };val throwingSession=session(throwingRetry);val throwingClaim=throwingSession.consume(success(throwingSession.stage(ROOT,"ghost"))).claim!!;val discardOome=OutOfMemoryError("retry");throwingRetry.discardFailure=discardOome;assertSame(discardOome,assertThrows(OutOfMemoryError::class.java){throwingClaim.discard()});throwingRetry.discardFailure=null;assertEquals(NarStagedTree.Error.OK,throwingClaim.discard());assertEquals(NarStagedTree.Error.OK,throwingClaim.discard());assertEquals(2,throwingRetry.discards)
    }

    @Test fun claimLeaseIsExclusiveExactAndTransferredCleanupOnly() {
        val backend=FakeBackend().also { it.present(empty()) };val claim=claim(backend,"ghost");val otherBackend=FakeBackend().also{it.present(empty())};val other=claim(otherBackend,"other")
        assertEquals("READY",claim.state().name);val lease=claim.lease()!!;assertEquals(NarGhostTreePolicy.State.PRESENT,lease.manifest().state);assertTrue(lease.entries().isEmpty());assertEquals("BUSY",claim.state().name);assertNull(claim.lease());assertEquals(NarStagedTree.Error.BUSY,claim.discard());val foreign=other.lease()!!;assertEquals(NarStagedTree.Error.FOREIGN,claim.release(foreign));assertEquals(NarStagedTree.Error.FOREIGN,claim.consume(foreign));assertEquals(NarStagedTree.Error.OK,claim.release(lease));assertEquals("READY",claim.state().name);assertEquals(NarStagedTree.Error.CONSUMED,claim.release(lease));assertThrows(IllegalStateException::class.java){lease.manifest()};assertEquals(NarStagedTree.Error.OK,other.release(foreign))
        backend.discardResults=arrayOf(NarStagedTree.Error.PERMISSION,NarStagedTree.Error.OK);val consumed=claim.lease()!!;assertEquals(NarStagedTree.Error.OK,claim.consume(consumed));assertEquals("CONSUMED",claim.state().name);assertEquals(NarStagedTree.Error.CONSUMED,claim.discard());assertNull(claim.lease());assertEquals(NarStagedTree.Error.PERMISSION,consumed.discard());assertEquals(NarStagedTree.Error.OK,consumed.discard());assertEquals(NarStagedTree.Error.OK,consumed.discard());assertEquals(2,backend.discards);assertMethods(NarStagedTree.Claim.Lease::class.java,"discard","entries","manifest");assertEquals(NarStagedTree.Error.OK,other.discard())
    }

    @Test fun absentAndOomeClaimLeaseCleanupRemainRetryable() {
        val absentBackend=FakeBackend().also { it.begin=NarStagedTree.BeginResult.absent(7,11) };val absent=claim(absentBackend,"absent");val absentLease=absent.lease()!!;assertEquals(NarGhostTreePolicy.State.ABSENT,absentLease.manifest().state);assertEquals(NarStagedTree.Error.OK,absent.consume(absentLease));assertEquals(NarStagedTree.Error.CONSUMED,absent.discard());assertEquals(NarStagedTree.Error.OK,absentLease.discard());assertEquals(NarStagedTree.Error.OK,absentLease.discard());assertEquals(0,absentBackend.discards)
        val oomeBackend=FakeBackend().also { it.present(empty()) };val oome=claim(oomeBackend,"oome");val oomeLease=oome.lease()!!;assertEquals(NarStagedTree.Error.OK,oome.consume(oomeLease));val failure=OutOfMemoryError("discard");oomeBackend.discardFailure=failure;assertSame(failure,assertThrows(OutOfMemoryError::class.java){oomeLease.discard()});oomeBackend.discardFailure=null;assertEquals(NarStagedTree.Error.OK,oomeLease.discard());assertEquals(NarStagedTree.Error.OK,oomeLease.discard());assertEquals(2,oomeBackend.discards)
    }

    @Test fun concurrentClaimLeaseAndDirectDiscardAreLinearized() {
        val backend=FakeBackend().also{it.present(empty())};val claim=claim(backend,"race");var lease:NarStagedTree.Claim.Lease?=null;var discard:NarStagedTree.Error?=null;race({lease=claim.lease()},{discard=claim.discard()});if(lease==null)assertEquals(NarStagedTree.Error.OK,discard) else {assertEquals(NarStagedTree.Error.BUSY,discard);assertEquals(NarStagedTree.Error.OK,claim.consume(lease));assertEquals(NarStagedTree.Error.OK,lease!!.discard())};assertEquals("CONSUMED",claim.state().name);assertEquals(1,backend.discards)
    }

    @Test fun concurrentDiscardAndTransferAreLinearized() {
        val claimBackend=FakeBackend().also{it.present(empty())};val claimSession=session(claimBackend);val claim=claimSession.consume(success(claimSession.stage(ROOT,"ghost"))).claim!!;val claimResults=arrayOfNulls<NarStagedTree.Error>(2);race({claimResults[0]=claim.discard()},{claimResults[1]=claim.discard()});assertEquals(NarStagedTree.Error.OK,claimResults[0]);assertEquals(NarStagedTree.Error.OK,claimResults[1]);assertEquals(1,claimBackend.discards)
        val treeBackend=FakeBackend().also{it.present(empty())};val treeSession=session(treeBackend);val tree=success(treeSession.stage(ROOT,"ghost"));var transfer:NarStagedTree.ConsumeResult?=null;var discard:NarStagedTree.Error?=null;race({transfer=treeSession.consume(tree)},{discard=tree.discard()});if(transfer!!.isSuccess()){assertEquals(NarStagedTree.Error.CONSUMED,discard);assertEquals(NarStagedTree.Error.OK,transfer!!.claim!!.discard())}else{assertEquals(NarStagedTree.Error.CLOSED,transfer!!.error);assertEquals(NarStagedTree.Error.OK,discard)};assertEquals(1,treeBackend.discards)
    }

    @Test fun nativeFactoryMapsCodesDefensivelyAndCachesDescription() {
        val token=ByteArray(88).also{it[0]=7};val paths=arrayOf("file");val types=intArrayOf(1);val sizes=longArrayOf(3);val ordinals=intArrayOf(0);val digests=digest("abc");val present=NarStagedTree.fromNativeBegin(2,0,0,7,11,token,paths,types,sizes,ordinals,digests);val handle=field(present,"handle") as NarStagedTree.Handle;token[0]=99;paths[0]="changed";digests[0]=99;val owned=field(handle,"token") as ByteArray;assertEquals(7,owned[0].toInt());val inventory=NarStagedTreeInventory.present("ghost",NarStagedTree.NativeBackend().describe(handle));assertTrue(inventory.isSuccess());assertEquals("file",inventory.entries()[0].path());assertEquals(digest("abc")[0],inventory.entries()[0].sha256()!![0])
        val invalid=NarStagedTree.fromNativeBegin(0,Int.MAX_VALUE,Int.MIN_VALUE,0,0,null, emptyArray(),intArrayOf(),longArrayOf(),intArrayOf(),byteArrayOf());assertEquals(NarStagedTree.Error.NATIVE,field(invalid,"primaryError"));assertEquals(NarStagedTree.Error.NATIVE,field(invalid,"cleanupError"));val absent=NarStagedTree.fromNativeBegin(1,0,0,7,11,null,emptyArray(),intArrayOf(),longArrayOf(),intArrayOf(),byteArrayOf());assertNull(field(absent,"primaryError"))
        for(malformed in arrayOf<Any?>(null,arrayOf("unexpected"),intArrayOf(1),longArrayOf(1),intArrayOf(0),byteArrayOf(0))){var absentPaths:Array<String>?= emptyArray();var absentTypes=intArrayOf();var absentSizes=longArrayOf();var absentOrdinals=intArrayOf();var absentDigests=byteArrayOf();when(malformed){null,is Array<*>->absentPaths=malformed as Array<String>?;is IntArray->if(malformed[0]==0)absentOrdinals=malformed else absentTypes=malformed;is LongArray->absentSizes=malformed;is ByteArray->absentDigests=malformed};val malformedAbsent=NarStagedTree.fromNativeBegin(1,0,0,7,11,null,absentPaths,absentTypes,absentSizes,absentOrdinals,absentDigests);assertEquals(NarStagedTree.Error.NATIVE,field(malformedAbsent,"primaryError"))}
        assertThrows(IllegalArgumentException::class.java){NarStagedTree.fromNativeBegin(2,0,0,7,11,ByteArray(87),emptyArray(),intArrayOf(),longArrayOf(),intArrayOf(),byteArrayOf())}
    }

    @Test fun nativeFactoryRejectsMalformedStateShapesAndRetainsCleanup() {
        val token=ByteArray(88).also{it[0]=9};val io=NarStagedTree.Error.IO.ordinal;val close=NarStagedTree.Error.CLOSE.ordinal;val absentWithToken=nativeResult(1,0,0,token);val absentWithError=nativeResult(1,io,close,null);val absentWithPrimaryOnly=nativeResult(1,io,0,null);val presentWithoutToken=nativeResult(2,0,0,null);val presentWithError=nativeResult(2,io,0,token);val presentWithCleanupOnly=nativeResult(2,0,close,token);val errorWithToken=nativeResult(0,io,close,token);val unknownWithToken=nativeResult(Int.MAX_VALUE,0,0,token)
        assertBeginFailure(absentWithToken,NarStagedTree.Error.NATIVE,NarStagedTree.Error.OK,true);assertBeginFailure(absentWithError,NarStagedTree.Error.IO,NarStagedTree.Error.CLOSE,false);assertBeginFailure(absentWithPrimaryOnly,NarStagedTree.Error.IO,NarStagedTree.Error.OK,false);assertBeginFailure(presentWithoutToken,NarStagedTree.Error.NATIVE,NarStagedTree.Error.OK,false);assertBeginFailure(presentWithError,NarStagedTree.Error.IO,NarStagedTree.Error.OK,true);assertBeginFailure(presentWithCleanupOnly,NarStagedTree.Error.NATIVE,NarStagedTree.Error.CLOSE,true);assertBeginFailure(errorWithToken,NarStagedTree.Error.IO,NarStagedTree.Error.CLOSE,true);assertBeginFailure(unknownWithToken,NarStagedTree.Error.NATIVE,NarStagedTree.Error.OK,true)
        for(begun in arrayOf(absentWithToken,presentWithError,presentWithCleanupOnly,errorWithToken,unknownWithToken)){val backend=FakeBackend();backend.handle=field(begun,"handle") as NarStagedTree.Handle;backend.begin=begun;backend.discardResults=arrayOf(NarStagedTree.Error.PERMISSION,NarStagedTree.Error.OK);val result=session(backend).stage(ROOT,"ghost");assertFailure(field(begun,"primaryError") as NarStagedTree.Error,result);assertEquals(1,backend.discards);assertEquals(NarStagedTree.Error.PERMISSION,result.cleanup.discardError());assertEquals(NarStagedTree.Error.OK,result.cleanup.discard());assertEquals(NarStagedTree.Error.OK,result.cleanup.discard());assertEquals(2,backend.discards)}
    }

    @Test fun nativeErrorCodeMappingAndEnumPrefixStayExact() {
        val prefix=arrayOf(NarStagedTree.Error.OK,NarStagedTree.Error.INVALID_OPTIONS,NarStagedTree.Error.INVALID_TARGET,NarStagedTree.Error.ROOT_TYPE,NarStagedTree.Error.TARGET_TYPE,NarStagedTree.Error.SYMLINK,NarStagedTree.Error.SPECIAL_TYPE,NarStagedTree.Error.INVALID_NAME,NarStagedTree.Error.COMPONENT_LIMIT,NarStagedTree.Error.PATH_LIMIT,NarStagedTree.Error.DEPTH_LIMIT,NarStagedTree.Error.ENTRY_COUNT_LIMIT,NarStagedTree.Error.FILE_SIZE_LIMIT,NarStagedTree.Error.TOTAL_SIZE_LIMIT,NarStagedTree.Error.CYCLE,NarStagedTree.Error.TREE_CHANGED,NarStagedTree.Error.PERMISSION,NarStagedTree.Error.RESOURCE,NarStagedTree.Error.IO,NarStagedTree.Error.VISITOR,NarStagedTree.Error.CLOSE);assertEquals(29,NarStagedTree.Error.entries.size);assertEquals(28,NarStagedTree.Error.BUSY.ordinal);val mapper=NarStagedTree::class.java.getDeclaredMethod("fromNativeError",Int::class.javaPrimitiveType);mapper.isAccessible=true;prefix.forEachIndexed { code,error->assertEquals(code,error.ordinal);assertEquals(error,mapper.invoke(NarStagedTree,code)) };assertEquals(NarStagedTree.Error.INPUT,mapper.invoke(NarStagedTree,100));assertEquals(NarStagedTree.Error.NATIVE,mapper.invoke(NarStagedTree,101));assertEquals(NarStagedTree.Error.NATIVE,mapper.invoke(NarStagedTree,-1));assertEquals(NarStagedTree.Error.NATIVE,mapper.invoke(NarStagedTree,Int.MAX_VALUE))
    }

    @Test fun surfaceHasNoDestinationHandleGetterOrOverlayEndpoint() {
        assertNotNull(NarStagedTree::class.java.getAnnotation(Metadata::class.java));for(nested in NarStagedTree::class.java.declaredClasses){assertNotNull(nested.getAnnotation(Metadata::class.java));for(field in nested.declaredFields)assertFalse(forbidden(field.type))};assertMethods(NarStagedTree.Handle::class.java);assertMethods(NarStagedTree.Backend::class.java,"begin","describe","discard");assertMethods(NarStagedTree.BeginResult::class.java,"absent","failure","present");assertMethods(NarStagedTree.Stager::class.java,"session");assertMethods(NarStagedTree.Session::class.java,"consume","stage");assertMethods(NarStagedTree.Tree::class.java,"discard","entries","manifest");assertMethods(NarStagedTree.Claim::class.java,"consume","discard","lease","release","state");assertMethods(NarStagedTree.StageResult::class.java,"failure","getCleanup","getDetail","getError","getTree","isSuccess","success");assertMethods(NarStagedTree.Cleanup::class.java,"discard","discardError","nativeError");assertMethods(NarStagedTree.ConsumeResult::class.java,"failure","getClaim","getError","isSuccess","success");NarStagedTree.Backend::class.java.getDeclaredMethod("begin",Context::class.java,NarFilesystemInspector.TrustedRoot::class.java,CharSequence::class.java)
    }

    private fun assertMethods(type:Class<*>,vararg expected:String){val actual=ArrayList<String>();for(method in type.declaredMethods){if(method.isSynthetic)continue;actual+=method.name;assertFalse(method.name.matches(Regex("(finalize|publish|overlay|path|token|handle)")));assertFalse(forbidden(method.returnType));method.parameterTypes.forEach{assertFalse(forbidden(it))}};Collections.sort(actual);expected.sort();assertEquals(expected.toList(),actual)}
    private fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).also{it.isAccessible=true}.get(owner)
    private fun nativeResult(state:Int,error:Int,cleanup:Int,token:ByteArray?)=NarStagedTree.fromNativeBegin(state,error,cleanup,7,11,token, emptyArray(),intArrayOf(),longArrayOf(),intArrayOf(),byteArrayOf())
    private fun presentWithNullHandle():NarStagedTree.BeginResult { val constructor=NarStagedTree.BeginResult::class.java.declaredConstructors.single { it.parameterTypes.size == 6 };constructor.isAccessible=true;return constructor.newInstance(2,0L,0L,null,null,NarStagedTree.Error.OK) as NarStagedTree.BeginResult }
    private fun assertBeginFailure(result:NarStagedTree.BeginResult,error:NarStagedTree.Error,cleanup:NarStagedTree.Error,hasHandle:Boolean){assertEquals(error,field(result,"primaryError"));assertEquals(cleanup,field(result,"cleanupError"));assertEquals(hasHandle,field(result,"handle")!=null)}
    private fun forbidden(type:Class<*>):Boolean {val name=type.name;return name=="java.io.File"||name.startsWith("java.nio.file")||name.contains("InputStream")||name.contains("OutputStream")||name.contains("Reader")||name.contains("Writer")}
    private fun race(first:()->Unit,second:()->Unit){val start=CountDownLatch(1);val one=Thread{await(start);first()};val two=Thread{await(start);second()};one.start();two.start();start.countDown();one.join(5000);two.join(5000);assertFalse(one.isAlive);assertFalse(two.isAlive)}
    private fun await(latch:CountDownLatch){try{latch.await()}catch(error:InterruptedException){throw AssertionError(error)}}
    private fun session(backend:FakeBackend)=NarStagedTree.Stager(backend).session(CONTEXT)
    private fun claim(backend:FakeBackend,target:String):NarStagedTree.Claim {val session=session(backend);return session.consume(success(session.stage(ROOT,target))).claim!!}
    private fun success(result:NarStagedTree.StageResult):NarStagedTree.Tree {assertTrue(result.detail,result.isSuccess());return result.tree!!}
    private fun assertFailure(expected:NarStagedTree.Error,result:NarStagedTree.StageResult){assertFalse(result.isSuccess());assertEquals(expected,result.error);assertNull(result.tree)}
    private fun failingDescription(failure:Throwable)=FakeBackend().also{it.present(empty());it.describeFailure=failure}
    private fun empty()=description(emptyArray(),intArrayOf(),longArrayOf(),intArrayOf(),byteArrayOf())
    private fun description(paths:Array<String>?,types:IntArray?,sizes:LongArray?,ordinals:IntArray?,digests:ByteArray?)=NarStagedTreeInventory.Description(7,11,paths,types,sizes,ordinals,digests)
    private fun flat(vararg values:ByteArray)=ByteArray(values.size*32).also{result->values.forEachIndexed{index,value->value.copyInto(result,index*32)}}
    private fun digest(value:String):ByteArray=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private class FakeHandle:NarStagedTree.Handle
    private class FakeBackend:NarStagedTree.Backend { var handle:NarStagedTree.Handle=FakeHandle();var begin:NarStagedTree.BeginResult?=null;var description:NarStagedTreeInventory.Description?=null;var beginFailure:Throwable?=null;var describeFailure:Throwable?=null;var discardFailure:Throwable?=null;var discardResults:Array<NarStagedTree.Error?> = arrayOf(NarStagedTree.Error.OK);var discardIndex=0;var discards=0
        fun present(value:NarStagedTreeInventory.Description?){begin=NarStagedTree.BeginResult.present(handle);description=value;discardIndex=0}
        override fun begin(context:Context,root:NarFilesystemInspector.TrustedRoot,target:CharSequence):NarStagedTree.BeginResult? {assertSame(CONTEXT,context);assertSame(ROOT,root);throwIfNeeded(beginFailure);return begin}
        override fun describe(supplied:NarStagedTree.Handle):NarStagedTreeInventory.Description {assertSame(handle,supplied);throwIfNeeded(describeFailure);return description!!}
        override fun discard(context:Context,supplied:NarStagedTree.Handle):NarStagedTree.Error? {assertSame(CONTEXT,context);assertSame(handle,supplied);discards++;throwIfNeeded(discardFailure);return discardResults[minOf(discardIndex++,discardResults.size-1)]}
        private fun throwIfNeeded(failure:Throwable?){when(failure){is RuntimeException->throw failure;is Error->throw failure}}
    }
    private companion object {
        // These unit tests only pass Context through the fake backend by
        // identity; no Android Context API is invoked.  Allocation without a
        // constructor keeps the JVM test independent from android.jar stubs.
        val CONTEXT: Context = inertContext()
        val ROOT = NarFilesystemInspector.TrustedRoot("/trusted")

        private fun inertContext(): Context {
            val field = Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            return (field.get(null) as Unsafe).allocateInstance(MockContext::class.java) as Context
        }
    }
}
