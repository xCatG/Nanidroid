package com.cattailsw.nanidroid.compose.stage

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.compose.SurfaceSpeaker
import com.cattailsw.nanidroid.compose.GhostPresentationStage
import com.cattailsw.nanidroid.runtime.GhostPresentationReducer
import com.cattailsw.nanidroid.runtime.GhostSpeaker
import com.cattailsw.nanidroid.runtime.dialogue.AnchorAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueAction
import com.cattailsw.nanidroid.runtime.dialogue.DialogueContent
import com.cattailsw.nanidroid.runtime.dialogue.DialogueSegment
import com.cattailsw.nanidroid.runtime.dialogue.InputBoxSpec
import com.cattailsw.nanidroid.runtime.dialogue.InputDispatch
import com.cattailsw.nanidroid.runtime.dialogue.PendingInputState
import com.cattailsw.nanidroid.runtime.stage.BubbleInteractionTarget
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionFence
import com.cattailsw.nanidroid.runtime.stage.BubbleRegionSet
import com.cattailsw.nanidroid.runtime.stage.BubbleScrollOrigin
import com.cattailsw.nanidroid.runtime.stage.StageMode
import com.cattailsw.nanidroid.runtime.stage.StageDisplayFeature
import com.cattailsw.nanidroid.runtime.stage.StageDpRect
import com.cattailsw.nanidroid.runtime.stage.StageEnvironment
import com.cattailsw.nanidroid.runtime.stage.StageInputCapabilities
import com.cattailsw.nanidroid.runtime.stage.StagePosture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class GhostBubbleInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dialogue_live_region_announces_only_the_settled_typewriter_copy() {
        val state = mutableStateOf(
            BubbleUiState(
                speaker = SurfaceSpeaker.SAKURA,
                content = DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("H"))),
                pendingChoices = emptyList(),
                scrollPosition = 0,
                userScrolledThisTalk = false,
                talkId = 91L,
                contentRevision = 1L,
            ),
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { GhostBubble(state = state.value) }
        val bubble = composeRule.onNodeWithTag("ghost-bubble-sakura")

        composeRule.mainClock.advanceTimeBy(300)
        composeRule.runOnIdle {
            state.value = state.value.copy(
                content = DialogueContent(GhostSpeaker.SAKURA, listOf(DialogueSegment.Text("Hello"))),
                contentRevision = 2L,
            )
        }
        composeRule.mainClock.advanceTimeBy(499)
        assertTrue(SemanticsProperties.LiveRegion !in bubble.fetchSemanticsNode().config)

        composeRule.mainClock.advanceTimeBy(1)
        composeRule.waitForIdle()
        val semantics = bubble.fetchSemanticsNode().config
        assertEquals(LiveRegionMode.Polite, semantics[SemanticsProperties.LiveRegion])
        assertEquals(listOf("Hello"), semantics[SemanticsProperties.ContentDescription])
    }

    @Test
    fun reachedInputOnlyDialogueProvidesAnOwningBubbleControl() {
        val inputSpec = inputSpec("name")
        val inputSegment = DialogueSegment.InputBox(inputSpec)
        var opened: DialogueSegment.InputBox? = null

        composeRule.setContent {
            GhostBubble(
                state = BubbleUiState(
                    speaker = SurfaceSpeaker.SAKURA,
                    content = DialogueContent(GhostSpeaker.SAKURA, listOf(inputSegment)),
                    pendingChoices = emptyList(),
                    pendingInput = PendingInputState(1L, inputSpec, Long.MAX_VALUE),
                    scrollPosition = 0,
                    userScrolledThisTalk = false,
                    talkId = 1L,
                    contentRevision = 1L,
                ),
                onInput = { opened = it },
            )
        }

        composeRule.onNodeWithTag("ghost-bubble-input-sakura-0").performClick()
        composeRule.runOnIdle { assertSame(inputSegment, opened) }
    }

    @Test
    fun carriedInputWithoutCurrentSegmentProvidesOneFallbackControlAlongsideChoice() {
        val inputSpec = inputSpec("carried")
        val pending = PendingInputState(2L, inputSpec, Long.MAX_VALUE, GhostSpeaker.SAKURA)
        val choice = DialogueAction.Normal("Choose", "choose", emptyList())
        var opened: DialogueSegment.InputBox? = null

        composeRule.setContent {
            GhostBubble(
                state = BubbleUiState(
                    speaker = SurfaceSpeaker.SAKURA,
                    content = DialogueContent(
                        GhostSpeaker.SAKURA,
                        listOf(DialogueSegment.Text("interruption"), DialogueSegment.Choice(choice)),
                    ),
                    pendingChoices = listOf(choice),
                    pendingInput = pending,
                    scrollPosition = 0,
                    userScrolledThisTalk = false,
                    talkId = 2L,
                    contentRevision = 2L,
                ),
                onInput = { opened = it },
            )
        }

        composeRule.onAllNodesWithTag("ghost-bubble-input-sakura-0").assertCountEquals(1)
        composeRule.onAllNodesWithTag("ghost-bubble-choose-sakura").assertCountEquals(1)
        composeRule.onNodeWithTag("ghost-bubble-input-sakura-0").performClick()
        composeRule.runOnIdle { assertSame(inputSpec, opened?.spec) }
    }

    @Test
    fun typedControlsKeepExactRuntimeIdentityAndPublishActionsBeforeScrollAndFrame() {
        val normalAnchor = AnchorAction.Normal("Normal", "topic", listOf("one", "", "three"))
        val directAnchor = AnchorAction.DirectEvent("Direct", "OnDirect", listOf("alpha", "", "omega"))
        val inputSpec = inputSpec("name")
        val inputSegment = DialogueSegment.InputBox(inputSpec)
        val choice = DialogueAction.Normal("Yes", "yes", listOf("ref"))
        val frame = BubbleRegionFence(
            speaker = SurfaceSpeaker.KERO,
            talkId = 4L,
            contentRevision = 9L,
            frame = IntRect(100, 20, 1100, 1220),
        )
        var publication: BubbleRegionSet? = null
        var activatedNormal: AnchorAction? = null
        var activatedDirect: AnchorAction? = null
        var activatedUrl: String? = null
        var activatedInput: DialogueSegment.InputBox? = null
        var chooseCount = 0

        composeRule.setContent {
            CompositionLocalProvider(LocalBubbleRegionFence provides frame) {
                Box(Modifier.size(240.dp, 320.dp)) {
                    GhostBubble(
                        state = BubbleUiState(
                            speaker = SurfaceSpeaker.KERO,
                            content = DialogueContent(
                                GhostSpeaker.KERO,
                                listOf(
                                    DialogueSegment.Text("plain https://plain.example must stay text"),
                                    DialogueSegment.Anchor(normalAnchor),
                                    DialogueSegment.Anchor(directAnchor),
                                    DialogueSegment.ExternalUrl("Explicit", "https://explicit.example/path"),
                                    inputSegment,
                                    DialogueSegment.Choice(choice),
                                ),
                            ),
                            pendingChoices = listOf(choice),
                            pendingInput = PendingInputState(6L, inputSpec, Long.MAX_VALUE),
                            scrollPosition = 0,
                            userScrolledThisTalk = false,
                            talkId = 4L,
                            contentRevision = 9L,
                        ),
                        onRegionSet = { publication = it },
                        onAnchor = { action ->
                            if (action === normalAnchor) activatedNormal = action
                            if (action === directAnchor) activatedDirect = action
                        },
                        onExternalUrl = { activatedUrl = it },
                        onInput = { activatedInput = it },
                        onChoose = { chooseCount++ },
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("ghost-bubble-external-url-kero-2").assertCountEquals(1)
        composeRule.onAllNodesWithTag("ghost-bubble-choose-kero").assertCountEquals(1)
        composeRule.onNodeWithTag("ghost-bubble-anchor-kero-0").performClick()
        composeRule.onNodeWithTag("ghost-bubble-anchor-kero-1").performClick()
        composeRule.onNodeWithTag("ghost-bubble-external-url-kero-2").performClick()
        composeRule.onNodeWithTag("ghost-bubble-input-kero-3").performClick()
        composeRule.onNodeWithTag("ghost-bubble-input-kero-3").performClick()
        composeRule.onNodeWithTag("ghost-bubble-choose-kero").performClick()

        composeRule.waitUntil(5_000) { publication != null }
        val typedControlsViewport = measuredScrollViewport(frame, SurfaceSpeaker.KERO)
        composeRule.runOnIdle {
            assertSame(normalAnchor, activatedNormal)
            assertSame(directAnchor, activatedDirect)
            assertEquals(listOf("one", "", "three"), (activatedNormal as AnchorAction.Normal).extraReferences)
            assertEquals(listOf("alpha", "", "omega"), (activatedDirect as AnchorAction.DirectEvent).references)
            assertEquals("https://explicit.example/path", activatedUrl)
            assertSame(inputSegment, activatedInput)
            assertEquals(1, chooseCount)

            val published = requireNotNull(publication)
            assertEquals(frame, published.fence)
            assertEquals(
                listOf(
                    BubbleInteractionTarget.Anchor(normalAnchor),
                    BubbleInteractionTarget.Anchor(directAnchor),
                    BubbleInteractionTarget.ExternalUrl("https://explicit.example/path"),
                    BubbleInteractionTarget.Input(inputSegment),
                    BubbleInteractionTarget.Choice(choice),
                ),
                published.actionRegions.map { it.target },
            )
            published.actionRegions.forEach { region ->
                assertTrue(region.bounds.left >= frame.frame.left)
                assertTrue(region.bounds.top >= frame.frame.top)
                assertTrue(region.bounds.right <= frame.frame.right)
                assertTrue(region.bounds.bottom <= frame.frame.bottom)
                assertTrue(region.bounds.width > 0 && region.bounds.height > 0)
            }
            assertPublishedScrollViewport(frame, published, typedControlsViewport)
        }
    }

    @Test
    fun offscreenControlsAreOmittedWithoutSuppressingScrollAndFramePublication() {
        val anchors = (0 until 8).map { index ->
            AnchorAction.Normal("Anchor $index", "anchor-$index", emptyList())
        }
        val choice = DialogueAction.Normal("Trailing choice", "trailing", emptyList())
        val orderedTargets = anchors.map(BubbleInteractionTarget::Anchor) +
            BubbleInteractionTarget.Choice(choice)
        val frame = BubbleRegionFence(
            SurfaceSpeaker.SAKURA,
            talkId = 31L,
            contentRevision = 4L,
            frame = IntRect(0, 0, 720, 480),
        )
        var publication: BubbleRegionSet? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalBubbleRegionFence provides frame) {
                GhostBubble(
                    state = BubbleUiState(
                        speaker = SurfaceSpeaker.SAKURA,
                        content = DialogueContent(
                            GhostSpeaker.SAKURA,
                            anchors.map(DialogueSegment::Anchor) + DialogueSegment.Choice(choice),
                        ),
                        pendingChoices = listOf(choice),
                        scrollPosition = 0,
                        userScrolledThisTalk = true,
                        talkId = 31L,
                        contentRevision = 4L,
                    ),
                    onRegionSet = { publication = it },
                    modifier = Modifier.size(240.dp, 160.dp),
                )
            }
        }

        composeRule.waitUntil(5_000) { publication != null }
        val initialViewport = measuredScrollViewport(frame, SurfaceSpeaker.SAKURA)
        composeRule.runOnIdle {
            val initial = checkNotNull(publication)
            assertEquals(frame, initial.fence)
            assertPublishedScrollViewport(frame, initial, initialViewport)
            assertTrue(initial.actionRegions.isNotEmpty())
            assertTrue(initial.actionRegions.size < orderedTargets.size)
            val indices = initial.actionRegions.map { orderedTargets.indexOf(it.target) }
            assertEquals(indices.sorted(), indices)
        }

        composeRule.onNodeWithTag("ghost-bubble-scroll-sakura")
            .performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, 10_000f) }
        composeRule.waitUntil(5_000) {
            publication?.actionRegions?.any { it.target == BubbleInteractionTarget.Choice(choice) } == true
        }
        val scrolledViewport = measuredScrollViewport(frame, SurfaceSpeaker.SAKURA)
        composeRule.runOnIdle {
            val scrolled = checkNotNull(publication)
            assertEquals(frame, scrolled.fence)
            assertPublishedScrollViewport(frame, scrolled, scrolledViewport)
            val indices = scrolled.actionRegions.map { orderedTargets.indexOf(it.target) }
            assertTrue(indices.all { it >= 0 })
            assertEquals(indices.sorted(), indices)
        }
    }

    @Test
    fun measuredScrollViewportReplacesTheAuthoredFrameWhenTheBubbleIsConstrained() {
        val frame = BubbleRegionFence(
            SurfaceSpeaker.SAKURA,
            talkId = 32L,
            contentRevision = 5L,
            frame = IntRect(100, 20, 1100, 1220),
        )
        var publication: BubbleRegionSet? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalBubbleRegionFence provides frame) {
                GhostBubble(
                    state = BubbleUiState(
                        speaker = SurfaceSpeaker.SAKURA,
                        content = DialogueContent(
                            GhostSpeaker.SAKURA,
                            listOf(DialogueSegment.Text("Long enough to scroll\n".repeat(32))),
                        ),
                        pendingChoices = emptyList(),
                        scrollPosition = 0,
                        userScrolledThisTalk = false,
                        talkId = 32L,
                        contentRevision = 5L,
                    ),
                    onRegionSet = { publication = it },
                    modifier = Modifier.size(240.dp, 160.dp),
                )
            }
        }

        composeRule.waitUntil(5_000) { publication != null }
        val constrainedViewport = measuredScrollViewport(frame, SurfaceSpeaker.SAKURA)
        composeRule.runOnIdle {
            val viewport = requireNotNull(requireNotNull(publication).scrollViewport)
            assertPublishedScrollViewport(frame, requireNotNull(publication), constrainedViewport)
            assertTrue(viewport.width < frame.frame.width)
            assertTrue(viewport.height < frame.frame.height)
        }
    }

    @Test
    fun measuredScrollViewportPreservesTheLeftPointerInsetInStageCoordinates() {
        val frame = BubbleRegionFence(SurfaceSpeaker.KERO, 33L, 6L, IntRect(100, 20, 1100, 1220))
        var publication: BubbleRegionSet? = null
        composeRule.setContent {
            CompositionLocalProvider(
                LocalBubbleRegionFence provides frame,
                LocalBubblePointerDirection provides BubblePointerDirection.LEFT,
            ) {
                GhostBubble(
                    state = BubbleUiState(
                        speaker = SurfaceSpeaker.KERO,
                        content = DialogueContent(GhostSpeaker.KERO, listOf(DialogueSegment.Text("Scrollable\n".repeat(32)))),
                        pendingChoices = emptyList(),
                        scrollPosition = 0,
                        userScrolledThisTalk = false,
                        talkId = 33L,
                        contentRevision = 6L,
                    ),
                    onRegionSet = { publication = it },
                    modifier = Modifier.size(240.dp, 160.dp),
                )
            }
        }

        composeRule.waitUntil(5_000) { publication != null }
        val expectedViewport = measuredScrollViewport(frame, SurfaceSpeaker.KERO)
        composeRule.runOnIdle {
            val published = requireNotNull(publication)
            assertPublishedScrollViewport(frame, published, expectedViewport)
            assertTrue(requireNotNull(published.scrollViewport).left > frame.frame.left)
        }
    }

    @Test
    fun replacingInputBetweenDownAndUpCannotActivateEitherRuntimeObject() {
        val firstSpec = inputSpec("first")
        val replacementSpec = inputSpec("first")
        assertEquals(firstSpec, replacementSpec)
        assertTrue(firstSpec !== replacementSpec)
        val first = DialogueSegment.InputBox(firstSpec)
        val replacement = DialogueSegment.InputBox(replacementSpec)
        val current = mutableStateOf(first, neverEqualPolicy())
        val revision = mutableStateOf(1L)
        var activated: DialogueSegment.InputBox? = null
        val fence = BubbleRegionFence(SurfaceSpeaker.SAKURA, 8L, 1L, IntRect(0, 0, 600, 600))

        composeRule.setContent {
            val input = current.value
            CompositionLocalProvider(LocalBubbleRegionFence provides fence.copy(contentRevision = revision.value)) {
                GhostBubble(
                    state = BubbleUiState(
                        speaker = SurfaceSpeaker.SAKURA,
                        content = DialogueContent(GhostSpeaker.SAKURA, listOf(input)),
                        pendingChoices = emptyList(),
                        pendingInput = PendingInputState(revision.value, input.spec, Long.MAX_VALUE),
                        scrollPosition = 0,
                        userScrolledThisTalk = false,
                        talkId = 8L,
                        contentRevision = revision.value,
                    ),
                    onInput = { activated = it },
                    modifier = Modifier.size(240.dp, 160.dp),
                )
            }
        }

        composeRule.onNodeWithTag("ghost-bubble-input-sakura-0").performTouchInput { down(center) }
        composeRule.runOnIdle {
            current.value = replacement
            revision.value = 2L
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("ghost-bubble-input-sakura-0").performTouchInput { up() }

        composeRule.runOnIdle { assertEquals(null, activated) }
    }

    @Test
    fun touchMouseKeyboardAndAccessibilityScrollingAreManualAndNewTalkRestoresFollow() {
        val talkId = mutableStateOf(1L)
        val revision = mutableStateOf(1L)
        val content = mutableStateOf(longContent("initial"))
        val changes = mutableListOf<ScrollChange>()

        composeRule.setContent {
            GhostBubble(
                state = BubbleUiState(
                    speaker = SurfaceSpeaker.SAKURA,
                    content = content.value,
                    pendingChoices = emptyList(),
                    scrollPosition = Int.MAX_VALUE,
                    userScrolledThisTalk = false,
                    talkId = talkId.value,
                    contentRevision = revision.value,
                ),
                onScrollPositionChanged = { position, origin ->
                    changes += ScrollChange(talkId.value, position, origin)
                },
                modifier = Modifier.size(240.dp, 160.dp),
            )
        }

        fun resetToNewTalk() {
            composeRule.runOnIdle {
                talkId.value++
                revision.value++
                changes.clear()
            }
            composeRule.waitUntil(5_000) {
                changes.any { it.talkId == talkId.value && it.origin == BubbleScrollOrigin.PROGRAMMATIC }
            }
            composeRule.runOnIdle { changes.clear() }
        }

        val bubble = composeRule.onNodeWithTag("ghost-bubble-sakura")
        val scrollViewport = composeRule.onNodeWithTag("ghost-bubble-scroll-sakura")
        composeRule.waitUntil(5_000) { changes.any { it.origin == BubbleScrollOrigin.PROGRAMMATIC } }
        composeRule.runOnIdle { changes.clear() }

        bubble.performTouchInput { swipeDown() }
        composeRule.waitUntil(5_000) { changes.any { it.origin == BubbleScrollOrigin.MANUAL } }

        resetToNewTalk()
        bubble.performMouseInput {
            enter(center)
            scroll(-120f)
            exit()
        }
        composeRule.waitUntil(5_000) { changes.any { it.origin == BubbleScrollOrigin.MANUAL } }

        fun assertKeyboardScrollIsManual(key: Key, startAboveBottom: Boolean) {
            resetToNewTalk()
            scrollViewport.performSemanticsAction(SemanticsActions.RequestFocus)
            if (startAboveBottom) {
                composeRule.onNodeWithTag("ghost-bubble-scroll-sakura")
                    .performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, -400f) }
                composeRule.waitUntil(5_000) { changes.any { it.origin == BubbleScrollOrigin.MANUAL } }
                composeRule.runOnIdle { changes.clear() }
            }
            scrollViewport.performKeyInput { pressKey(key) }
            composeRule.waitUntil(5_000) { changes.any { it.origin == BubbleScrollOrigin.MANUAL } }
        }
        assertKeyboardScrollIsManual(Key.DirectionUp, startAboveBottom = false)
        assertKeyboardScrollIsManual(Key.DirectionDown, startAboveBottom = true)
        assertKeyboardScrollIsManual(Key.PageUp, startAboveBottom = false)
        assertKeyboardScrollIsManual(Key.PageDown, startAboveBottom = true)

        resetToNewTalk()
        composeRule.onNodeWithTag("ghost-bubble-scroll-sakura")
            .performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, -100f) }
        composeRule.waitUntil(5_000) { changes.any { it.origin == BubbleScrollOrigin.MANUAL } }

        composeRule.runOnIdle {
            val manualPosition = changes.last { it.origin == BubbleScrollOrigin.MANUAL }.position
            content.value = longContent("appended", extraLines = 10)
            revision.value++
            changes.clear()
            assertTrue(manualPosition >= 0)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(changes.none { it.origin == BubbleScrollOrigin.PROGRAMMATIC })
        }

        resetToNewTalk()
        composeRule.runOnIdle {
            assertTrue(changes.isEmpty())
        }
    }

    @Test
    fun autoFollowKeepsTrailingChooseVisibleWhenTheSameTalkViewportShrinks() {
        val bubbleSize = mutableStateOf(DpSize(240.dp, 320.dp))
        val choice = DialogueAction.Normal("Newest choice", "newest", emptyList())
        val input = inputSpec("answer")
        composeRule.setContent {
            Box(Modifier.requiredSize(bubbleSize.value.width, bubbleSize.value.height)) {
                GhostBubble(
                    state = BubbleUiState(
                        speaker = SurfaceSpeaker.SAKURA,
                        content = DialogueContent(
                            GhostSpeaker.SAKURA,
                            listOf(
                                DialogueSegment.Text("Typed actions stay explicit."),
                                DialogueSegment.Anchor(
                                    AnchorAction.Normal("Open topic", "topic", emptyList()),
                                ),
                                DialogueSegment.ExternalUrl("Visit project page", "https://example.invalid"),
                                DialogueSegment.InputBox(input),
                                DialogueSegment.Choice(choice),
                            ),
                        ),
                        pendingChoices = listOf(choice),
                        pendingInput = PendingInputState(7L, input, Long.MAX_VALUE),
                        scrollPosition = 0,
                        userScrolledThisTalk = false,
                        talkId = 13L,
                        contentRevision = 21L,
                    ),
                )
            }
        }

        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("ghost-bubble-choose-sakura").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.runOnIdle { bubbleSize.value = DpSize(240.dp, 160.dp) }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("ghost-bubble-choose-sakura").assertIsDisplayed()
            }.isSuccess
        }
    }

    @Test
    fun recreationKeepsOwningSpeakerAndExactActionsAndDismissRestoresChooseFocus() {
        val first = DialogueAction.Normal("First", "first", listOf("one", "", "three"))
        val second = DialogueAction.DirectEvent("Second", "OnSecond", listOf("alpha", "", "omega"))
        val replacementFirst = first.copy()
        val replacementSecond = second.copy()
        val runtimeActions = mutableStateOf(listOf(first, second), neverEqualPolicy())
        val runtimeRevision = mutableStateOf(0L)
        val owner = Any()
        var selected: DialogueAction? = null
        val restoration = StateRestorationTester(composeRule)

        restoration.setContent {
            val measureState = remember {
                GhostStageMeasureState().also { it.resetFor(owner) }
            }
            Box(Modifier.size(360.dp, 720.dp)) {
                GhostPresentationStage(
                    presentation = GhostPresentationReducer.snapshot(
                        sakuraText = "Sakura",
                        sakuraSurfaceId = "0",
                        sakuraAnimationId = null,
                        sakuraBalloonId = "0",
                        keroText = "Kero",
                        keroSurfaceId = "10",
                        keroAnimationId = null,
                        keroBalloonId = "0",
                    ),
                    sakuraComposedSurface = null,
                    keroComposedSurface = null,
                    measureState = measureState,
                    ghostKey = "restoration-fixture",
                    showKeroBalloon = false,
                    sakuraDialogue = DialogueContent(GhostSpeaker.SAKURA, emptyList()),
                    sakuraPendingChoices = runtimeActions.value,
                    dialogueRevision = runtimeRevision.value,
                    onDialogueChoice = { selected = it },
                )
            }
        }

        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").performClick()
        composeRule.onNodeWithTag("dialogue-action-0").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onAllNodesWithTag("dialogue-action-surface-sakura").assertCountEquals(0)
        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("dialogue-action-0").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertSame(first, selected) }

        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").performClick()
        composeRule.onNodeWithTag("dialogue-action-0").performKeyInput { pressKey(Key.Escape) }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onNodeWithTag("ghost-bubble-choose-sakura")
                    .fetchSemanticsNode().config[SemanticsProperties.Focused]
            }.getOrDefault(false)
        }
        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").assertIsFocused()

        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").performClick()
        composeRule.onNodeWithTag("dialogue-action-surface-sakura").assertIsDisplayed()
        composeRule.runOnIdle {
            runtimeActions.value = listOf(replacementFirst, replacementSecond)
            runtimeRevision.value++
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("dialogue-action-surface-sakura").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithTag("dialogue-action-surface-sakura").assertCountEquals(0)
        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").performClick()
        composeRule.onNodeWithTag("dialogue-action-surface-sakura").assertIsDisplayed()
        composeRule.runOnIdle { runtimeActions.value = emptyList() }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("dialogue-action-surface-sakura").fetchSemanticsNodes().isEmpty()
        }
        composeRule.runOnIdle { runtimeActions.value = listOf(first) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag("dialogue-action-surface-sakura").assertCountEquals(0)
        composeRule.onNodeWithTag("ghost-bubble-choose-sakura").performClick()
        composeRule.onNodeWithTag("dialogue-action-surface-sakura").assertIsDisplayed()
    }

    @Test
    fun standardCompactAbsentAndFontTwoLayoutsKeepFixedBubbleCellsUsable() {
        val windowSize = mutableStateOf(DpSize(360.dp, 720.dp))
        val fontScale = mutableFloatStateOf(1f)
        val showKero = mutableStateOf(true)
        val layoutDirection = mutableStateOf(LayoutDirection.Ltr)
        val keroContent = mutableStateOf(
            DialogueContent(GhostSpeaker.KERO, listOf(DialogueSegment.Text("one line"))),
        )
        val owner = Any()
        val measureState = GhostStageMeasureState().also { it.resetFor(owner) }
        val longAnchor = AnchorAction.Normal(
            "A deliberately long localized anchor label that must wrap at font scale two",
            "topic",
            emptyList(),
        )
        val presentation = GhostPresentationReducer.snapshot(
            sakuraText = "Sakura",
            sakuraSurfaceId = "0",
            sakuraAnimationId = null,
            sakuraBalloonId = "0",
            keroText = "Kero",
            keroSurfaceId = "10",
            keroAnimationId = null,
            keroBalloonId = "0",
        )

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection.value) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.FontScale(fontScale.floatValue),
                ) {
                val density = LocalDensity.current
                Box(
                    Modifier
                        .requiredSize(windowSize.value.width, windowSize.value.height)
                        .background(Color.Black),
                ) {
                    MeasuredGhostStageLayout(
                        presentation = presentation,
                        environmentForSize = { size ->
                            StageEnvironment(
                                safeBounds = StageDpRect(
                                    0.dp,
                                    0.dp,
                                    (size.width / density.density).dp,
                                    (size.height / density.density).dp,
                                ),
                                density = density.density,
                                fontScale = density.fontScale,
                                canonicalAppBarHeight = 64.dp,
                                posture = StagePosture.FLAT,
                                displayFeatures = emptyList<StageDisplayFeature>(),
                                inputCapabilities = StageInputCapabilities(true, false, false, false),
                                ghostKey = "layout-fixture",
                            )
                        },
                        measureState = measureState,
                        kero = null,
                        sakura = null,
                        modifier = Modifier.fillMaxSize(),
                        showKeroBalloon = showKero.value,
                        keroBalloon = {
                            GhostBubble(
                                state = BubbleUiState(
                                    speaker = SurfaceSpeaker.KERO,
                                    content = keroContent.value,
                                    pendingChoices = emptyList(),
                                    scrollPosition = 0,
                                    userScrolledThisTalk = false,
                                ),
                            )
                        },
                        sakuraBalloon = {
                            GhostBubble(
                                state = BubbleUiState(
                                    speaker = SurfaceSpeaker.SAKURA,
                                    content = DialogueContent(
                                        GhostSpeaker.SAKURA,
                                        listOf(DialogueSegment.Anchor(longAnchor)),
                                    ),
                                    pendingChoices = emptyList(),
                                    scrollPosition = 0,
                                    userScrolledThisTalk = false,
                                ),
                            )
                        },
                    )
                }
            }
            }
        }

        composeRule.waitUntil(5_000) { measureState.latest?.layoutPx?.mode == StageMode.STANDARD }
        composeRule.runOnIdle {
            assertEquals(2, measureState.latest!!.activeBubbleFences.size)
        }
        assertDownPointer("ghost-bubble-kero")
        assertDownPointer("ghost-bubble-sakura")
        val fixedKeroBounds = composeRule.onNodeWithTag("ghost-bubble-kero")
            .fetchSemanticsNode().boundsInRoot
        val fixedSakuraBounds = composeRule.onNodeWithTag("ghost-bubble-sakura")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(fixedKeroBounds.left < fixedSakuraBounds.left)
        composeRule.runOnIdle {
            keroContent.value = DialogueContent(
                GhostSpeaker.KERO,
                listOf(DialogueSegment.Text("first line\nsecond line")),
            )
        }
        composeRule.waitForIdle()
        assertEquals(
            fixedKeroBounds,
            composeRule.onNodeWithTag("ghost-bubble-kero").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.runOnIdle {
            keroContent.value = DialogueContent(
                GhostSpeaker.KERO,
                listOf(DialogueSegment.Text((0 until 80).joinToString("\n") { "long fixed-frame $it" })),
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onNodeWithTag("ghost-bubble-scroll-kero").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f
        }
        assertEquals(
            fixedKeroBounds,
            composeRule.onNodeWithTag("ghost-bubble-kero").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.runOnIdle { layoutDirection.value = LayoutDirection.Rtl }
        composeRule.waitForIdle()
        assertEquals(
            fixedKeroBounds,
            composeRule.onNodeWithTag("ghost-bubble-kero").fetchSemanticsNode().boundsInRoot,
        )
        assertEquals(
            fixedSakuraBounds,
            composeRule.onNodeWithTag("ghost-bubble-sakura").fetchSemanticsNode().boundsInRoot,
        )
        composeRule.runOnIdle { layoutDirection.value = LayoutDirection.Ltr }

        composeRule.runOnIdle { windowSize.value = DpSize(720.dp, 360.dp) }
        composeRule.waitUntil(5_000) { measureState.latest?.layoutPx?.mode == StageMode.COMPACT_LANDSCAPE }
        composeRule.runOnIdle {
            val latest = measureState.latest!!
            assertEquals(2, latest.activeBubbleFences.size)
            assertEquals(latest.layoutPx.keroBubble, latest.activeBubbleFences[SurfaceSpeaker.KERO]?.frame)
            assertEquals(latest.layoutPx.sakuraBubble, latest.activeBubbleFences[SurfaceSpeaker.SAKURA]?.frame)
        }
        assertLeftPointer("ghost-bubble-kero")
        assertRightPointer("ghost-bubble-sakura")

        composeRule.runOnIdle { showKero.value = false }
        composeRule.waitUntil(5_000) {
            measureState.latest?.activeBubbleFences?.containsKey(SurfaceSpeaker.KERO) == false
        }
        composeRule.onAllNodesWithTag("ghost-bubble-kero").assertCountEquals(0)
        composeRule.runOnIdle {
            assertTrue(measureState.latest!!.activeBubbleFences.containsKey(SurfaceSpeaker.SAKURA))
        }

        composeRule.runOnIdle {
            windowSize.value = DpSize(360.dp, 720.dp)
            fontScale.floatValue = 2f
        }
        composeRule.waitUntil(5_000) {
            measureState.latest?.layoutPx?.mode == StageMode.STANDARD &&
                measureState.latest?.activeBubbleFences?.containsKey(SurfaceSpeaker.SAKURA) == true
        }
        val anchorBounds = composeRule.onNodeWithTag("ghost-bubble-anchor-sakura-0")
            .fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            val density = composeRule.activity.resources.displayMetrics.density
            assertTrue(anchorBounds.height / density >= 48f)
            assertTrue(anchorBounds.height / density > 48f)
        }
    }

    @Test
    fun pointerDecorationPointsDownInStandardAndPhysicallyOutwardInCompactWithoutFocusTarget() {
        val direction = mutableStateOf(BubblePointerDirection.DOWN)
        val speaker = mutableStateOf(SurfaceSpeaker.SAKURA)
        composeRule.setContent {
            Box(Modifier.size(240.dp, 160.dp).background(Color.Black)) {
                CompositionLocalProvider(LocalBubblePointerDirection provides direction.value) {
                    GhostBubble(
                        state = BubbleUiState(
                            speaker = speaker.value,
                            content = DialogueContent(GhostSpeaker.SAKURA, emptyList()),
                            pendingChoices = emptyList(),
                            scrollPosition = 0,
                            userScrolledThisTalk = false,
                        ),
                    )
                }
            }
        }

        fun image() = composeRule.onNodeWithTag("ghost-bubble-${speaker.value.name.lowercase()}")
            .captureToImage().toPixelMap()
        fun assertColored(pixel: Color) = assertTrue("expected pointer pixel, got $pixel", pixel != Color.Black)

        image().let { pixels ->
            assertColored(pixels[(pixels.width * 0.5f).toInt(), pixels.height - 3])
            assertEquals(Color.Black, pixels[(pixels.width * 0.1f).toInt(), pixels.height - 3])
        }
        composeRule.runOnIdle {
            direction.value = BubblePointerDirection.LEFT
            speaker.value = SurfaceSpeaker.KERO
        }
        composeRule.waitForIdle()
        image().let { pixels ->
            assertColored(pixels[3, (pixels.height * 0.65f).toInt()])
            assertEquals(Color.Black, pixels[3, (pixels.height * 0.1f).toInt()])
        }
        composeRule.runOnIdle {
            direction.value = BubblePointerDirection.RIGHT
            speaker.value = SurfaceSpeaker.SAKURA
        }
        composeRule.waitForIdle()
        image().let { pixels ->
            assertColored(pixels[pixels.width - 4, (pixels.height * 0.65f).toInt()])
            assertEquals(Color.Black, pixels[pixels.width - 4, (pixels.height * 0.1f).toInt()])
        }
        composeRule.onAllNodes(isFocusable()).assertCountEquals(1)
    }

    private fun assertDownPointer(tag: String) {
        composeRule.onNodeWithTag(tag).captureToImage().toPixelMap().let { pixels ->
            assertTrue(pixels[(pixels.width * 0.5f).toInt(), pixels.height - 3] != Color.Black)
            assertEquals(Color.Black, pixels[(pixels.width * 0.1f).toInt(), pixels.height - 3])
        }
    }

    private fun assertLeftPointer(tag: String) {
        composeRule.onNodeWithTag(tag).captureToImage().toPixelMap().let { pixels ->
            assertTrue(pixels[3, (pixels.height * 0.65f).toInt()] != Color.Black)
            assertEquals(Color.Black, pixels[3, (pixels.height * 0.1f).toInt()])
        }
    }

    private fun assertRightPointer(tag: String) {
        composeRule.onNodeWithTag(tag).captureToImage().toPixelMap().let { pixels ->
            assertTrue(pixels[pixels.width - 4, (pixels.height * 0.65f).toInt()] != Color.Black)
            assertEquals(Color.Black, pixels[pixels.width - 4, (pixels.height * 0.1f).toInt()])
        }
    }

    private data class ScrollChange(
        val talkId: Long,
        val position: Int,
        val origin: BubbleScrollOrigin,
    )

    private fun assertPublishedScrollViewport(
        fence: BubbleRegionFence,
        publication: BubbleRegionSet,
        measured: IntRect,
    ) {
        val viewport = requireNotNull(publication.scrollViewport)
        assertEquals(fence, publication.fence)
        assertEquals(measured, viewport)
    }

    private fun measuredScrollViewport(fence: BubbleRegionFence, speaker: SurfaceSpeaker): IntRect {
        val bounds = composeRule.onNodeWithTag("ghost-bubble-scroll-${speaker.name.lowercase()}")
            .fetchSemanticsNode()
            .boundsInRoot
        return IntRect(
            fence.frame.left + floor(bounds.left).toInt(),
            fence.frame.top + floor(bounds.top).toInt(),
            fence.frame.left + ceil(bounds.right).toInt(),
            fence.frame.top + ceil(bounds.bottom).toInt(),
        )
    }

    private fun longContent(prefix: String, extraLines: Int = 0) = DialogueContent(
        GhostSpeaker.SAKURA,
        listOf(
            DialogueSegment.Text(
                (0 until 80 + extraLines).joinToString("\n") { "$prefix line $it" },
            ),
        ),
    )

    private fun inputSpec(id: String) = InputBoxSpec(
        dispatch = InputDispatch.Normal(id),
        timeoutMillis = null,
        initialText = "",
        behaviorOptions = emptySet(),
        supplement = "",
        extraReferences = emptyList(),
        unknownOptions = emptyList(),
    )
}
