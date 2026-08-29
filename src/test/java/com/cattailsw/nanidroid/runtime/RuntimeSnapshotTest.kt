package com.cattailsw.nanidroid.runtime

import androidx.compose.ui.unit.IntOffset
import com.cattailsw.nanidroid.SurfaceAnimation
import com.cattailsw.nanidroid.SurfaceAnimationFrame
import com.cattailsw.nanidroid.SurfaceCatalog
import com.cattailsw.nanidroid.SurfaceCollision
import com.cattailsw.nanidroid.SurfaceDefinition
import com.cattailsw.nanidroid.SurfaceElement
import com.cattailsw.nanidroid.surface.CollisionShape
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueActionKey
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueRuntimeState
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.GhostRuntimeMode
import com.cattailsw.nanidroid.runtime.dialogue.InputBehavior
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeAnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeChoiceAction
import com.cattailsw.nanidroid.runtime.dialogue.RuntimeInputAction
import java.io.File
import java.io.FileDescriptor
import java.lang.reflect.Array
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Exchanger
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.Phaser
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Supplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSnapshotTest {
    @Test
    fun equalCuePayloadsRemainDistinctByCueIdentity() {
        val lease = RuntimeHostLease(RuntimeHostId(7), 3)
        val first = RuntimePresentationCue(1, 9, lease, GhostSpeaker.SAKURA, RuntimeCueKind.ONE_SHOT, "2")
        val second = first.copy(cueId = 2)

        assertNotEquals(first, second)
    }

    @Test
    fun staleHostAndDialogueActionIdentitiesDoNotMatchCurrentCommands() {
        assertNotEquals(RuntimeHostLease(RuntimeHostId(7), 3), RuntimeHostLease(RuntimeHostId(7), 4))
        assertNotEquals(
            DialogueActionKey(generation = 9, incarnation = 2, actionId = 4),
            DialogueActionKey(generation = 9, incarnation = 3, actionId = 4),
        )
        assertNotEquals(
            RuntimeChoiceAction(
                DialogueActionKey(9, 2, 1),
                DialogueAction.Normal("Choice", "choice", listOf("one")),
            ),
            RuntimeChoiceAction(
                DialogueActionKey(9, 2, 2),
                DialogueAction.Normal("Choice", "choice", listOf("one")),
            ),
        )
    }

    @Test
    fun snapshotFreezeCopiesIndependentlyEqualSurfaceCatalogs() {
        val sourceDefinitions = surfaceDefinitions()
        val sourceDefinition = sourceDefinitions.getValue("0")
        val sourceCatalog = SurfaceCatalog.freeze(sourceDefinitions)
        val independentlyConstructedCatalog = SurfaceCatalog.freeze(surfaceDefinitions())

        val snapshot = RuntimeSnapshot.freeze(mutableSnapshot(activeSurfaces = sourceCatalog))
        (sourceDefinition.collisions as MutableList<SurfaceCollision>).clear()
        sourceDefinitions.clear()

        assertEquals(independentlyConstructedCatalog, sourceCatalog)
        assertEquals(independentlyConstructedCatalog.hashCode(), sourceCatalog.hashCode())
        assertEquals(independentlyConstructedCatalog, snapshot.activeSurfaces)
        assertNotSame(sourceCatalog, snapshot.activeSurfaces)
    }

    @Test
    fun snapshotFactoryDefensivelyCopiesEveryReachableCollection() {
        val source = mutableSnapshot()
        val expected = expectedSnapshot(requireNotNull(source.activeSurfaces))
        val snapshot = RuntimeSnapshot.freeze(source)
        val published = snapshot
        val sourcePendingInput = requireNotNull(source.dialogue.state.pendingInput)
        val sourceInputAction = requireNotNull(source.dialogue.input)

        (source.catalog.lastProvenEntries as MutableList<RuntimeGhostMetadata>).clear()
        (source.catalog.publications as MutableMap<CatalogPublicationToken, RuntimeCatalogPublicationStatus>).clear()
        (source.cues as MutableList<RuntimePresentationCue>).clear()
        (source.dialogue.state.contents[0].segments as MutableList<DialogueSegment>).clear()
        ((source.dialogue.state.pendingChoices[0] as DialogueAction.Normal).extraReferences as MutableList<String>).clear()
        ((source.dialogue.anchors[0].action as AnchorAction.DirectEvent).references as MutableList<String>).clear()
        (source.dialogue.state.contents as MutableList<DialogueContent>).clear()
        (source.dialogue.state.pendingChoices as MutableList<DialogueAction>).clear()
        (sourcePendingInput.spec.behaviorOptions as MutableSet<InputBehavior>).clear()
        (sourcePendingInput.spec.extraReferences as MutableList<String>).clear()
        (sourcePendingInput.spec.unknownOptions as MutableList<String>).clear()
        (source.dialogue.choices as MutableList<RuntimeChoiceAction>).clear()
        (source.dialogue.anchors as MutableList<RuntimeAnchorAction>).clear()
        (sourceInputAction.pending.spec.behaviorOptions as MutableSet<InputBehavior>).clear()
        (sourceInputAction.pending.spec.extraReferences as MutableList<String>).clear()
        (sourceInputAction.pending.spec.unknownOptions as MutableList<String>).clear()

        assertFrozenAndUnchanged(published, expected)
    }

    @Test
    fun dialogueActionCollectionsRejectMutation() {
        val snapshot = RuntimeSnapshot.freeze(mutableSnapshot())

        assertUnsupported { (snapshot.dialogue.state.contents as MutableList<DialogueContent>).clear() }
        assertUnsupported { (snapshot.dialogue.choices as MutableList<RuntimeChoiceAction>).clear() }
        assertUnsupported { (snapshot.dialogue.anchors as MutableList<RuntimeAnchorAction>).clear() }
        assertUnsupported {
            ((snapshot.dialogue.choices.single().action as DialogueAction.Normal).extraReferences as MutableList<String>)
                .clear()
        }
    }

    @Test
    fun presentationPreservesSpeakerTextSurfaceCueAndBalloonPolicy() {
        val snapshot = RuntimeSnapshot.freeze(
            RuntimeSnapshot.initial().copy(
                generation = 9L,
                presentation = RuntimePresentation(
                    sakura = RuntimeSpeakerPresentation("Sakura text", "120", 3L, true),
                    kero = RuntimeSpeakerPresentation("Kero text", "11", 4L, true),
                    talkingAnimationEnabled = true,
                ),
                cues = listOf(
                    RuntimePresentationCue(
                        cueId = 1L,
                        generation = 9L,
                        hostLease = RuntimeHostLease(RuntimeHostId(7L), 3L),
                        speaker = GhostSpeaker.SAKURA,
                        kind = RuntimeCueKind.ONE_SHOT,
                        animationId = "3",
                    ),
                ),
            ),
        )

        assertEquals("Sakura text", snapshot.presentation.sakura.text)
        assertEquals("120", snapshot.presentation.sakura.surfaceId)
        assertTrue(snapshot.presentation.sakura.balloonVisible)
        assertEquals("Kero text", snapshot.presentation.kero.text)
        assertEquals("11", snapshot.presentation.kero.surfaceId)
        assertTrue(snapshot.presentation.kero.balloonVisible)
        assertEquals("3", snapshot.cues.single().animationId)
    }

    @Test
    fun emptyTextAndDisabledBalloonRemainHidden() {
        val presentation = RuntimePresentation(
            sakura = RuntimeSpeakerPresentation("", "0", 0L, false),
            kero = RuntimeSpeakerPresentation("", "10", 0L, false),
            talkingAnimationEnabled = false,
        )

        assertFalse(presentation.sakura.balloonVisible)
        assertFalse(presentation.kero.balloonVisible)
        assertTrue(presentation.sakura.text.isEmpty())
        assertTrue(presentation.kero.text.isEmpty())
    }

    @Test
    fun snapshotGraphContainsNoViewOrCallback() {
        val snapshot = RuntimeSnapshot.freeze(mutableSnapshot())
        val forbidden = setOf(
            android.content.Context::class.java,
            android.app.Activity::class.java,
            File::class.java,
            FileDescriptor::class.java,
            Lock::class.java,
        )

        RuntimeSnapshot::class.java.declaredFields.forEach { field ->
            assertTrue("forbidden ${field.type}", forbidden.none { it.isAssignableFrom(field.type) })
        }
        assertSnapshotValuesContainNoForbiddenObjects(snapshot)
    }

    @Test
    fun recursiveSnapshotAuditRejectsForbiddenNestedCallbackFields() {
        val failure = captureAssertion {
            assertSnapshotValuesContainNoForbiddenObjects(RunnableProbe(Runnable {}))
        }

        assertTrue(failure.message.orEmpty().startsWith("forbidden snapshot value"))
    }

    @Test
    fun recursiveSnapshotAuditRejectsNestedCallbackAndSynchronizationFamilies() {
        listOf(
            ConsumerProbe(Consumer<String> { }),
            SupplierProbe(Supplier { "value" }),
            KotlinFunctionProbe { },
            CallableProbe(Callable { "value" }),
            LatchProbe(CountDownLatch(1)),
            SemaphoreProbe(Semaphore(1)),
            AtomicProbe(AtomicLong(1)),
            LockProbe(ReentrantLock()),
            ThreadProbe(Thread {}),
            ExecutorProbe(Executor { }),
            FutureProbe(CompletableFuture.completedFuture("value")),
        ).forEach(::assertForbiddenNestedValue)
    }

    @Test
    fun recursiveSnapshotAuditVisitsEveryCollectionBearingSnapshotVariant() {
        catalogVariants().forEach { catalog ->
            assertSnapshotValuesContainNoForbiddenObjects(
                RuntimeSnapshot.freeze(mutableSnapshot().copy(catalog = catalog)),
            )
        }
        assertSnapshotValuesContainNoForbiddenObjects(dialogueVariantSnapshot())
    }

    private fun assertFrozenAndUnchanged(snapshot: RuntimeSnapshot, expected: RuntimeSnapshot) {

        assertUnsupported { (snapshot.catalog.lastProvenEntries as MutableList<RuntimeGhostMetadata>).clear() }
        assertUnsupported {
            (snapshot.catalog.publications as MutableMap<CatalogPublicationToken, RuntimeCatalogPublicationStatus>).clear()
        }
        assertUnsupported { (snapshot.cues as MutableList<RuntimePresentationCue>).clear() }
        assertUnsupported {
            (snapshot.activeSurfaces!!.definitionsForTesting() as MutableMap<String, SurfaceDefinition>).clear()
        }
        val definition = snapshot.activeSurfaces!!.definitionsForTesting().getValue("0")
        assertUnsupported { (definition.collisions as MutableList<SurfaceCollision>).clear() }
        assertUnsupported { (definition.animations as MutableList<SurfaceAnimation>).clear() }
        assertUnsupported { (definition.elements as MutableList<SurfaceElement>).clear() }
        assertUnsupported { (definition.animations[0].frames as MutableList<SurfaceAnimationFrame>).clear() }
        assertUnsupported { (definition.animations[0].alternativeAnimationIds as MutableList<String>).clear() }
        assertUnsupported {
            ((definition.collisions[0].shape as CollisionShape.Polygon).points as MutableList<IntOffset>).clear()
        }
        assertUnsupported { (snapshot.dialogue.state.contents as MutableList<DialogueContent>).clear() }
        assertUnsupported { (snapshot.dialogue.state.contents[0].segments as MutableList<DialogueSegment>).clear() }
        assertUnsupported { (snapshot.dialogue.state.pendingChoices as MutableList<DialogueAction>).clear() }
        assertUnsupported {
            ((snapshot.dialogue.state.pendingChoices[0] as DialogueAction.Normal).extraReferences as MutableList<String>).clear()
        }
        assertUnsupported { (snapshot.dialogue.choices as MutableList<RuntimeChoiceAction>).clear() }
        assertUnsupported { (snapshot.dialogue.anchors as MutableList<RuntimeAnchorAction>).clear() }
        assertUnsupported {
            ((snapshot.dialogue.anchors[0].action as AnchorAction.DirectEvent).references as MutableList<String>).clear()
        }
        assertUnsupported {
            (snapshot.dialogue.state.pendingInput!!.spec.behaviorOptions as MutableSet<InputBehavior>).clear()
        }
        assertUnsupported { (snapshot.dialogue.state.pendingInput!!.spec.extraReferences as MutableList<String>).clear() }
        assertUnsupported { (snapshot.dialogue.state.pendingInput!!.spec.unknownOptions as MutableList<String>).clear() }
        assertUnsupported {
            (snapshot.dialogue.input!!.pending.spec.behaviorOptions as MutableSet<InputBehavior>).clear()
        }
        assertUnsupported { (snapshot.dialogue.input!!.pending.spec.extraReferences as MutableList<String>).clear() }
        assertUnsupported { (snapshot.dialogue.input!!.pending.spec.unknownOptions as MutableList<String>).clear() }

        assertEquals(expected, snapshot)
        assertFalse(snapshot.catalog.lastProvenEntries.isEmpty())
        assertFalse(snapshot.cues.isEmpty())
        assertFalse(snapshot.dialogue.choices.isEmpty())
        assertFalse(snapshot.dialogue.anchors.isEmpty())
    }

    private fun mutableSnapshot(
        activeSurfaces: SurfaceCatalog = frozenSurfaceCatalog(),
    ): RuntimeSnapshot {
        val choice = DialogueAction.Normal("Choice", "choice", mutableListOf("one"))
        val anchor = AnchorAction.DirectEvent("Anchor", "OnAnchor", mutableListOf("two"))
        val input = PendingInputState(
            generation = 9,
            spec = InputBoxSpec(
                dispatch = InputDispatch.Normal("input"),
                timeoutMillis = 100,
                initialText = "draft",
                behaviorOptions = mutableSetOf(InputBehavior.NO_EMPTY),
                supplement = "supplement",
                extraReferences = mutableListOf("three"),
                unknownOptions = mutableListOf("four"),
            ),
            deadlineElapsedMillis = 200,
        )
        val key = DialogueActionKey(9, 2, 1)
        return RuntimeSnapshot(
            revision = 1,
            generation = 9,
            phase = com.cattailsw.nanidroid.GhostRuntimePhase.Attached,
            activeGhostId = "ghost",
            activeSurfaces = activeSurfaces,
            pending = RuntimePendingGhostIdentity(3, "next", "/ghost/next"),
            catalog = RuntimeCatalogState.Ready(
                epoch = 4,
                entries = mutableListOf(RuntimeGhostMetadata("ghost", "/ghost", "Ghost", "Sakura", "/ghost/readme.txt")),
                publications = mutableMapOf(
                    CatalogPublicationToken("install", "1") to RuntimeCatalogPublicationStatus.Pending("ghost", 4),
                ),
            ),
            presentation = RuntimePresentation(
                sakura = RuntimeSpeakerPresentation("Sakura", "0", 1, true),
                kero = RuntimeSpeakerPresentation("Kero", "10", 2, false),
                talkingAnimationEnabled = true,
            ),
            cues = mutableListOf(RuntimePresentationCue(5, 9, RuntimeHostLease(RuntimeHostId(7), 3), GhostSpeaker.SAKURA, RuntimeCueKind.TALKING, "0")),
            dialogue = RuntimeDialogueSnapshot(
                state = DialogueRuntimeState(
                    revision = 6,
                    incarnation = 2,
                    talkId = 8,
                    contents = mutableListOf(
                        DialogueContent(
                            GhostSpeaker.SAKURA,
                            mutableListOf(
                                DialogueSegment.Choice(choice),
                                DialogueSegment.Anchor(anchor),
                                DialogueSegment.InputBox(input.spec),
                            ),
                        ),
                    ),
                    pendingChoices = mutableListOf(choice),
                    pendingInput = input,
                ),
                choices = mutableListOf(RuntimeChoiceAction(key, choice)),
                anchors = mutableListOf(RuntimeAnchorAction(key.copy(actionId = 2), anchor)),
                input = RuntimeInputAction(key.copy(actionId = 3), input),
            ),
            mode = GhostRuntimeMode(playingTalk = false, pendingUserAction = true, passive = false),
            modeIdentity = RuntimeModeIdentity(9, 7, null, null),
            clockRunning = true,
            foregroundHost = RuntimeHostLease(RuntimeHostId(7), 3),
            exit = RuntimeExitSnapshot(10, 9, null),
            notice = RuntimeNotice(11, RuntimeNoticeCode.REQUEST_FAILED),
        )
    }

    private fun expectedSnapshot(activeSurfaces: SurfaceCatalog): RuntimeSnapshot {
        val choice = DialogueAction.Normal("Choice", "choice", listOf("one"))
        val anchor = AnchorAction.DirectEvent("Anchor", "OnAnchor", listOf("two"))
        val input = PendingInputState(
            generation = 9,
            spec = InputBoxSpec(
                dispatch = InputDispatch.Normal("input"),
                timeoutMillis = 100,
                initialText = "draft",
                behaviorOptions = setOf(InputBehavior.NO_EMPTY),
                supplement = "supplement",
                extraReferences = listOf("three"),
                unknownOptions = listOf("four"),
            ),
            deadlineElapsedMillis = 200,
        )
        val key = DialogueActionKey(9, 2, 1)
        return RuntimeSnapshot(
            revision = 1,
            generation = 9,
            phase = com.cattailsw.nanidroid.GhostRuntimePhase.Attached,
            activeGhostId = "ghost",
            activeSurfaces = activeSurfaces,
            pending = RuntimePendingGhostIdentity(3, "next", "/ghost/next"),
            catalog = RuntimeCatalogState.Ready(
                epoch = 4,
                entries = listOf(RuntimeGhostMetadata("ghost", "/ghost", "Ghost", "Sakura", "/ghost/readme.txt")),
                publications = mapOf(
                    CatalogPublicationToken("install", "1") to RuntimeCatalogPublicationStatus.Pending("ghost", 4),
                ),
            ),
            presentation = RuntimePresentation(
                sakura = RuntimeSpeakerPresentation("Sakura", "0", 1, true),
                kero = RuntimeSpeakerPresentation("Kero", "10", 2, false),
                talkingAnimationEnabled = true,
            ),
            cues = listOf(RuntimePresentationCue(5, 9, RuntimeHostLease(RuntimeHostId(7), 3), GhostSpeaker.SAKURA, RuntimeCueKind.TALKING, "0")),
            dialogue = RuntimeDialogueSnapshot(
                state = DialogueRuntimeState(
                    revision = 6,
                    incarnation = 2,
                    talkId = 8,
                    contents = listOf(
                        DialogueContent(
                            GhostSpeaker.SAKURA,
                            listOf(
                                DialogueSegment.Choice(choice),
                                DialogueSegment.Anchor(anchor),
                                DialogueSegment.InputBox(input.spec),
                            ),
                        ),
                    ),
                    pendingChoices = listOf(choice),
                    pendingInput = input,
                ),
                choices = listOf(RuntimeChoiceAction(key, choice)),
                anchors = listOf(RuntimeAnchorAction(key.copy(actionId = 2), anchor)),
                input = RuntimeInputAction(key.copy(actionId = 3), input),
            ),
            mode = GhostRuntimeMode(playingTalk = false, pendingUserAction = true, passive = false),
            modeIdentity = RuntimeModeIdentity(9, 7, null, null),
            clockRunning = true,
            foregroundHost = RuntimeHostLease(RuntimeHostId(7), 3),
            exit = RuntimeExitSnapshot(10, 9, null),
            notice = RuntimeNotice(11, RuntimeNoticeCode.REQUEST_FAILED),
        )
    }

    private fun frozenSurfaceCatalog(): SurfaceCatalog = SurfaceCatalog.freeze(surfaceDefinitions())

    private fun surfaceDefinitions(): MutableMap<String, SurfaceDefinition> = mutableMapOf(
        "0" to SurfaceDefinition(
            id = 0,
            type = 0,
            imagePath = "surface0.png",
            fallbackImagePath = null,
            width = 10,
            height = 10,
            collisions = mutableListOf(
                SurfaceCollision(
                    id = 1,
                    identifier = "Face",
                    shape = CollisionShape.Polygon(mutableListOf(IntOffset(0, 0), IntOffset(1, 0), IntOffset(0, 1))),
                    authoredOrder = 1,
                ),
            ),
            animations = mutableListOf(
                SurfaceAnimation(
                    id = "talk",
                    interval = 1,
                    exclusive = false,
                    frames = mutableListOf(SurfaceAnimationFrame(0, null, null, 0, 1, 0, 0, 1, 1)),
                    alternativeAnimationIds = mutableListOf("alt"),
                ),
            ),
            elements = mutableListOf(SurfaceElement(0, null, 0, 0, 1, 1)),
        ),
    )

    private fun catalogVariants(): List<RuntimeCatalogState> {
        val metadata = RuntimeGhostMetadata("ghost", "/ghost", "Ghost", "Sakura", "/ghost/readme.txt")
        return listOf(
            RuntimeCatalogState.Loading(
                epoch = 1,
                lastProvenEntries = mutableListOf(metadata),
                publications = mutableMapOf(
                    CatalogPublicationToken("loading", "1") to RuntimeCatalogPublicationStatus.Pending("ghost", 1),
                ),
            ),
            RuntimeCatalogState.Ready(
                epoch = 2,
                entries = mutableListOf(metadata),
                publications = mutableMapOf(
                    CatalogPublicationToken("ready", "2") to RuntimeCatalogPublicationStatus.Ready("ghost", 2),
                ),
            ),
            RuntimeCatalogState.Failed(
                epoch = 3,
                lastProvenEntries = mutableListOf(metadata),
                publications = mutableMapOf(
                    CatalogPublicationToken("failed", "3") to RuntimeCatalogPublicationStatus.RecoveryRequired(
                        targetId = "ghost",
                        failedEpoch = 3,
                        reason = RuntimeNoticeCode.CATALOG_TARGET_MISSING,
                    ),
                ),
                reason = RuntimeNoticeCode.CATALOG_SCAN_FAILED,
            ),
        )
    }

    private fun dialogueVariantSnapshot(): RuntimeSnapshot {
        val normal = DialogueAction.Normal("Normal", "normal", mutableListOf("normal-ref"))
        val direct = DialogueAction.DirectEvent("Direct", "OnDirect", mutableListOf("direct-ref"))
        val script = DialogueAction.Script("Script", "\\hscript\\e")
        val anchorNormal = AnchorAction.Normal("Anchor", "anchor", mutableListOf("anchor-ref"))
        val anchorDirect = AnchorAction.DirectEvent("Anchor direct", "OnAnchor", mutableListOf("anchor-direct-ref"))
        val input = PendingInputState(
            generation = 9,
            spec = InputBoxSpec(
                dispatch = InputDispatch.DirectEvent("OnInput"),
                timeoutMillis = null,
                initialText = "",
                behaviorOptions = mutableSetOf(InputBehavior.MULTILINE),
                supplement = "",
                extraReferences = mutableListOf("input-ref"),
                unknownOptions = mutableListOf("unknown"),
            ),
            deadlineElapsedMillis = 3,
        )
        val key = DialogueActionKey(9, 4, 1)
        return RuntimeSnapshot.freeze(
            mutableSnapshot().copy(
                dialogue = RuntimeDialogueSnapshot(
                    state = DialogueRuntimeState(
                        revision = 4,
                        incarnation = 4,
                        talkId = 4,
                        contents = mutableListOf(
                            DialogueContent(
                                GhostSpeaker.KERO,
                                mutableListOf(
                                    DialogueSegment.Text("text"),
                                    DialogueSegment.NewLine,
                                    DialogueSegment.Wait(1),
                                    DialogueSegment.Clear,
                                    DialogueSegment.SpeakerChangeClear,
                                    DialogueSegment.Choice(normal),
                                    DialogueSegment.Choice(direct),
                                    DialogueSegment.Choice(script),
                                    DialogueSegment.Anchor(anchorNormal),
                                    DialogueSegment.Anchor(anchorDirect),
                                    DialogueSegment.ExternalUrl("link", "https://example.test"),
                                    DialogueSegment.InputBox(input.spec),
                                    DialogueSegment.PassiveMode(true),
                                ),
                            ),
                        ),
                        pendingChoices = mutableListOf(normal, direct, script),
                        pendingInput = input,
                    ),
                    choices = mutableListOf(
                        RuntimeChoiceAction(key, normal),
                        RuntimeChoiceAction(key.copy(actionId = 2), direct),
                        RuntimeChoiceAction(key.copy(actionId = 3), script),
                    ),
                    anchors = mutableListOf(
                        RuntimeAnchorAction(key.copy(actionId = 4), anchorNormal),
                        RuntimeAnchorAction(key.copy(actionId = 5), anchorDirect),
                    ),
                    input = RuntimeInputAction(key.copy(actionId = 6), input),
                ),
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertSnapshotValuesContainNoForbiddenObjects(root: Any?) {
        val visited = IdentityHashMap<Any, Unit>()

        fun visit(value: Any?) {
            if (value == null || visited.put(value, Unit) != null) return
            assertPermitted(value)

            when {
                value.javaClass.isArray -> {
                    repeat(Array.getLength(value)) { index -> visit(Array.get(value, index)) }
                }
                value is Map<*, *> -> {
                    assertUnsupported { (value as MutableMap<Any?, Any?>).clear() }
                    value.forEach { (key, item) ->
                        visit(key)
                        visit(item)
                    }
                }
                value is Collection<*> -> {
                    assertUnsupported { (value as MutableCollection<Any?>).clear() }
                    value.forEach(::visit)
                }
                value is Iterable<*> -> value.forEach(::visit)
            }

            if (value.javaClass.name.startsWith("com.cattailsw.nanidroid.")) {
                value.javaClass.declaredFields
                    .asSequence()
                    .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                    .forEach { field ->
                        field.isAccessible = true
                        visit(field.get(value))
                    }
            }
        }

        visit(root)
    }

    private fun assertPermitted(value: Any) {
        val name = value.javaClass.name
        val forbidden = value is android.content.Context ||
            value is android.app.Activity ||
            value is File ||
            value is FileDescriptor ||
            value is Throwable ||
            value is Thread ||
            value is Lock ||
            value is ReadWriteLock ||
            value is Condition ||
            value is Function<*> ||
            value is Runnable ||
            value is Callable<*> ||
            value is Executor ||
            value is Future<*> ||
            value is CompletionStage<*> ||
            value is CountDownLatch ||
            value is Semaphore ||
            value is CyclicBarrier ||
            value is Phaser ||
            value is Exchanger<*> ||
            value.javaClass.implementsJavaFunctionInterface() ||
            name.startsWith("java.util.concurrent.atomic.") ||
            name.startsWith("java.util.concurrent.locks.") ||
            name.contains("Callback") ||
            name.contains("Listener") ||
            name.contains("Adapter") ||
            name.contains("Builder") ||
            name.contains("Native") ||
            name.contains("Handle") ||
            name.contains("Synchronizer")
        if (forbidden) throw AssertionError("forbidden snapshot value $name")
    }

    private fun Class<*>.implementsJavaFunctionInterface(): Boolean =
        name.startsWith("java.util.function.") ||
            interfaces.any { it.implementsJavaFunctionInterface() } ||
            superclass?.implementsJavaFunctionInterface() == true

    private data class RunnableProbe(val callback: Runnable)

    private data class ConsumerProbe(val callback: Consumer<String>)

    private data class SupplierProbe(val callback: Supplier<String>)

    private data class KotlinFunctionProbe(val callback: () -> Unit)

    private data class CallableProbe(val callback: Callable<String>)

    private data class LatchProbe(val synchronization: CountDownLatch)

    private data class SemaphoreProbe(val synchronization: Semaphore)

    private data class AtomicProbe(val synchronization: AtomicLong)

    private data class LockProbe(val synchronization: ReentrantLock)

    private data class ThreadProbe(val handle: Thread)

    private data class ExecutorProbe(val handle: Executor)

    private data class FutureProbe(val handle: Future<String>)

    private fun assertForbiddenNestedValue(value: Any) {
        val failure = captureAssertion { assertSnapshotValuesContainNoForbiddenObjects(value) }
        assertTrue(failure.message.orEmpty().startsWith("forbidden snapshot value"))
    }

    private fun captureAssertion(block: () -> Unit): AssertionError = try {
        block()
        throw AssertionError("expected forbidden value rejection")
    } catch (failure: AssertionError) {
        failure
    }

    private fun assertUnsupported(block: () -> Unit) {
        try {
            block()
        } catch (_: UnsupportedOperationException) {
            return
        }
        throw AssertionError("expected UnsupportedOperationException")
    }
}
