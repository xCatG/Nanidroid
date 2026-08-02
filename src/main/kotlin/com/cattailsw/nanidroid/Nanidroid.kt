package com.cattailsw.nanidroid

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Message
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.NanidroidSimpleDialog
import com.cattailsw.nanidroid.compose.ComposeGhostStageHost
import com.cattailsw.nanidroid.compose.PlainTextDocument
import com.cattailsw.nanidroid.compose.SurfaceInteractionEffect
import com.cattailsw.nanidroid.compose.SurfaceInteractionPort
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.CrashReporting
import com.cattailsw.nanidroid.util.NarUtil
import com.cattailsw.nanidroid.util.PrefUtil
import com.cattailsw.nanidroid.install.NarContentUriImport
import com.cattailsw.nanidroid.install.NarDownloadRepository
import com.cattailsw.nanidroid.install.NarLocalArchiveStager
import com.cattailsw.nanidroid.install.NarDownloadState
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Arrays

internal fun ownsGhostSwitchRequest(targetGhostId: String, pendingGhostId: String?): Boolean =
    targetGhostId == pendingGhostId

/**
 * The production activity. Compose owns both chrome and ghost presentation;
 * SScriptRunner supplies immutable frames through KotlinGhostPresentationRuntime.
 */
class Nanidroid : ComponentActivity(), SScriptRunner.UICallback {

    private var loading by mutableStateOf(true)
    private var progressMessage by mutableStateOf("")
    private var toolbarVisible by mutableStateOf(false)
    private var simpleDialog: NanidroidSimpleDialog? by mutableStateOf(null)
    private var anime: AnimationDrawable? = null
    private var runner: SScriptRunner? = null
    private val composeStage = ComposeGhostStageHost(
        SurfaceInteractionPort { effect ->
            if (effect is SurfaceInteractionEffect.MouseDoubleClick) {
                runner?.dispatchComposeDoubleClick(
                    effect.x, effect.y, effect.speaker.legacyReference == "0", effect.collisionId, effect.buttonId,
                )
            }
        },
    )
    private var gm: GhostMgr? = null
    private var currentGhost: Ghost? = null
    private var restoreFromMinimize = false
    private var currentRunCount = -1L
    private var initComplete = false
    private var surfaceKeys: Array<String>? = null
    private var keyindex = 0
    private var currentSurfaceKey: String? = null
    private var currentSurface: ShellSurface? = null
    private var animeIndex = 0
    private var nextGhostId: String? = null
    private var awaitingNarDocument = false
    private var replacingNarDownloadId: String? = null
    private var consumedArchiveIntentUri: String? = null
    private var pendingArchiveIntentUri: String? = null
    private var pendingArchiveIntentFlags = 0
    private val narDownloads by lazy { NarDownloadRepository.get(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        awaitingNarDocument = savedInstanceState?.getBoolean(NAR_PICK_PENDING, false) ?: false
        replacingNarDownloadId = savedInstanceState?.getString(NAR_PICK_REPLACEMENT_ID)
        consumedArchiveIntentUri = savedInstanceState?.getString(NAR_CONSUMED_INTENT_URI)
        pendingArchiveIntentUri = savedInstanceState?.getString(NAR_PENDING_INTENT_URI)
        pendingArchiveIntentFlags = savedInstanceState?.getInt(NAR_PENDING_INTENT_FLAGS, 0) ?: 0
        handleIncomingIntent(intent)
        val dbgBuild = isDbgBuild()
        initGA()
        setupViews(dbgBuild)
        restoreSimpleDialog(savedInstanceState)
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED, true)) {
            simpleDialog = NanidroidSimpleDialog.Notice(
                R.string.err_title,
                R.string.err_no_sdcard,
                onConfirm = { finish() },
            )
            return
        }
        checkIsRestore(savedInstanceState)
        runner = SScriptRunner.getInstance(this)
        initOnSeparateThread()
    }

    @Suppress("DEPRECATION")
    private fun initOnSeparateThread() {
        object : AsyncTask<Void, Void, Void>() {
            override fun doInBackground(vararg params: Void?): Void? {
                createSvcs2ndThread()
                if (gm!!.getGhostCount() == 0) installFirstGhost()
                createGhost()
                currentRunCount = getStartCount()
                if (currentRunCount == 0L) loadFirstRunScript()
                setStartCount(++currentRunCount)
                NarUtil.createNarDirOnSDCard()
                return null
            }
            override fun onPostExecute(result: Void?) {
                // Compose state and its caches are main-thread owned.  The
                // ghost files were prepared above; bind them to the stage only
                // after AsyncTask returns to the UI thread.
                setGhostToRunner(currentGhost!!)
                enqueuePendingArchiveIntent()
                dbgRelatedSetup(currentGhost!!)
                hideProgress()
                initComplete = true
                runner!!.startClock()
                runner!!.run()
            }
        }.execute()
    }

    private fun createSvcs2ndThread() { gm = GhostMgr(this) }
    private fun createGhost() {
        val lastId = gm!!.getLastRunGhostId() ?: "nanidroid"
        mGH.sendEmptyMessage(MSG_LOAD_F)
        val ghost = gm!!.createGhost(lastId)!!
        CrashReporting.setCustomKey("current_ghost", ghost.getGhostId())
        gm!!.setLastRunGhost(ghost)
        currentGhost = ghost
    }
    private fun setGhostToRunner(ghost: Ghost) {
        composeStage.setSurfaceManager(ghost.mgr)
        runner!!.setPresentationRenderer(composeStage.renderer)
        // The runner remains attached precisely once, on the initialized UI thread.
        runner!!.setUICallback(this@Nanidroid)
        runner!!.setGhost(ghost)
    }
    private fun setupViews(dbgBuild: Boolean) {
        progressMessage = getString(R.string.prog_startup)
        setContent {
            val downloads by narDownloads.observeDownloads().collectAsState()
            LaunchedEffect(downloads) {
                if (downloads.any { it.state is NarDownloadState.Complete }) gm?.refreshGhost()
            }
            NanidroidComposeShell(
                ghostStage = { composeStage.Stage(onSurfaceTap = ::frameClick) },
                loading = loading,
                progressMessage = progressMessage,
                toolbarVisible = toolbarVisible,
                onListGhost = ::onListGhost,
                onUpdate = ::onUpdate,
                onPreferences = ::onSetupClick,
                onHelp = ::onHelp,
                onArchiveQueue = {
                    simpleDialog = NanidroidSimpleDialog.ArchiveQueue(
                        onRetry = { narDownloads.retry(it) },
                        onReselect = { startInstallFromSDCard(it) },
                        onDelete = { narDownloads.delete(it) },
                    )
                },
                archiveDownloads = downloads,
                showDebugControls = dbgBuild,
                onNextSurface = ::onNextSurface,
                onAnimate = ::onAnimate,
                onNextGhost = ::onNextGhost,
                onRun = ::runClick,
                onNarTest = ::narTest,
                onStageClick = ::frameClick,
                simpleDialog = simpleDialog,
                onDismissSimpleDialog = { simpleDialog = null },
            )
        }
        showProgress()
    }
    private fun showProgress() { loading = true }
    private fun hideProgress() { loading = false; toolbarVisible = true }
    private fun checkIsRestore(state: Bundle?): Boolean {
        if (state != null) { Log.d(TAG, "was minimized"); restoreFromMinimize = state.getBoolean(MIN_TAG, false); return restoreFromMinimize }; return false
    }
    private fun isDbgBuild(): Boolean = try {
        (packageManager.getApplicationInfo("com.cattailsw.nanidroid", PackageManager.GET_META_DATA).flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (_: Exception) { false }
    private fun getStartCount() = PrefUtil.getKeyValueLong(applicationContext, PREF_KEY_LAUNCH_TIME)
    private fun setStartCount(count: Long) = PrefUtil.setKey(applicationContext, PREF_KEY_LAUNCH_TIME, count)
    private fun loadFirstRunScript() = try {
        BufferedReader(InputStreamReader(resources.openRawResource(R.raw.first_run_script), "UTF-8")).use { br ->
            var line = br.readLine(); while (line != null) { if (line.isNotEmpty() && !line.startsWith("#")) runner!!.addMsgToQueue(arrayOf(line)); line = br.readLine() }
        }
    } catch (_: Exception) { runner!!.addMsgToQueue(arrayOf("\\0Oops, something wrong with first run script!\\e")) }
    private fun initGA() { val enabled = PreferenceManager.getDefaultSharedPreferences(applicationContext).getBoolean(Setup.PREF_KEY_USE_ANALYTICS, true); AnalyticsUtils.getInstance(applicationContext, Setup.UA_CODE, enabled); AnalyticsUtils.getInstance(applicationContext).dispatch() }
    private fun dbgRelatedSetup(ghost: Ghost) { updateSurfaceKeys(ghost); currentSurfaceKey = surfaceKeys!![0]; currentSurface = ghost.mgr!!.getSakuraSurface(currentSurfaceKey!!) }
    private fun updateSurfaceKeys(ghost: Ghost) { surfaceKeys = ghost.mgr!!.getSurfaceKeys().toTypedArray(); Arrays.sort(surfaceKeys) }

    override fun onPause() { super.onPause(); runner?.stopClock(); sendStopIntent() }
    override fun onSaveInstanceState(outState: Bundle) {
        saveSimpleDialog(outState)
        outState.putBoolean(NAR_PICK_PENDING, awaitingNarDocument)
        outState.putString(NAR_PICK_REPLACEMENT_ID, replacingNarDownloadId)
        outState.putString(NAR_CONSUMED_INTENT_URI, consumedArchiveIntentUri)
        outState.putString(NAR_PENDING_INTENT_URI, pendingArchiveIntentUri)
        outState.putInt(NAR_PENDING_INTENT_FLAGS, pendingArchiveIntentFlags)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { super.onDestroy(); sendStopIntent() }
    override fun onResume() { super.onResume(); if (initComplete) { runner?.startClock(); runner?.run() }; AnalyticsUtils.getInstance(applicationContext).trackPageView(TAG) }
    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val activeRunner = runner
            if (activeRunner != null) {
                activeRunner.stopClock()
                activeRunner.setCallback(mscb)
                activeRunner.stop()
                activeRunner.doExit()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }
    private val mscb = object : SScriptRunner.StatusCallback {
        override fun stop() = Unit
        override fun canExit() { runner!!.setCallback(null); finish() }
        override fun ghostSwitchScriptComplete() { runner!!.setCallback(null); runOnUiThread(ghostSwitchStep2Caller) }
    }
    private val mGH = object : Handler() { override fun handleMessage(m: Message) { when (m.what) { MSG_START -> progressMessage = getString(R.string.prog_startup); MSG_LOAD_F -> progressMessage = String.format(getString(R.string.load_g), gm!!.getGhostDispName(gm!!.getLastRunGhostId() ?: "nanidroid")); MSG_LOAD_N -> (m.obj as? String)?.let { ghostId -> progressMessage = String.format(getString(R.string.load_g), gm!!.getGhostDispName(ghostId)) } } } }
    private val ghostSwitchStep2Caller = Runnable { showProgress(); ghostSwitchStep2() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus) }
    fun onNextSurface() {
        val keys = surfaceKeys ?: return
        keyindex = if (keyindex < keys.size - 1) keyindex + 1 else 0
        currentSurfaceKey = keys[keyindex]
        currentSurface = currentGhost?.mgr?.getSakuraSurface(currentSurfaceKey!!)
        // Debug selection remains a diagnostic only; production frames come
        // from the script runner and are rendered by the Compose host.
        simpleDialog = NanidroidSimpleDialog.DebugMessage("current surface: $currentSurfaceKey")
    }
    fun onAnimate() = Unit
    private fun pickNextAnimation() = Unit
    fun onShowCollision() = Unit
    fun runClick() { runner!!.addMsgToQueue(arrayOf("\\![open,inputbox,lalala]")); runner!!.run() }
    private fun sendStopIntent() { stopService(Intent(this, NanidroidService::class.java)) }
    private fun addNarToDownload(target: Uri) {
        val value = target.toString()
        if (!isApprovedArchiveUrl(value)) {
            Toast.makeText(this, R.string.err_https_nar_only, Toast.LENGTH_LONG).show()
            return
        }
        narDownloads.enqueueRemote(value)
    }
    private fun startModernService(intent: Intent) { if (Build.VERSION.SDK_INT >= 26) { try { javaClass.getMethod("startForegroundService", Intent::class.java).invoke(this, intent); return } catch (e: Exception) { Log.w(TAG, "foreground-service API unavailable", e) } }; startService(intent) }
    fun narTest() { runner!!.addMsgToQueue(arrayOf("\\h\\s[0]\\w4なんやCatGさん？\\n\\n\\q[なにか話して,Manzai]\n\\q[モードチェンジ,ChangeMode]\\n\\q[各種設定,OpenSetup]\\n\\n\\q[取り消し,Cancel]\\e\\e")); runner!!.run() }
    private fun extractNar(targetPath: String) = extractNar(targetPath, false)
    private fun extractNar(targetPath: String, force: Boolean) { val ghostId = NarUtil.readNarGhostId(targetPath); if (ghostId == null) { runner?.doShioriEvent("OnInstallFailure", null); AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "ghost_install", "cannot read $targetPath", -1); return }; if (!gm!!.hasSameGhostId(ghostId) || force) { runner?.doInstallBegin(ghostId); InstallTask(targetPath, ghostId).execute(targetPath) } else { runner?.doShioriEvent("OnInstallRefuse", null); AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_ERR, "ghost_install", ghostId, -2) } }
    private fun onSuccessGhostInstall(ghostId: String, path: String) { runner?.doInstallComplete(ghostId); AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_PGM_FLOW, "ghost_install", ghostId, 1); val readme = File(path, "readme.txt"); if (readme.exists()) showReadme(readme, ghostId) else showGhostInstalledDlg(ghostId) }
    @Suppress("DEPRECATION") private inner class InstallTask(private val targetPath: String, private val ghostId: String) : AsyncTask<String, Int, String>() { override fun doInBackground(vararg params: String): String? = gm!!.installGhost(ghostId, targetPath); override fun onPostExecute(path: String?) { if (path != null) onSuccessGhostInstall(ghostId, path) else { runner?.doShioriEvent("OnInstallFailure", null); gm!!.getLastInstallError()?.takeIf { it.isNotEmpty() }?.let { Toast.makeText(this@Nanidroid, it, Toast.LENGTH_LONG).show() }; AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "ghost_install", ghostId, -1) } } }
    private fun installFirstGhost() { try { assets.open("nanidroid.zip").use { input -> val target = File(externalCacheDir, "nanidroid.nar"); NarUtil.copyFile(input, FileOutputStream(target)); gm!!.installFirstGhost("nanidroid", target.path) } } catch (e: IOException) { e.printStackTrace() } }
    private fun showReadme(readme: File, ghostId: String) {
        AnalyticsUtils.getInstance(applicationContext).trackPageView("/${Setup.DLG_README}:$ghostId")
        simpleDialog = createReadmeDialog(readme, ghostId)
    }
    private fun showGhostInstalledDlg(ghostId: String) {
        AnalyticsUtils.getInstance(applicationContext).trackPageView("/${Setup.DLG_NO_REAMDE}:$ghostId")
        simpleDialog = createNoReadmeDialog(ghostId, gm!!.getGhostDispName(ghostId) ?: ghostId)
    }
    fun onNextGhost() {
        simpleDialog = NanidroidSimpleDialog.DebugMessage(currentGhost!!.mgr!!.dumpSurfaces())
    }
    fun switchGhost(nextId: String) { val name = gm!!.getGhostSakuraName(nextId) ?: run { Log.d(TAG, "invalid next ghost id"); return }; nextGhostId = nextId; runner!!.stopClock(); runner!!.clearMsgQueue(); runner!!.setCallback(mscb); runner!!.doGhostChanging(name, "manual", gm!!.getGhostPath(nextId)); AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_PGM_FLOW, "ghost_switch", nextGhostId, 0) }
    @Suppress("DEPRECATION") fun ghostSwitchStep2() {
        val targetGhostId = nextGhostId ?: run {
            Log.w(TAG, "ghost switch completed without a target ghost")
            hideProgress()
            return
        }
        object : AsyncTask<Void, Void, Ghost?>() {
        override fun onPreExecute() { mGH.obtainMessage(MSG_LOAD_N, targetGhostId).sendToTarget(); showProgress() }
        override fun doInBackground(vararg params: Void?): Ghost? = try {
            gm!!.createGhost(targetGhostId)
        } catch (e: Exception) {
            AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "ghost_switch", targetGhostId, -1)
            Log.d(TAG, "failed to switch to ghost:$targetGhostId")
            e.printStackTrace()
            null
        }
        override fun onPostExecute(ghost: Ghost?) {
            if (!ownsGhostSwitchRequest(targetGhostId, nextGhostId)) return
            nextGhostId = null
            hideProgress()
            if (ghost == null) return
            CrashReporting.setCustomKey("current_ghost", ghost.getGhostId())
            currentGhost = ghost
            // Keep the Compose stage and runner on the UI thread; its frame
            // cache and scheduler state are intentionally not synchronized.
            composeStage.setSurfaceManager(ghost.mgr)
            updateSurfaceKeys(ghost)
            keyindex = 0
            currentSurfaceKey = surfaceKeys!![keyindex]
            gm!!.setLastRunGhost(ghost)
            runner!!.setGhost(ghost)
            runner!!.startClock()
        }
    }.execute()
    }
    private fun handleIncomingIntent(incoming: Intent?) {
        val resolvedMimeType = incoming?.type ?: runCatching {
            incoming?.data?.let(contentResolver::getType)
        }.getOrNull()
        val uri = ArchiveIntentAdapter.contentUri(incoming, resolvedMimeType) ?: return
        if (consumedArchiveIntentUri == uri.toString()) return
        consumedArchiveIntentUri = uri.toString()
        pendingArchiveIntentUri = uri.toString()
        pendingArchiveIntentFlags = incoming?.flags ?: 0
    }

    private fun enqueuePendingArchiveIntent() {
        val location = pendingArchiveIntentUri ?: return
        pendingArchiveIntentUri = null
        enqueueLocalArchive(Uri.parse(location), pendingArchiveIntentFlags)
        pendingArchiveIntentFlags = 0
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumedArchiveIntentUri = null
        pendingArchiveIntentUri = null
        pendingArchiveIntentFlags = 0
        handleIncomingIntent(intent)
        if (initComplete) enqueuePendingArchiveIntent()
    }
    fun onUpdate() { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "Update", "", 0); val home = runner!!.getStringValueFromShiori("homeurl") ?: return; runner!!.doShioriEvent("OnUpdateBegin", arrayOf(currentGhost!!.getGhostName(), currentGhost!!.getGhostPath())); startModernService(NanidroidService.createUpdateIntent(this, home, currentGhost!!.getGhostId(), currentGhost!!.getGhostPath())) }
    fun onListGhost() { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "list_ghost", "", 0); showGhostListDlg() }
    fun onHelp() {
        AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "help", "", 0)
        AnalyticsUtils.getInstance(this).trackPageView("/Help_menu")
        simpleDialog = createHelpMenuDialog()
    }
    fun getMoreGhost(source: Int) {
        AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "MoreGhost", if (source == 0) "MainUI" else Setup.DLG_G_LIST, source)
        AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_MORE_G}")
        simpleDialog = createMoreGhostDialog()
    }
    private fun startInstallFromSDCard(replaceId: String? = null) {
        AnalyticsUtils.getInstance(applicationContext).trackEvent(
            Setup.ANA_UI_TOUCH, "more_ghost_install_sd", "install_from_sd", 0,
        )
        awaitingNarDocument = true
        replacingNarDownloadId = replaceId
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, NAR_PICK_REQUEST)
    }
    fun showNarErrDlg(dir: Boolean) {
        AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "more_ghost_install_sd", if (dir) "no_nar_folder" else "no_nar_file", if (dir) -1 else -2)
        simpleDialog = NanidroidSimpleDialog.Notice(
            R.string.err_nar_title,
            if (dir) R.string.err_no_nar_folder else R.string.err_no_nar_file,
        )
    }
    private fun importPickedNar(uri: Uri, replacementId: String?) =
        enqueueLocalArchive(uri, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION, replacementId)

    private fun enqueueLocalArchive(uri: Uri, flags: Int, replacementId: String? = null) {
        val retainedItemId = replacementId ?: narDownloads.retainLocalSourceForCopy(uri.toString()).id
        object : AsyncTask<Void, Void, NarLocalArchiveStager.Result>() {
            override fun doInBackground(vararg params: Void?): NarLocalArchiveStager.Result {
                val canPersist = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
                if (canPersist) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        return NarLocalArchiveStager.Result.Staged(uri.toString())
                    } catch (_: SecurityException) { /* Copy the one-shot grant below. */ }
                }
                return NarLocalArchiveStager.stage(File(filesDir, "nar-local-imports")) {
                    contentResolver.openInputStream(uri)
                }
            }

            override fun onPostExecute(result: NarLocalArchiveStager.Result) {
                when (result) {
                    is NarLocalArchiveStager.Result.Staged -> {
                        if (narDownloads.replaceLocalSource(retainedItemId, result.location) == null) {
                            discardUnclaimedArchive(uri, result.location)
                        }
                    }
                    is NarLocalArchiveStager.Result.Failed -> {
                        Toast.makeText(this@Nanidroid, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.execute()
    }

    private fun discardUnclaimedArchive(sourceUri: Uri, location: String) {
        if (location == sourceUri.toString() && !narDownloads.isSourceReferenced(location)) {
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    sourceUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        } else {
            NarLocalArchiveStager.discard(location)
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != NAR_PICK_REQUEST || !awaitingNarDocument) return
        awaitingNarDocument = false
        val replacementId = replacingNarDownloadId
        replacingNarDownloadId = null
        if (resultCode == RESULT_OK) {
            data?.data?.let { importPickedNar(it, replacementId) }
                ?: Toast.makeText(this, "The selected document is no longer available.", Toast.LENGTH_LONG).show()
        }
    }
    private fun showUrlDlg() { AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_E_URL}"); simpleDialog = createUrlEntryDialog() }
    private fun showGhostTown() {
        AnalyticsUtils.getInstance(this).trackPageView("/ghost_town_portal")
        simpleDialog = NanidroidSimpleDialog.Notice(R.string.not_implemeted_title, R.string.not_implemented)
    }
    fun onMoreGhost() = getMoreGhost(0)
    private fun showGhostListDlg() {
        val manager = gm ?: return
        AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_G_LIST}")
        simpleDialog = createGhostListDialog(
            manager.getGDispNames()?.map { it ?: "" }.orEmpty(),
            manager.getGnames()?.toList().orEmpty(),
        )
    }
    private fun showHelp() {
        AnalyticsUtils.getInstance(applicationContext).trackPageView("/help")
        simpleDialog = createGeneralHelpDialog()
    }
    private fun createHelpMenuDialog() = NanidroidSimpleDialog.HelpMenu(
        onGeneralHelp = { showHelp() }, onAbout = { showAbout() }, onFeedback = { showFeedback() },
    )
    private fun createGeneralHelpDialog() = NanidroidSimpleDialog.GeneralHelp(
        onInstallHelp = { openHelpPage(R.string.url_help_install, "/help/install") },
        onSupportedOperations = { openHelpPage(R.string.url_support_ops, "/help/supported_ops") },
    )
    private fun createMoreGhostDialog() = NanidroidSimpleDialog.MoreGhost(
        onEnterUrl = { showUrlDlg() }, onInstallFromSdCard = { startInstallFromSDCard() }, onGhostTown = { showGhostTown() },
    )
    private fun createUrlEntryDialog(value: String = "", error: Boolean = false): NanidroidSimpleDialog.UrlEntry = NanidroidSimpleDialog.UrlEntry(
        value, error,
        onValueChanged = { simpleDialog = createUrlEntryDialog(it) },
        onSubmit = { url ->
            if (!PatternHolders.url_ptrn.matcher(url).find() || !isApprovedArchiveUrl(url)) false
            else { addNarToDownload(Uri.parse(url)); true }
        },
        onInvalid = { simpleDialog = createUrlEntryDialog(value, true) },
    )

    private fun isApprovedArchiveUrl(value: String): Boolean = try {
        val parsed = java.net.URI(value)
        parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    } catch (_: Exception) { false }
    private fun createUserInputDialog(id: String, value: String = ""): NanidroidSimpleDialog.UserInput = NanidroidSimpleDialog.UserInput(
        id, value,
        onValueChanged = { simpleDialog = createUserInputDialog(id, it) },
        onSubmit = { inputId, input -> onFinishUserInput(inputId, input) },
        onCancel = { onCancelInput() },
    )
    private fun createUserChoiceDialog(labels: List<String>, ids: List<String>) = NanidroidSimpleDialog.UserChoice(
        labels, ids, onChoice = { onChoiceSelect(it) },
    )
    private fun createGhostListDialog(names: List<String>, ids: List<String>) = NanidroidSimpleDialog.GhostList(
        names, ids,
        onSelect = { index -> selectGhostFromList(ids.getOrNull(index), names.getOrNull(index) ?: "", index) },
        onMore = { getMoreGhost(1) },
        onCancel = { AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_BTN, "ghost_list_cancel", "", 0) },
    )
    private fun createReadmeDialog(readme: File, ghostId: String) = NanidroidSimpleDialog.TextDocument(
        getString(R.string.new_ghost_installed_title), PlainTextDocument.read(readme), ::openDocumentLink, ghostId,
        { AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_BTN, "ghost_switch", "ghost_readme_dlg", 0); switchGhost(ghostId) },
    )
    private fun createNoReadmeDialog(ghostId: String, ghostName: String) = NanidroidSimpleDialog.SwitchConfirmation(
        ghostId, ghostName,
        onSwitch = { AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_BTN, "ghost_switch", "ghost_readme_dlg", 1); switchGhost(ghostId) },
        onCancel = { AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_BTN, "close", "ghost_readme_dlg", 1) },
    )
    private fun createAboutDialog() = NanidroidSimpleDialog.TextDocument(
        getString(R.string.about_title),
        "Nanidroid\n\nCopyright 2012-2014 Yenchi Lin\n\nLicensed under the Apache License, Version 2.0.\nhttps://www.apache.org/licenses/LICENSE-2.0\n\nCredits\nProgram: CatTail Software LLC\nBuilt-in Ghost & Shell: CatG Studio.\nAndroid Robot artwork is used according to the Android brand guidelines and Creative Commons Attribution 3.0.",
        ::openDocumentLink,
    )
    private fun openDocumentLink(link: String) {
        val uri = Uri.parse(link)
        if (uri.scheme !in setOf("https", "http", "mailto")) return
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    private fun selectGhostFromList(id: String?, name: String, index: Int) {
        val manager = gm ?: return
        val ghostId = id ?: return
        AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_UI_TOUCH, "ghost_list_touch", name, manager.getGhostLaunchCount(index))
        if (manager.getGhostReadMe(ghostId).exists()) showReadme(manager.getGhostReadMe(ghostId), ghostId)
        else simpleDialog = createNoReadmeDialog(ghostId, name)
    }
    private fun saveSimpleDialog(outState: Bundle) {
        when (val dialog = simpleDialog) {
            null -> Unit
            is NanidroidSimpleDialog.Notice -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_NOTICE)
                outState.putInt(SIMPLE_DIALOG_TITLE, dialog.title)
                outState.putInt(SIMPLE_DIALOG_MESSAGE, dialog.message)
            }
            // Surface dumps may be arbitrarily large for installed ghosts. Do
            // not put them in the Activity Bundle (Binder-size bounded).
            is NanidroidSimpleDialog.DebugMessage -> Unit
            is NanidroidSimpleDialog.HelpMenu -> outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_HELP_MENU)
            is NanidroidSimpleDialog.GeneralHelp -> outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_GENERAL_HELP)
            is NanidroidSimpleDialog.MoreGhost -> outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_MORE_GHOST)
            is NanidroidSimpleDialog.UrlEntry -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_URL_ENTRY)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.value)
                outState.putBoolean(SIMPLE_DIALOG_ERROR, dialog.validationError)
            }
            is NanidroidSimpleDialog.UserInput -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_USER_INPUT)
                outState.putString(SIMPLE_DIALOG_ID, dialog.id)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.value)
            }
            is NanidroidSimpleDialog.UserChoice -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_USER_CHOICE)
                outState.putStringArrayList(SIMPLE_DIALOG_LABELS, ArrayList(dialog.labels))
                outState.putStringArrayList(SIMPLE_DIALOG_IDS, ArrayList(dialog.ids))
            }
            is NanidroidSimpleDialog.GhostList -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_GHOST_LIST)
                outState.putStringArrayList(SIMPLE_DIALOG_LABELS, ArrayList(dialog.names))
                outState.putStringArrayList(SIMPLE_DIALOG_IDS, ArrayList(dialog.ids))
            }
            is NanidroidSimpleDialog.ArchiveQueue -> Unit
            is NanidroidSimpleDialog.TextDocument -> {
                outState.putString(SIMPLE_DIALOG_TYPE, if (dialog.onSwitch == null) DIALOG_ABOUT else DIALOG_README)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.text)
                outState.putString(SIMPLE_DIALOG_ID, dialog.sourceId)
            }
            is NanidroidSimpleDialog.SwitchConfirmation -> {
                outState.putString(SIMPLE_DIALOG_TYPE, DIALOG_NO_README)
                outState.putString(SIMPLE_DIALOG_ID, dialog.ghostId)
                outState.putString(SIMPLE_DIALOG_VALUE, dialog.ghostName)
            }
        }
    }
    private fun restoreSimpleDialog(state: Bundle?) {
        simpleDialog = when (state?.getString(SIMPLE_DIALOG_TYPE)) {
            DIALOG_NOTICE -> {
                val title = state.getInt(SIMPLE_DIALOG_TITLE)
                val message = state.getInt(SIMPLE_DIALOG_MESSAGE)
                NanidroidSimpleDialog.Notice(title, message, if (message == R.string.err_no_sdcard) ({ finish() }) else null)
            }
            DIALOG_HELP_MENU -> createHelpMenuDialog()
            DIALOG_GENERAL_HELP -> createGeneralHelpDialog()
            DIALOG_MORE_GHOST -> createMoreGhostDialog()
            DIALOG_URL_ENTRY -> createUrlEntryDialog(state.getString(SIMPLE_DIALOG_VALUE) ?: "", state.getBoolean(SIMPLE_DIALOG_ERROR, false))
            DIALOG_USER_INPUT -> createUserInputDialog(state.getString(SIMPLE_DIALOG_ID) ?: "", state.getString(SIMPLE_DIALOG_VALUE) ?: "")
            DIALOG_USER_CHOICE -> createUserChoiceDialog(state.getStringArrayList(SIMPLE_DIALOG_LABELS)?.toList().orEmpty(), state.getStringArrayList(SIMPLE_DIALOG_IDS)?.toList().orEmpty())
            DIALOG_GHOST_LIST -> createGhostListDialog(state.getStringArrayList(SIMPLE_DIALOG_LABELS)?.toList().orEmpty(), state.getStringArrayList(SIMPLE_DIALOG_IDS)?.toList().orEmpty())
            DIALOG_ABOUT -> createAboutDialog()
            DIALOG_README -> {
                val ghostId = state.getString(SIMPLE_DIALOG_ID).orEmpty()
                NanidroidSimpleDialog.TextDocument(getString(R.string.new_ghost_installed_title), state.getString(SIMPLE_DIALOG_VALUE).orEmpty(), ::openDocumentLink, ghostId, { switchGhost(ghostId) })
            }
            DIALOG_NO_README -> createNoReadmeDialog(state.getString(SIMPLE_DIALOG_ID).orEmpty(), state.getString(SIMPLE_DIALOG_VALUE).orEmpty())
            else -> null
        }
    }
    private fun openHelpPage(urlRes: Int, page: String) {
        AnalyticsUtils.getInstance(this).trackPageView(page)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlRes))))
    }
    private fun showFeedback() { AnalyticsUtils.getInstance(applicationContext).trackPageView("/feedback"); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.feedback_url)))) }
    private fun showAbout() {
        AnalyticsUtils.getInstance(applicationContext).trackPageView("/about")
        simpleDialog = createAboutDialog()
    }
    fun onSetupClick() = showPreference()
    private fun showPreference() { val target = Intent(Intent.ACTION_VIEW); target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET); target.setClassName(this, Preferences::class.java.name); AnalyticsUtils.getInstance(this).trackPageView("/Preference"); startActivity(target) }
    fun frameClick() {
        if (!toolbarVisible) AnalyticsUtils.getInstance(this).trackPageView("/main_btn_bar")
        toolbarVisible = !toolbarVisible
    }
    private fun onFinishUserInput(id: String, userinput: String) { Log.d(TAG, "got user input:$userinput"); runner!!.resumeEvt(); runner!!.doUserInput(id, userinput) }
    private fun onCancelInput() { Log.d(TAG, "user cancel"); runner!!.resumeEvt() }
    override fun showUserInputBox(id: String) { simpleDialog = createUserInputDialog(id) }
    private fun onChoiceSelect(id: String) { runner!!.doOnChoiceSelect(id) }
    override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) { simpleDialog = createUserChoiceDialog(textlabel.toList(), ids.toList()) }

    companion object { private const val TAG = "Nanidroid"; private const val NAR_PICK_REQUEST = 4017; private const val NAR_PICK_PENDING = "nar_picker_pending"; private const val NAR_PICK_REPLACEMENT_ID = "nar_picker_replacement_id"; private const val NAR_CONSUMED_INTENT_URI = "consumed_archive_intent_uri"; private const val NAR_PENDING_INTENT_URI = "pending_archive_intent_uri"; private const val NAR_PENDING_INTENT_FLAGS = "pending_archive_intent_flags"; private const val PREF_KEY_LAUNCH_TIME = "keylaunchtime"; private const val MIN_TAG = "minimized"; private const val MSG_START = 2019; private const val MSG_LOAD_F = 2020; private const val MSG_LOAD_N = 2021; private const val SIMPLE_DIALOG_TYPE = "simple_dialog_type"; private const val SIMPLE_DIALOG_TITLE = "simple_dialog_title"; private const val SIMPLE_DIALOG_MESSAGE = "simple_dialog_message"; private const val SIMPLE_DIALOG_VALUE = "simple_dialog_value"; private const val SIMPLE_DIALOG_ERROR = "simple_dialog_error"; private const val SIMPLE_DIALOG_ID = "simple_dialog_id"; private const val SIMPLE_DIALOG_LABELS = "simple_dialog_labels"; private const val SIMPLE_DIALOG_IDS = "simple_dialog_ids"; private const val DIALOG_NOTICE = "notice"; private const val DIALOG_HELP_MENU = "help_menu"; private const val DIALOG_GENERAL_HELP = "general_help"; private const val DIALOG_MORE_GHOST = "more_ghost"; private const val DIALOG_URL_ENTRY = "url_entry"; private const val DIALOG_USER_INPUT = "user_input"; private const val DIALOG_USER_CHOICE = "user_choice"; private const val DIALOG_GHOST_LIST = "ghost_list"; private const val DIALOG_ABOUT = "about"; private const val DIALOG_README = "readme"; private const val DIALOG_NO_README = "no_readme" }
}
