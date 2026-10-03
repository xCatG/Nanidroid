package com.cattailsw.nanidroid.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cattailsw.nanidroid.ghost.SurfaceImageLoader
import com.cattailsw.nanidroid.ghost.ShellFiles
import com.cattailsw.nanidroid.ghost.ShellCatalog
import com.cattailsw.nanidroid.ghost.SurfaceComposer
import com.cattailsw.nanidroid.ghost.ComposedSurface
import com.cattailsw.nanidroid.ghost.SurfaceCollision
import com.cattailsw.nanidroid.runtime.ImportPromptResult
import com.cattailsw.nanidroid.runtime.StageState
import com.cattailsw.nanidroid.runtime.InteractionToken
import com.cattailsw.nanidroid.runtime.DialogueToken
import com.cattailsw.nanidroid.runtime.RenderedMoveSource
import com.cattailsw.nanidroid.runtime.RenderedMoveSnapshot
import com.cattailsw.nanidroid.runtime.MoveGeometryResolver
import com.cattailsw.nanidroid.runtime.SpeakerFrame
import com.cattailsw.nanidroid.runtime.PresentedChoice
import com.cattailsw.nanidroid.install.ImportOutcome
import com.cattailsw.nanidroid.install.ImportState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private sealed interface LoadedImages {
    data object Loading : LoadedImages
    data class Ready(val images: Map<Int, ComposedSurface>) : LoadedImages
    data class Error(val message: String) : LoadedImages
}

/** Tap callbacks receive speaker, authored x/y, collision surface and the originating dialogue. */
private typealias CharacterTap = (Int, Int, Int, Int, DialogueToken?) -> Unit
private typealias CharacterMove = (Int, Int, Int, Int, RenderedMoveSource) -> Unit

private class CharacterCallbacks(
    val click: CharacterTap,
    val doubleClick: CharacterTap,
    val move: CharacterMove,
    val moveCancel: (Int) -> Unit,
)

private data class CharacterPointerContext(
    val rectangle: StageGeometry.SurfaceRectangle,
    val rootOrigin: Offset,
    val surface: AnimatedSurface,
    val source: RenderedMoveSource,
    val callbacks: CharacterCallbacks,
)

/** The press whose moves are being sampled; a coordinate change cancels only the sampling. */
private class ActiveCharacterGesture(private val speaker: Int) {
    private var captured: CharacterPointerContext? = null
    val tracking get() = captured != null

    fun begin(context: CharacterPointerContext) { captured = context }

    fun invalidateIfChanged(rectangle: StageGeometry.SurfaceRectangle, rootOrigin: Offset,
        surface: AnimatedSurface) {
        val first = captured ?: return
        if (first.rectangle != rectangle || first.rootOrigin != rootOrigin ||
            first.surface.coordinates != surface.coordinates) cancel()
    }

    fun cancel() {
        captured?.callbacks?.moveCancel?.invoke(speaker)
        captured = null
    }

    fun end() { captured = null }
}

@Composable
fun GhostStage(state: StageState, imageLoader: SurfaceImageLoader,
    onCharacterClick: (speaker: Int, x: Int, y: Int) -> Unit) {
    GhostStage(state, imageLoader, {}, {}, {}, { _, _, _ -> }, onCharacterClick)
}

@Composable
fun GhostStage(
    state: StageState,
    imageLoader: SurfaceImageLoader,
    onSelectGhost: (String) -> Unit,
    onConfirmSwitch: () -> Unit,
    onDismissSwitch: () -> Unit,
    onCharacterDoubleClick: (speaker: Int, x: Int, y: Int) -> Unit = { _, _, _ -> },
    onCharacterClick: (speaker: Int, x: Int, y: Int) -> Unit = { _, _, _ -> },
    importState: ImportState = ImportState.Idle,
    onImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onClose: () -> Unit = {},
    onAcknowledgeImport: (String) -> Unit = {},
    importInterrupted: Boolean = false,
    onDismissInterrupted: () -> Unit = {},
    onChoose: (InteractionToken) -> Unit = {},
    onSubmitInput: (InteractionToken, String) -> Unit = { _, _ -> },
    onCancelInput: (InteractionToken) -> Unit = {},
    onOpenLink: (DialogueToken, String) -> Unit = { _, _ -> },
    isFarewellInput: (InteractionToken) -> Boolean = { false },
    linkError: String? = null,
    onCharacterMove: (speaker: Int, x: Int, y: Int) -> Unit = { _, _, _ -> },
    onCharacterMoveCancel: (speaker: Int) -> Unit = {},
    onCharacterClickResolved: ((speaker: Int, x: Int, y: Int, collisionSurfaceId: Int,
        origin: DialogueToken?) -> Unit)? = null,
    onCharacterDoubleClickResolved: ((speaker: Int, x: Int, y: Int, collisionSurfaceId: Int,
        origin: DialogueToken?) -> Unit)? = null,
    onCharacterMoveResolved: ((speaker: Int, x: Int, y: Int, collisionSurfaceId: Int,
        source: RenderedMoveSource) -> Unit)? = null,
    interactionActive: Boolean = true,
    versionName: String = "Unknown",
    notices: List<Notice> = emptyList(),
) {
    val view = LocalView.current
    val debugBuild = view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    var showTouchBounds by rememberSaveable { mutableStateOf(false) }
    // The resolved callbacks carry collision and dialogue provenance; plain ones are test conveniences.
    val callbacks = CharacterCallbacks(
        onCharacterClickResolved ?: { s, x, y, _, _ -> onCharacterClick(s, x, y) },
        onCharacterDoubleClickResolved ?: { s, x, y, _, _ -> onCharacterDoubleClick(s, x, y) },
        onCharacterMoveResolved ?: { s, x, y, _, _ -> onCharacterMove(s, x, y) },
        onCharacterMoveCancel,
    )
    DisposableEffect(view, importState is ImportState.Running) {
        view.keepScreenOn = importState is ImportState.Running
        onDispose { view.keepScreenOn = false }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (state) {
            StageState.Loading -> StageMessage("Loading ghost…")
            is StageState.Error -> StageMessage(state.message)
            StageState.Finished -> Unit
            is StageState.Ready -> {
                var showGhosts by remember { mutableStateOf(false) }
                var showAbout by remember { mutableStateOf(false) }
                var controlsVisible by rememberSaveable { mutableStateOf(true) }
                var controlsHeightPx by remember { mutableStateOf(0) }
                val controlsHeight = with(LocalDensity.current) { controlsHeightPx.toDp() }
                val toggleControls = { controlsVisible = !controlsVisible }
                val loaded by produceState<LoadedImages>(LoadedImages.Loading, state.surfaces, state.shell, imageLoader) {
                    value = try {
                        LoadedImages.Ready(loadSurfaces(state.surfaces, state.shell, imageLoader))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        LoadedImages.Error(error.message ?: "Unable to load surfaces")
                    }
                }
                Box(Modifier.fillMaxSize().testTag("stage-background")
                    .pointerInput(Unit) { detectTapGestures(onTap = { toggleControls() }) })
                when (val images = loaded) {
                    LoadedImages.Loading -> StageMessage("Loading ghost…")
                    is LoadedImages.Error -> StageMessage(images.message)
                    is LoadedImages.Ready -> ReadyStage(state, images.images, callbacks,
                        onChoose, onOpenLink, toggleControls, interactionActive,
                        debugBuild && showTouchBounds,
                        Modifier.padding(top = if (controlsVisible) controlsHeight else 0.dp)
                            .semantics {
                                contentDescription = if (controlsVisible) "Hide stage controls" else "Show stage controls"
                                onClick { toggleControls(); true }
                            }.testTag("stage-controls-toggle"))
                }
                if (controlsVisible) Row(Modifier.align(Alignment.TopEnd)
                    .onSizeChanged { controlsHeightPx = it.height }) {
                    if (debugBuild) TextButton(onClick = { showTouchBounds = !showTouchBounds },
                        modifier = Modifier.semantics {
                            contentDescription = if (showTouchBounds) "Hide touch bounds" else "Show touch bounds"
                            stateDescription = if (showTouchBounds) "Touch bounds on" else "Touch bounds off"
                        }.testTag("touch-bounds-action")) { Text("Bounds") }
                    TextButton(onClick = { showGhosts = true }, modifier = Modifier.semantics {
                        contentDescription = "Ghosts"
                    }.testTag("ghosts-action")) { Text("Ghosts") }
                    TextButton(onClick = { showAbout = true }, modifier = Modifier.semantics {
                        contentDescription = "About"
                    }.testTag("about-action")) { Text("About") }
                }
                Column(Modifier.align(Alignment.TopEnd).padding(top = if (controlsVisible) controlsHeight else 0.dp),
                    horizontalAlignment = Alignment.End) {
                    state.activationError?.let { error ->
                        Text(error, Modifier.background(Color(0xFFF8F3E8)).padding(8.dp)
                            .semantics { contentDescription = "Ghost error: $error" }
                            .testTag("ghost-error"), color = Color.Black)
                    }
                    linkError?.let { error ->
                        Text(error, Modifier.background(Color(0xFFF8F3E8)).padding(8.dp)
                            .testTag("link-error"), color = Color.Black)
                    }
                }
                if (showAbout) AboutDialog(versionName, notices) { showAbout = false }
                if ((showGhosts || importState is ImportState.Running ||
                    importState is ImportState.Completed || importInterrupted) && state.switchPrompt == null) {
                    AlertDialog(
                        onDismissRequest = {
                            if (importState is ImportState.Running) onClose() else {
                                showGhosts = false
                                if (importState is ImportState.Completed) onAcknowledgeImport(importState.attemptId)
                                if (importInterrupted) onDismissInterrupted()
                            }
                        },
                        properties = DialogProperties(dismissOnClickOutside = importState !is ImportState.Running),
                        title = { Text("Ghosts") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                when (importState) {
                                    is ImportState.Running -> Text("Importing ghost…",
                                        Modifier.semantics { contentDescription = "Import in progress" })
                                    is ImportState.Completed -> Text(when (importState.outcome) {
                                        is ImportOutcome.Installed ->
                                            (importState.promptResult as? ImportPromptResult.Unavailable)
                                                ?.let { "Ghost imported. ${it.message}." }
                                                ?: "Ghost imported. Review the readme before switching."
                                        is ImportOutcome.Refused -> "Ghost is already installed or cannot be imported."
                                        is ImportOutcome.Failed -> "Import failed. ${importState.outcome.message}."
                                        ImportOutcome.Cancelled -> "Import cancelled."
                                    })
                                    else -> {
                                        if (importInterrupted) Text("Import interrupted. Please try again.")
                                        state.installedGhosts.forEach { ghost ->
                                            TextButton(onClick = {
                                                showGhosts = false
                                                onSelectGhost(ghost.directoryId)
                                            }, modifier = Modifier.fillMaxWidth().semantics {
                                                contentDescription = "Select ${ghost.displayName}"
                                            }.testTag("ghost-${ghost.directoryId}")) {
                                                Text(ghost.displayName)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            if (importState is ImportState.Running) {
                                TextButton(onClick = onCancelImport, modifier = Modifier.semantics {
                                    contentDescription = "Cancel import"
                                }) { Text("Cancel") }
                            } else {
                                TextButton(onClick = {
                                    showGhosts = false
                                    if (importState is ImportState.Completed) onAcknowledgeImport(importState.attemptId)
                                    if (importInterrupted) onDismissInterrupted()
                                }, modifier = Modifier.semantics { contentDescription = "Close Ghosts" }) { Text("Close") }
                            }
                        },
                        dismissButton = {
                            if (importState == ImportState.Idle) {
                                TextButton(onClick = { showGhosts = false; onImport() },
                                    modifier = Modifier.semantics { contentDescription = "Import .nar" }
                                        .testTag("import-action")) { Text("Import .nar") }
                            }
                        },
                    )
                }
                state.switchPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = onDismissSwitch,
                        title = { Text("Switch to ${prompt.displayName}?") },
                        text = {
                            Text(prompt.readme ?: "Switch to ${prompt.displayName}?",
                                Modifier.verticalScroll(rememberScrollState()).testTag("ghost-readme"))
                        },
                        confirmButton = {
                            TextButton(onClick = onConfirmSwitch, modifier = Modifier.semantics {
                                contentDescription = "Confirm ghost switch"
                            }) { Text("Switch") }
                        },
                        dismissButton = {
                            TextButton(onClick = onDismissSwitch, modifier = Modifier.semantics {
                                contentDescription = "Cancel ghost switch"
                            }) { Text("Cancel") }
                        },
                    )
                }
                state.input?.let { input ->
                    var draft by rememberSaveable(input.token.sessionId, input.token.dialogueId,
                        input.token.itemKey) { mutableStateOf("") }
                    GhostInputDialog(input, draft, onDraftChange = { draft = it },
                        onSubmit = { onSubmitInput(input.token, it) },
                        onCancel = { onCancelInput(input.token) },
                        onBack = {
                            if (isFarewellInput(input.token)) onClose()
                            else onCancelInput(input.token)
                        })
                }
            }
        }
    }
}

private suspend fun loadSurfaces(files: Map<Int, File>, shellCatalog: ShellCatalog?,
    loader: SurfaceImageLoader): Map<Int, ComposedSurface> {
    val definitions = shellCatalog?.definitions
    val images = mutableMapOf<Int, ComposedSurface>()
    for ((id, file) in files) {
        if (shellCatalog != null && !withContext(Dispatchers.IO) {
                ShellFiles.isDirectChild(shellCatalog.shellDirectory, file)
        }) continue
        try {
            val elements = definitions?.definition(id)?.elements.orEmpty()
            if (shellCatalog != null && elements.any { it.id == 0 }) {
                images[id] = SurfaceComposer.composeBase(shellCatalog, id, loader)
                    ?: ComposedSurface(loader.load(file), 0, 0)
            } else {
                val numbered = loader.load(file)
                images[id] = if (shellCatalog != null && elements.isNotEmpty())
                    SurfaceComposer.composeBase(shellCatalog, id, loader, numbered)
                        ?: ComposedSurface(numbered, 0, 0)
                else ComposedSurface(numbered, 0, 0)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // One invalid numbered image must not hide the other drawable surfaces.
        }
    }
    if (shellCatalog != null) {
        for (id in shellCatalog.definitions.ids()) {
            val elements = definitions?.definition(id)?.elements.orEmpty()
            if (elements.isEmpty() || id in images) continue
            SurfaceComposer.composeBase(shellCatalog, id, loader)?.let { images[id] = it }
        }
    }
    return images
}

@Composable
private fun StageMessage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, modifier = Modifier.background(Color(0xFFF8F3E8)).padding(16.dp), color = Color.Black)
    }
}

@Composable
private fun ReadyStage(
    state: StageState.Ready,
    images: Map<Int, ComposedSurface>,
    callbacks: CharacterCallbacks,
    onChoose: (InteractionToken) -> Unit,
    onOpenLink: (DialogueToken, String) -> Unit,
    onInertTap: () -> Unit,
    gesturesActive: Boolean,
    showTouchBounds: Boolean,
    modifier: Modifier,
) {
    val imageBounds = remember(images) { AnimatedSurface.imageBounds(images) }
    @Composable
    fun resolve(frame: SpeakerFrame, speaker: Int) = remember(frame.surfaceId, frame.visual, images) {
        AnimatedSurface.resolve(frame.surfaceId, frame.visual, images,
            MoveGeometryResolver.fallbackSurfaceId(speaker), imageBounds)
    }
    val sakura = resolve(state.frame.sakura, 0)
    val kero = resolve(state.frame.kero, 1)
    if (sakura == null || kero == null) {
        StageMessage("Character surfaces are missing")
        return
    }
    // Stable per character so CharacterSurface skips when only balloon text changes.
    @Composable
    fun source(frame: SpeakerFrame, surface: AnimatedSurface) =
        remember(state.dialogueToken, frame.surfaceId, frame.visual, imageBounds, surface) {
            RenderedMoveSource(state.dialogueToken, frame.surfaceId, frame.visual,
                RenderedMoveSnapshot(imageBounds, surface.coordinates))
        }
    val sakuraSource = source(state.frame.sakura, sakura)
    val keroSource = source(state.frame.kero, kero)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val windowConfiguration = LocalConfiguration.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val minimumBalloonReserve = with(density) {
            val lineHeight = MaterialTheme.typography.bodyLarge.lineHeight.toPx()
            // Enlarged font line boxes can exceed the nominal line height.
            val lineBoxAllowance = lineHeight * (fontScale - 1f).coerceAtLeast(0f) / 2f
            // Keep 24dp below the third line, cover Balloon's 4dp padding on both edges,
            // and allow 2dp for fractional text line metrics and layout rounding.
            lineHeight * 3 + lineBoxAllowance + 34.dp.toPx()
        }
        val balloonReserve = maxOf(with(density) { 96.dp.toPx() }, minimumBalloonReserve)
            .coerceAtMost((heightPx - with(density) { 24.dp.toPx() }).coerceAtLeast(0f))
        val scale = StageGeometry.scaleFor(
            sakura.width, sakura.height, kero.width, kero.height,
            widthPx.roundToInt(), (heightPx - balloonReserve).roundToInt(),
            StageGeometry.logicalArtCeiling(windowConfiguration.screenWidthDp,
                windowConfiguration.screenHeightDp, density.density),
        )
        val sakuraWidth = with(density) { (sakura.width * scale).toDp() }
        val sakuraHeight = with(density) { (sakura.height * scale).toDp() }
        val keroWidth = with(density) { (kero.width * scale).toDp() }
        val keroHeight = with(density) { (kero.height * scale).toDp() }
        val balloonHeight = maxHeight - maxOf(sakuraHeight, keroHeight)
        val stageWidth = maxWidth
        val sakuraOffset = sakuraWidth * (sakura.offsetX.toFloat() / sakura.width)
        val keroOffset = keroWidth * (kero.offsetX.toFloat() / kero.width)

        if (state.frame.kero.visible) {
            CharacterSurface(kero, state.keroName, 1, keroWidth, keroHeight, keroSource,
                gesturesActive, showTouchBounds,
                state.shell?.definitions?.definition(kero.collisionSurfaceId)?.collisions.orEmpty(),
                Modifier.align(Alignment.BottomStart), callbacks)
        }
        if (state.frame.sakura.visible) {
            CharacterSurface(sakura, state.sakuraName, 0, sakuraWidth, sakuraHeight, sakuraSource,
                gesturesActive, showTouchBounds,
                state.shell?.definitions?.definition(sakura.collisionSurfaceId)?.collisions.orEmpty(),
                Modifier.align(Alignment.BottomEnd), callbacks)
        }

        if (kero.height < sakura.height / 2 && minimumBalloonReserve <= with(density) { 96.dp.toPx() }) {
            Balloon(state.sakuraName, state.frame.sakura.text, state.frame.sakura.balloonVisible,
                state.choices[0].orEmpty(), state.dialogueToken, onChoose, onOpenLink, onInertTap,
                maxWidth - sakuraWidth / 2 + sakuraOffset - 4.dp,
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(balloonHeight.coerceAtLeast(0.dp)))
            Balloon(state.keroName, state.frame.kero.text, state.frame.kero.balloonVisible,
                state.choices[1].orEmpty(), state.dialogueToken, onChoose, onOpenLink, onInertTap,
                keroWidth / 2 + keroOffset - 4.dp,
                Modifier.align(Alignment.BottomStart).padding(bottom = keroHeight)
                    .width(maxWidth / 2).height((sakuraHeight - keroHeight).coerceAtLeast(0.dp)))
        } else {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(balloonHeight.coerceAtLeast(0.dp))) {
                Balloon(state.keroName, state.frame.kero.text, state.frame.kero.balloonVisible,
                    state.choices[1].orEmpty(), state.dialogueToken, onChoose, onOpenLink, onInertTap,
                    keroWidth / 2 + keroOffset - 4.dp,
                    Modifier.weight(1f))
                Balloon(state.sakuraName, state.frame.sakura.text, state.frame.sakura.balloonVisible,
                    state.choices[0].orEmpty(), state.dialogueToken, onChoose, onOpenLink, onInertTap,
                    (stageWidth - sakuraWidth) / 2 + sakuraOffset - 4.dp,
                    Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CharacterSurface(
    surface: AnimatedSurface,
    name: String,
    speaker: Int,
    width: Dp,
    height: Dp,
    renderedMoveSource: RenderedMoveSource,
    gesturesActive: Boolean,
    showTouchBounds: Boolean,
    collisions: List<SurfaceCollision>,
    placement: Modifier,
    callbacks: CharacterCallbacks,
) {
    var rectangle by remember(surface.width, surface.height) {
        mutableStateOf(StageGeometry.fittedRectangle(surface.width, surface.height, 0, 0))
    }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val activeGesture = remember(speaker) { ActiveCharacterGesture(speaker) }
    val currentPointer by rememberUpdatedState(
        CharacterPointerContext(rectangle, rootOrigin, surface, renderedMoveSource, callbacks))
    SideEffect { activeGesture.invalidateIfChanged(rectangle, rootOrigin, surface) }
    val layerPaint = remember { Paint() }
    val collisionTextPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.CYAN
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    val collisionLabelBackground = remember {
        android.graphics.Paint().apply { color = android.graphics.Color.argb(220, 0, 0, 0) }
    }
    val viewContext = LocalView.current.context
    val platformDoubleTapSlop = remember(viewContext) {
        android.view.ViewConfiguration.get(viewContext).scaledDoubleTapSlop
    }
    Canvas(
        modifier = placement.offset(x = width * (surface.offsetX.toFloat() / surface.width),
                y = height * (surface.offsetY.toFloat() / surface.height)).size(width, height)
            .onSizeChanged {
                val next = StageGeometry.fittedRectangle(surface.width, surface.height,
                    it.width, it.height)
                activeGesture.invalidateIfChanged(next, rootOrigin, surface)
                rectangle = next
            }
            .onGloballyPositioned {
                val next = it.localToRoot(Offset.Zero)
                activeGesture.invalidateIfChanged(rectangle, next, surface)
                rootOrigin = next
            }
            .testTag(if (speaker == 0) "sakura" else "kero")
            .pointerInput(speaker, renderedMoveSource.dialogueToken, gesturesActive) {
                if (!gesturesActive) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val doubleTimeout = viewConfiguration.doubleTapTimeoutMillis
                val doubleSlop = platformDoubleTapSlop
                // Gestures that outlive their dialogue are dropped rather than retargeted.
                fun authored(position: Offset, context: CharacterPointerContext) =
                    if (currentPointer.source.dialogueToken != context.source.dialogueToken) null
                    else context.rectangle.toAuthored(position.x, position.y,
                        context.surface.originX, context.surface.originY)
                fun emitTap(position: Offset, double: Boolean, context: CharacterPointerContext) {
                    val point = authored(position, context) ?: return
                    val tap = if (double) context.callbacks.doubleClick else context.callbacks.click
                    tap(speaker, point.x, point.y, context.surface.collisionSurfaceId,
                        context.source.dialogueToken)
                }
                fun emitMove(position: Offset, context: CharacterPointerContext) {
                    val point = authored(position, context) ?: return
                    context.callbacks.move(speaker, point.x, point.y, context.surface.collisionSurfaceId,
                        context.source)
                }
                suspend fun AwaitPointerEventScope.track(down: PointerInputChange,
                    context: CharacterPointerContext): CharacterGesture {
                    activeGesture.begin(context)
                    return readCharacterGesture(down, slop,
                        { position -> position + currentPointer.rootOrigin - context.rootOrigin }) {
                        if (activeGesture.tracking) emitMove(it, context)
                    }.also { if (it is CharacterGesture.Cancelled) activeGesture.cancel() else activeGesture.end() }
                }
                try { awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val firstContext = currentPointer
                    val first = track(firstDown, firstContext)
                    // Geometry changes cancel only move sampling; taps keep their down context.
                    if (first is CharacterGesture.Tap) {
                        val secondDown = withTimeoutOrNull(doubleTimeout) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        if (secondDown == null) emitTap(first.position, false, firstContext)
                        else {
                            val secondContext = currentPointer
                            val second = track(secondDown, secondContext)
                            val delta = secondDown.position + secondContext.rootOrigin -
                                (firstDown.position + firstContext.rootOrigin)
                            val near = delta.x * delta.x + delta.y * delta.y <= doubleSlop * doubleSlop
                            if (second is CharacterGesture.Tap && near)
                                emitTap(second.position, true, secondContext)
                            else {
                                emitTap(first.position, false, firstContext)
                                if (second is CharacterGesture.Tap)
                                    emitTap(second.position, false, secondContext)
                            }
                        }
                    }
                } } finally {
                    activeGesture.cancel()
                }
            }
            .semantics {
                contentDescription = name
                onClick {
                    if (!gesturesActive) return@onClick false
                    callbacks.click(speaker, surface.originX + surface.width / 2,
                        surface.originY + surface.height / 2, surface.collisionSurfaceId,
                        renderedMoveSource.dialogueToken)
                    true
                }
            },
    ) {
        if (rectangle.width > 0f && rectangle.height > 0f) {
            drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint)
            val scaleX = rectangle.width / surface.width
            val scaleY = rectangle.height / surface.height
            surface.parts.forEach { part ->
                drawImage(
                    image = part.image,
                    srcSize = IntSize(part.image.width, part.image.height),
                    dstOffset = IntOffset((rectangle.left + (part.x - surface.originX) * scaleX).roundToInt(),
                        (rectangle.top + (part.y - surface.originY) * scaleY).roundToInt()),
                    dstSize = IntSize((part.image.width * scaleX).roundToInt(),
                        (part.image.height * scaleY).roundToInt()),
                    blendMode = part.blendMode,
                )
            }
            drawContext.canvas.restore()
            if (showTouchBounds) {
                collisionTextPaint.textSize = 12.dp.toPx()
                val visibleCollisions = collisions.mapNotNull { collision ->
                    val left = maxOf(collision.left.toLong(), surface.originX.toLong())
                    val top = maxOf(collision.top.toLong(), surface.originY.toLong())
                    val right = minOf(collision.right.toLong(), surface.originX.toLong() + surface.width)
                    val bottom = minOf(collision.bottom.toLong(), surface.originY.toLong() + surface.height)
                    if (right <= left || bottom <= top) null else {
                        val x = rectangle.left + (left - surface.originX).toFloat() * scaleX
                        val y = rectangle.top + (top - surface.originY).toFloat() * scaleY
                        collision to Rect(x, y, x + (right - left).toFloat() * scaleX,
                            y + (bottom - top).toFloat() * scaleY)
                    }
                }
                val labelBoxes = mutableListOf<Rect>()
                visibleCollisions.forEach { (collision, bounds) ->
                    val label = "${collision.id}: ${collision.name}"
                    val padding = 2.dp.toPx()
                    val labelHeight = collisionTextPaint.textSize + padding * 2
                    if (rectangle.width <= padding * 2 || rectangle.height <= labelHeight) return@forEach
                    val labelWidth = minOf(collisionTextPaint.measureText(label) + padding * 2, rectangle.width)
                    val labelX = bounds.left.coerceIn(rectangle.left, rectangle.left + rectangle.width - labelWidth)
                    var labelTop = bounds.top.coerceIn(rectangle.top,
                        rectangle.top + rectangle.height - labelHeight)
                    var labelBox = Rect(labelX, labelTop, labelX + labelWidth, labelTop + labelHeight)
                    var placementAttempts = 0
                    while (labelBoxes.any { it.overlaps(labelBox) } &&
                        placementAttempts++ < visibleCollisions.size) {
                        labelTop += labelHeight
                        if (labelTop + labelHeight > rectangle.top + rectangle.height)
                            labelTop = rectangle.top
                        labelBox = Rect(labelX, labelTop, labelX + labelWidth, labelTop + labelHeight)
                    }
                    labelBoxes += labelBox
                    val labelY = labelTop + collisionTextPaint.textSize + padding
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.drawRect(labelBox.left, labelBox.top, labelBox.right, labelBox.bottom,
                            collisionLabelBackground)
                        native.save()
                        native.clipRect(labelX + padding, labelY - collisionTextPaint.textSize,
                            labelX + labelWidth - padding, labelY + padding)
                        native.drawText(label, labelX + padding, labelY, collisionTextPaint)
                        native.restore()
                    }
                }
                visibleCollisions.forEach { (_, bounds) ->
                    drawRect(Color.Cyan, bounds.topLeft, bounds.size,
                        style = Stroke(width = 2.dp.toPx()))
                }
                drawRect(Color.Magenta,
                    topLeft = Offset(rectangle.left, rectangle.top),
                    size = Size(rectangle.width, rectangle.height),
                    style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

private sealed interface CharacterGesture {
    data class Tap(val position: Offset) : CharacterGesture
    data object Dragged : CharacterGesture
    data object Cancelled : CharacterGesture
}

private suspend fun AwaitPointerEventScope.readCharacterGesture(
    down: PointerInputChange, touchSlop: Float,
    toPressLocal: (Offset) -> Offset, onDrag: (Offset) -> Unit,
): CharacterGesture {
    var dragged = false
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
            ?: return CharacterGesture.Cancelled
        if (change.isConsumed) return CharacterGesture.Cancelled
        val position = toPressLocal(change.position)
        if (!change.pressed) return if (dragged) CharacterGesture.Dragged
            else CharacterGesture.Tap(position)
        val delta = position - down.position
        if (!dragged && delta.x * delta.x + delta.y * delta.y > touchSlop * touchSlop)
            dragged = true
        if (dragged) {
            onDrag(position)
            change.consume()
        }
    }
}

@Composable
private fun Balloon(name: String, text: String, visible: Boolean,
    choices: List<PresentedChoice>, dialogueToken: DialogueToken?,
    onChoose: (InteractionToken) -> Unit, onOpenLink: (DialogueToken, String) -> Unit,
    onInertTap: () -> Unit, tailAnchor: Dp,
    modifier: Modifier) {
    if (!visible) {
        Box(modifier)
        return
    }
    var linkActivated by remember { mutableStateOf(false) }
    val currentInertTap by rememberUpdatedState(onInertTap)
    Box(modifier.padding(4.dp).drawBehind {
        val outline = 2.dp.toPx()
        val radius = minOf(14.dp.toPx(), size.width / 3f, size.height / 3f)
        val tailDepth = minOf(5.dp.toPx(), size.height / 10f)
        val tailWidth = minOf(30.dp.toPx(), size.width / 3f)
        val tailX = tailAnchor.toPx().coerceIn(0f, size.width)
        val tailLeft = (tailX - tailWidth / 2f).coerceAtLeast(0f)
        val tailRight = (tailX + tailWidth / 2f).coerceAtMost(size.width)
        val bottom = size.height - minOf(3.dp.toPx(), size.height / 10f)
        val frame = Path().apply {
            moveTo(0f, radius)
            quadraticTo(0f, 0f, radius, 0f)
            lineTo(size.width - radius, 0f)
            quadraticTo(size.width, 0f, size.width, radius)
            lineTo(size.width, bottom)
            lineTo(tailRight, bottom)
            lineTo(tailX, bottom + tailDepth)
            lineTo(tailLeft, bottom)
            lineTo(0f, bottom)
            close()
        }
        translate(1.dp.toPx(), 2.dp.toPx()) {
            drawPath(frame, Color(0x330D0803))
        }
        drawPath(frame, Color(0xFFFFF8E9))
        drawPath(frame, Color(0xFF8B7460), style = Stroke(outline))
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            linkActivated = false
            if (!down.isConsumed) {
                val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                if (up != null && !up.isConsumed && !linkActivated) currentInertTap()
            }
        }
    }
        .semantics { contentDescription = "$name dialogue" }.testTag("balloon-$name")) {
        BalloonContent(text, choices, dialogueToken, onChoose, { token, url ->
            linkActivated = true
            onOpenLink(token, url)
        })
    }
}
