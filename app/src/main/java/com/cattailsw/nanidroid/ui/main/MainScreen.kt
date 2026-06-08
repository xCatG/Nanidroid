package com.cattailsw.nanidroid.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.cattailsw.nanidroid.*
import com.cattailsw.nanidroid.R
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.init()
    }

    val activeGhost by viewModel.activeGhost.collectAsStateWithLifecycle()
    val installedGhosts by viewModel.installedGhosts.collectAsStateWithLifecycle()
    val isOverlayEnabled by viewModel.isOverlayEnabled.collectAsStateWithLifecycle()
    val consoleLogs by viewModel.consoleLogs.collectAsStateWithLifecycle()

    val currentInputBoxId = viewModel.currentInputBoxId
    val currentSelection = viewModel.currentSelection

    // Shiori/Sakura Script prompted User Input Dialog
    if (currentInputBoxId != null) {
        var textValue by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.onInputCancel() },
            title = { Text("Mascot Prompt") },
            text = {
                Column {
                    Text("The mascot requests input:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textValue,
                        onValueChange = { textValue = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.onInputSubmit(currentInputBoxId, textValue) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onInputCancel() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Shiori/Sakura Script prompted Selection Menu Dialog
    if (currentSelection != null) {
        AlertDialog(
            onDismissRequest = { viewModel.onSelectionCancel() },
            title = { Text("Choose Option") },
            text = {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)
                ) {
                    items(currentSelection.labels.size) { idx ->
                        val label = currentSelection.labels[idx]
                        val selId = currentSelection.ids[idx]
                        Button(
                            onClick = { viewModel.onSelectionSelect(selId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.onSelectionCancel() }) {
                    Text("Cancel")
                }
            }
        )
    }

    MainDashboard(
        activeGhost = activeGhost,
        installedGhosts = installedGhosts,
        isOverlayEnabled = isOverlayEnabled,
        consoleLogs = consoleLogs,
        onSwitchGhost = { id -> viewModel.switchGhost(id) },
        onToggleOverlay = { enabled -> viewModel.toggleOverlay(enabled) },
        onRefreshGhosts = { viewModel.refreshInstalledGhosts() },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainDashboard(
    activeGhost: Ghost?,
    installedGhosts: List<GhostInfo>,
    isOverlayEnabled: Boolean,
    consoleLogs: List<String>,
    onSwitchGhost: (String) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onRefreshGhosts: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val cacheFile = File(context.cacheDir, "temp_upload.nar")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                (context as? MainActivity)?.extractNar(cacheFile.absolutePath) {
                    onRefreshGhosts()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error staging NAR file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "NANIDROID MASCOT HUB",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF38BDF8),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Desktop Ukagaka/Nanika Mascot Engine",
                            fontSize = 10.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showBottomSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color.Transparent,
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E293B)
                    )
                )
            )
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HologramChamber(activeGhost = activeGhost, isOverlayEnabled = isOverlayEnabled)
        }
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            containerColor = Color(0xFF1E293B),
            contentColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF94A3B8)) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ControlCenterCard(
                    activeGhost = activeGhost,
                    isOverlayEnabled = isOverlayEnabled,
                    onToggleOverlay = onToggleOverlay,
                    onInstallClick = { fileLauncher.launch("*/*") },
                    onUpdateClick = {
                        activeGhost?.let { g ->
                            coroutineScope.launch {
                                val runner = SScriptRunner.getInstance(context)
                                val homeurl = runner.getStringValueFromShiori("homeurl")
                                if (homeurl != null) {
                                    val name = g.getGhostName() ?: ""
                                    val path = g.getGhostPath() ?: ""
                                    runner.doShioriEvent("OnUpdateBegin", arrayOf(name, path))
                                    val intent = NanidroidService.createUpdateIntent(context, homeurl, g.getGhostId(), g.getGhostPath())
                                    context.startService(intent)
                                    Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No homeurl defined for updates.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onPreferencesClick = {
                        val intent = Intent(context, Preferences::class.java)
                        context.startActivity(intent)
                    }
                )

                CatalogCard(
                    installedGhosts = installedGhosts,
                    activeGhostId = activeGhost?.getGhostId() ?: "",
                    onSwitchGhost = onSwitchGhost
                )

                ConsoleCard(
                    consoleLogs = consoleLogs,
                    modifier = Modifier.height(150.dp)
                )
            }
        }
    }
}

@Composable
fun HeaderSection() {
    Column {
        Text(
            text = "NANIDROID MASCOT HUB",
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            color = Color(0xFF38BDF8),
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Desktop Ukagaka/Nanika Mascot Engine",
            fontSize = 12.sp,
            color = Color(0xFF94A3B8)
        )
    }
}

@Composable
fun HologramChamber(activeGhost: Ghost?, isOverlayEnabled: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "hologram")
    val scanY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanline"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0x6038BDF8), Color(0x1038BDF8)))),
        colors = CardDefaults.cardColors(containerColor = Color(0x401E293B)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val gridSpacing = 30.dp.toPx()
                val linePaint = Color(0x0A38BDF8)
                
                var x = 0f
                while (x < size.width) {
                    drawLine(linePaint, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += gridSpacing
                }

                var y = 0f
                while (y < size.height) {
                    drawLine(linePaint, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += gridSpacing
                }

                val currentScanY = scanY * size.height
                drawLine(
                    color = Color(0x3038BDF8),
                    start = Offset(0f, currentScanY),
                    end = Offset(size.width, currentScanY),
                    strokeWidth = 3f
                )
            }

            if (activeGhost != null) {
                InAppMascotView(
                    activeGhost = activeGhost,
                    isOverlayEnabled = isOverlayEnabled,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Activating Mascot Interface...",
                        color = Color(0xFF64748B),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

data class MascotViews(
    val layout: FrameLayout,
    val sakura: SakuraView,
    val kero: KeroView,
    val bSakura: Balloon,
    val bKero: Balloon
)

@Composable
fun InAppMascotView(
    activeGhost: Ghost?,
    isOverlayEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var viewsRef by remember { mutableStateOf<MascotViews?>(null) }

    DisposableEffect(lifecycleOwner, activeGhost, isOverlayEnabled, viewsRef) {
        val lm = LayoutManager.getInstance(context)
        val runner = SScriptRunner.getInstance(context)

        fun bindViews() {
            val views = viewsRef ?: return
            val ghost = activeGhost ?: return
            
            if (!isOverlayEnabled) {
                views.sakura.mgr = ghost.mgr
                views.kero.mgr = ghost.mgr
                
                lm.setViews(views.layout, views.sakura, views.kero, views.bSakura, views.bKero)
                runner.setViews(views.sakura, views.kero, views.bSakura, views.bKero)
                runner.setGhost(ghost)
                runner.setLayoutMgr(lm)
                
                views.layout.post {
                    lm.checkAndUpdateLayoutParam()
                }
                
                runner.stopClock()
                runner.startClock()
                runner.run()
            }
        }

        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            bindViews()
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bindViews()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!OverlayMascotService.isRunning) {
                val runner = SScriptRunner.getInstance(context)
                runner.setGhost(null)
                runner.stopClock()
                runner.clearMsgQueue()
                runner.clearViews()
                LayoutManager.getInstance(context).clearViews()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            val layout = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            
            val sv = SakuraView(ctx).apply { tag = "sakura" }
            val kv = KeroView(ctx).apply { tag = "kero" }
            
            val bSakura = Balloon(ctx).apply {
                tag = "bSakura"
                setBackgroundResource(R.drawable.balloon)
                setTextColor(AndroidColor.BLACK)
            }
            val bKero = Balloon(ctx).apply {
                tag = "bKero"
                setBackgroundResource(R.drawable.balloon)
                setTextColor(AndroidColor.BLACK)
            }

            layout.addView(bKero)
            layout.addView(bSakura)
            layout.addView(sv)
            layout.addView(kv)

            viewsRef = MascotViews(layout, sv, kv, bSakura, bKero)
            layout
        },
        update = { layout ->
            val lm = LayoutManager.getInstance(context)
            if (activeGhost != null) {
                val sv = layout.findViewWithTag<SakuraView>("sakura")
                val kv = layout.findViewWithTag<KeroView>("kero")
                val bSakura = layout.findViewWithTag<Balloon>("bSakura")
                val bKero = layout.findViewWithTag<Balloon>("bKero")
                
                if (sv != null && kv != null && bSakura != null && bKero != null) {
                    sv.mgr = activeGhost.mgr
                    kv.mgr = activeGhost.mgr
                    
                    lm.setViews(layout, sv, kv, bSakura, bKero)
                    layout.post {
                        lm.checkAndUpdateLayoutParam()
                    }
                }
            }
        },
        modifier = modifier
    )
}

@Composable
fun ControlCenterCard(
    activeGhost: Ghost?,
    isOverlayEnabled: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onInstallClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onPreferencesClick: () -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x601E293B)),
        border = BorderStroke(1.dp, Color(0x2094A3B8))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "MASCOT CONTROLS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp
            )

            Text(
                text = "Active Mascot: ${activeGhost?.getGhostName() ?: "Loading..."}",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )

            HorizontalDivider(color = Color(0x1AFFFFFF))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Overlay Desktop Mode",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Float companion on other apps",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }

                Switch(
                    checked = isOverlayEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                                Toast.makeText(context, "Please grant Overlay Permission to enable Desktop Mode", Toast.LENGTH_LONG).show()
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                            } else {
                                onToggleOverlay(true)
                            }
                        } else {
                            onToggleOverlay(false)
                        }
                    }
                )
            }

            HorizontalDivider(color = Color(0x1AFFFFFF))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onInstallClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF818CF8))
                ) {
                    Text("Install NAR", fontSize = 11.sp)
                }

                Button(
                    onClick = onUpdateClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Update", fontSize = 11.sp)
                }

                Button(
                    onClick = onPreferencesClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF64748B))
                ) {
                    Text("Settings", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun CatalogCard(
    installedGhosts: List<GhostInfo>,
    activeGhostId: String,
    onSwitchGhost: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0x601E293B)),
        border = BorderStroke(1.dp, Color(0x2094A3B8)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "INSTALLED MASCOTS",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64748B),
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (installedGhosts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No ghosts found in storage.", color = Color(0xFF64748B), fontSize = 13.sp)
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    installedGhosts.forEach { info ->
                        val isActive = info.id.equals(activeGhostId, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) Color(0x2038BDF8) else Color(0x08FFFFFF),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .border(
                                    BorderStroke(1.dp, if (isActive) Color(0xFF38BDF8) else Color.Transparent),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSwitchGhost(info.id) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(info.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(info.id, color = Color(0xFF94A3B8), fontSize = 11.sp)
                            }
                            if (isActive) {
                                Text(
                                    text = "ACTIVE",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConsoleCard(
    consoleLogs: List<String>,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF020617)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text(
                text = "SHIORI SYSTEM LOG",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF10B981)
            )
            
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(consoleLogs) { log ->
                    Text(
                        text = "> $log",
                        color = Color(0xFF34D399),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
