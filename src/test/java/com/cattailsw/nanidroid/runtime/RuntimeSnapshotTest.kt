package com.cattailsw.nanidroid.runtime

import com.cattailsw.nanidroid.SurfaceCatalog
import com.cattailsw.nanidroid.SurfaceAnimation
import com.cattailsw.nanidroid.SurfaceAnimationFrame
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
import java.util.concurrent.locks.Lock
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun snapshotGraphContainsNoFileAndroidNativeOrCallbackObjects() {
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
        assertSnapshotValuesContainNoForbiddenObjects(snapshot, forbidden)
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

    private fun mutableSnapshot(): RuntimeSnapshot {
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
            activeSurfaces = frozenSurfaceCatalog(),
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

    private fun frozenSurfaceCatalog(): SurfaceCatalog = SurfaceCatalog.freeze(
        mutableMapOf(
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
        ),
    )

    private fun assertSnapshotValuesContainNoForbiddenObjects(
        snapshot: RuntimeSnapshot,
        forbidden: Set<Class<*>>,
    ) {
        fun assertAllowed(value: Any?) {
            if (value == null) return
            assertTrue("forbidden ${value.javaClass}", forbidden.none { it.isAssignableFrom(value.javaClass) })
        }

        assertAllowed(snapshot)
        assertAllowed(snapshot.activeSurfaces)
        assertAllowed(snapshot.pending)
        assertAllowed(snapshot.catalog)
        snapshot.catalog.lastProvenEntries.forEach(::assertAllowed)
        snapshot.catalog.publications.forEach { (token, status) ->
            assertAllowed(token)
            assertAllowed(status)
        }
        assertAllowed(snapshot.presentation)
        snapshot.cues.forEach(::assertAllowed)
        assertAllowed(snapshot.dialogue)
        assertAllowed(snapshot.dialogue.state)
        snapshot.dialogue.state.contents.forEach { content ->
            assertAllowed(content)
            content.segments.forEach { segment ->
                assertAllowed(segment)
                when (segment) {
                    is DialogueSegment.Choice -> assertAllowed(segment.action)
                    is DialogueSegment.Anchor -> assertAllowed(segment.action)
                    is DialogueSegment.InputBox -> assertAllowed(segment.spec)
                    else -> Unit
                }
            }
        }
        snapshot.dialogue.state.pendingChoices.forEach(::assertAllowed)
        snapshot.dialogue.state.pendingInput?.also {
            assertAllowed(it)
            assertAllowed(it.spec)
        }
        snapshot.dialogue.choices.forEach {
            assertAllowed(it)
            assertAllowed(it.key)
            assertAllowed(it.action)
        }
        snapshot.dialogue.anchors.forEach {
            assertAllowed(it)
            assertAllowed(it.key)
            assertAllowed(it.action)
        }
        snapshot.dialogue.input?.also {
            assertAllowed(it)
            assertAllowed(it.key)
            assertAllowed(it.pending)
            assertAllowed(it.pending.spec)
        }
        assertAllowed(snapshot.mode)
        assertAllowed(snapshot.modeIdentity)
        assertAllowed(snapshot.foregroundHost)
        assertAllowed(snapshot.exit)
        assertAllowed(snapshot.notice)
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
