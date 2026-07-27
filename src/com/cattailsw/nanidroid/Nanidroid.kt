package com.cattailsw.nanidroid

import android.app.WallpaperManager
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
import android.support.v4.app.DialogFragment
import android.support.v4.app.FragmentActivity
import android.util.Log
import android.view.ContextMenu
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.cattailsw.nanidroid.dlgs.*
import com.cattailsw.nanidroid.compose.NanidroidComposeShell
import com.cattailsw.nanidroid.compose.ComposeShellLifecycleOwner
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.CrashReporting
import com.cattailsw.nanidroid.util.NarUtil
import com.cattailsw.nanidroid.util.PrefUtil
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.Arrays

/**
 * The legacy activity, expressed in Kotlin without changing its view-stage
 * ownership or callback boundary. Compose owns the app chrome while the ghost
 * renderer remains one retained AndroidView boundary. XML callbacks and the
 * SScriptRunner UICallback contract stay here until their own migrations.
 */
class Nanidroid : FragmentActivity(), EnterUrlDlg.EUrlDlgListener,
    NarPickDlg.NarPickDlgListener, MoreGhostFuncDlg.MoreGhostFuncListener,
    UserInputDlg.UserInputListener, UserSelectDlg.UserSelDlgListener,
    SScriptRunner.UICallback {

    private var sv: SakuraView? = null
    private var kv: KeroView? = null
    private var bSakura: Balloon? = null
    private var bKero: Balloon? = null
    private var fl: FrameLayout? = null
    private var loading by mutableStateOf(true)
    private var progressMessage by mutableStateOf("")
    private var toolbarVisible by mutableStateOf(false)
    private val composeLifecycleOwner = ComposeShellLifecycleOwner()
    private var anime: AnimationDrawable? = null
    private var lm: LayoutManager? = null
    private var runner: SScriptRunner? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dbgBuild = isDbgBuild()
        initGA()
        setupViews(dbgBuild)
        setBackground()
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED, true)) {
            bSakura!!.text = "sd card error"
            ErrMsgDlg.newInstance(R.string.err_title, R.string.err_no_sdcard, ecb, FLAG_SD_ERR)
                .show(supportFragmentManager, Setup.DLG_ERR)
            return
        }
        checkIsRestore(savedInstanceState)
        runner = SScriptRunner.getInstance(this)
        initOnSeparateThread()
        ViewServerLifecycle.onActivityCreated(this)
    }

    @Suppress("DEPRECATION")
    private fun initOnSeparateThread() {
        object : AsyncTask<Void, Void, Void>() {
            override fun doInBackground(vararg params: Void?): Void? {
                createSvcs2ndThread()
                if (gm!!.getGhostCount() == 0) installFirstGhost()
                createGhost()
                setGhostToRunner(currentGhost!!)
                currentRunCount = getStartCount()
                if (currentRunCount == 0L) loadFirstRunScript()
                setStartCount(++currentRunCount)
                NarUtil.createNarDirOnSDCard()
                return null
            }
            override fun onPostExecute(result: Void?) {
                handleIncomingIntent(intent)
                dbgRelatedSetup(currentGhost!!)
                hideProgress()
                initComplete = true
                runner!!.startClock()
                runner!!.run()
            }
        }.execute()
    }

    private fun createSvcs2ndThread() { lm = LayoutManager.getInstance(this); gm = GhostMgr(this) }
    private fun createGhost() {
        val lastId = gm!!.getLastRunGhostId() ?: "nanidroid"
        mGH.sendEmptyMessage(MSG_LOAD_F)
        val ghost = gm!!.createGhost(lastId)!!
        CrashReporting.setCustomKey("current_ghost", ghost.getGhostId())
        runner!!.setGhost(ghost)
        gm!!.setLastRunGhost(ghost)
        currentGhost = ghost
    }
    private fun setGhostToRunner(ghost: Ghost) {
        runner!!.setViews(sv, kv, bSakura, bKero)
        sv!!.mgr = ghost.mgr
        kv!!.mgr = ghost.mgr
        lm!!.setViews(fl!!, sv!!, kv!!, bSakura!!, bKero!!)
        runner!!.setLayoutMgr(lm)
        // The runner remains attached precisely once, on the initialized UI thread.
        runner!!.setUICallback(this@Nanidroid)
    }
    private fun setupViews(dbgBuild: Boolean) {
        val stage = FrameLayout(this).apply {
            id = R.id.fl
            setOnClickListener { frameClick(it) }
        }
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        bKero = Balloon(this).apply {
            id = R.id.bKero
            setBackgroundResource(R.drawable.balloon)
            setTextColor(android.graphics.Color.BLACK)
        }
        bSakura = Balloon(this).apply {
            id = R.id.bSakura
            setBackgroundResource(R.drawable.balloon)
            setTextColor(android.graphics.Color.BLACK)
        }
        sv = SakuraView(this).apply { id = R.id.sakura_display }
        kv = KeroView(this).apply { id = R.id.kero_display }
        stage.addView(bKero, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ))
        stage.addView(bSakura, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ))
        stage.addView(sv, FrameLayout.LayoutParams(dp(180), dp(240), Gravity.BOTTOM or Gravity.END))
        stage.addView(kv, FrameLayout.LayoutParams(dp(126), dp(168), Gravity.BOTTOM or Gravity.START))
        fl = stage
        progressMessage = getString(R.string.prog_startup)
        // The old XML layout is deliberately retained for the frozen Ant source.
        // Gradle's modern activity now owns its chrome declaratively.
        val composeRoot = ComposeView(this)
        composeLifecycleOwner.install(composeRoot)
        composeRoot.setContent {
            NanidroidComposeShell(
                ghostStage = stage,
                loading = loading,
                progressMessage = progressMessage,
                toolbarVisible = toolbarVisible,
                onListGhost = { onListGhost(stage) },
                onUpdate = { onUpdate(stage) },
                onPreferences = { onSetupClick(stage) },
                onHelp = { onHelp(stage) },
                showDebugControls = dbgBuild,
                onNextSurface = { onNextSurface(stage) },
                onAnimate = { onAnimate(stage) },
                onNextGhost = { onNextGhost(stage) },
                onRun = { runClick(stage) },
                onNarTest = { narTest(stage) },
            )
        }
        setContentView(composeRoot)
        registerForContextMenu(stage)
        showProgress()
    }
    private fun showProgress() { loading = true }
    private fun hideProgress() { loading = false; toolbarVisible = true }
    private fun checkIsRestore(state: Bundle?): Boolean {
        if (state != null) { Log.d(TAG, "was minimized"); restoreFromMinimize = state.getBoolean(MIN_TAG, false); return restoreFromMinimize }; return false
    }
    @Suppress("DEPRECATION") private fun setBackground() {
        try { findViewById<View>(android.R.id.content).background = WallpaperManager.getInstance(applicationContext).fastDrawable }
        catch (denied: SecurityException) { Log.w(TAG, "wallpaper background unavailable", denied) }
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

    override fun onPause() { composeLifecycleOwner.pause(); super.onPause(); runner?.stopClock(); sendStopIntent() }
    override fun onDestroy() { composeLifecycleOwner.destroy(); super.onDestroy(); ViewServerLifecycle.onActivityDestroyed(this); sendStopIntent() }
    override fun onResume() { super.onResume(); composeLifecycleOwner.resume(); if (initComplete) { runner?.startClock(); runner?.run() }; AnalyticsUtils.getInstance(applicationContext).trackPageView(TAG); ViewServerLifecycle.onActivityResumed(this) }
    @Suppress("DEPRECATION") override fun onBackPressed() { val r = runner; if (r != null) { r.stopClock(); r.setCallback(mscb); r.stop(); r.doExit() } else super.onBackPressed() }
    private val mscb = object : SScriptRunner.StatusCallback {
        override fun stop() = Unit
        override fun canExit() { runner!!.setCallback(null); finish() }
        override fun ghostSwitchScriptComplete() { runner!!.setCallback(null); runOnUiThread(ghostSwitchStep2Caller) }
    }
    private val mGH = object : Handler() { override fun handleMessage(m: Message) { when (m.what) { MSG_START -> progressMessage = getString(R.string.prog_startup); MSG_LOAD_F -> progressMessage = String.format(getString(R.string.load_g), gm!!.getGhostDispName(gm!!.getLastRunGhostId() ?: "nanidroid")); MSG_LOAD_N -> progressMessage = String.format(getString(R.string.load_g), gm!!.getGhostDispName(nextGhostId!!)) } } }
    private val ghostSwitchStep2Caller = Runnable { showProgress(); ghostSwitchStep2() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (initComplete) lm!!.checkAndUpdateLayoutParam() }
    private fun checkAndLoadAnimation() { sv!!.changeSurface(currentSurfaceKey!!); kv!!.changeSurface("10"); lm!!.checkAndUpdateLayoutParam(); if (!sv!!.hasAnimation()) findViewById<View>(R.id.btn2)?.isEnabled = false else { animeIndex = currentSurface!!.getFirstAnimationIndex(); sv!!.loadAnimation("$animeIndex"); findViewById<View>(R.id.btn2)?.isEnabled = true } }
    fun onNextSurface(v: View) { val keys = surfaceKeys!!; keyindex = if (keyindex < keys.size - 1) keyindex + 1 else 0; currentSurfaceKey = keys[keyindex]; Log.d(TAG, "loading surface:$currentSurfaceKey"); currentSurface = sv!!.mgr!!.getSakuraSurface(currentSurfaceKey!!); bSakura!!.text = "current drawable key: $currentSurfaceKey, animation count: ${currentSurface!!.getAnimationCount()}, collision count: ${currentSurface!!.getCollisionCount()}"; checkAndLoadAnimation() }
    fun onAnimate(v: View) = showCollisionAreaOnImageView()
    private fun pickNextAnimation() { if (currentSurface!!.getAnimationCount() > 1) { animeIndex = (animeIndex + 1) % currentSurface!!.getAnimationCount(); sv!!.loadAnimation("$animeIndex") } }
    fun onShowCollision(v: View) = showCollisionAreaOnImageView()
    private fun showCollisionAreaOnImageView() { sv!!.showCollisionArea(); kv!!.showCollisionArea() }
    fun runClick(v: View) { runner!!.addMsgToQueue(arrayOf("\\![open,inputbox,lalala]")); runner!!.run() }
    private fun sendStopIntent() { stopService(Intent(this, NanidroidService::class.java)) }
    private fun addNarToDownload(target: Uri) { if (!IncomingNarIntent.isApprovedDownload(target)) { Toast.makeText(this, R.string.err_https_nar_only, Toast.LENGTH_LONG).show(); return }; startModernService(Intent(this, NanidroidService::class.java).setAction(Intent.ACTION_RUN).setData(target)) }
    private fun startModernService(intent: Intent) { if (Build.VERSION.SDK_INT >= 26) { try { javaClass.getMethod("startForegroundService", Intent::class.java).invoke(this, intent); return } catch (e: Exception) { Log.w(TAG, "foreground-service API unavailable", e) } }; startService(intent) }
    fun narTest(v: View) { runner!!.addMsgToQueue(arrayOf("\\h\\s[0]\\w4なんやCatGさん？\\n\\n\\q[なにか話して,Manzai]\n\\q[モードチェンジ,ChangeMode]\\n\\q[各種設定,OpenSetup]\\n\\n\\q[取り消し,Cancel]\\e\\e")); runner!!.run() }
    private fun extractNar(targetPath: String) = extractNar(targetPath, false)
    private fun extractNar(targetPath: String, force: Boolean) { val ghostId = NarUtil.readNarGhostId(targetPath); if (ghostId == null) { runner?.doShioriEvent("OnInstallFailure", null); AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "ghost_install", "cannot read $targetPath", -1); return }; if (!gm!!.hasSameGhostId(ghostId) || force) { runner?.doInstallBegin(ghostId); InstallTask(targetPath, ghostId).execute(targetPath) } else { runner?.doShioriEvent("OnInstallRefuse", null); AnalyticsUtils.getInstance(this).trackEvent(Setup.ANA_ERR, "ghost_install", ghostId, -2) } }
    private fun onSuccessGhostInstall(ghostId: String, path: String) { runner?.doInstallComplete(ghostId); AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_PGM_FLOW, "ghost_install", ghostId, 1); val readme = File(path, "readme.txt"); if (readme.exists()) showReadme(readme, ghostId) else showGhostInstalledDlg(ghostId) }
    @Suppress("DEPRECATION") private inner class InstallTask(private val targetPath: String, private val ghostId: String) : AsyncTask<String, Int, String>() { override fun doInBackground(vararg params: String): String? = gm!!.installGhost(ghostId, targetPath); override fun onPostExecute(path: String?) { if (path != null) onSuccessGhostInstall(ghostId, path) else { runner?.doShioriEvent("OnInstallFailure", null); gm!!.getLastInstallError()?.takeIf { it.isNotEmpty() }?.let { Toast.makeText(this@Nanidroid, it, Toast.LENGTH_LONG).show() }; AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "ghost_install", ghostId, -1) } } }
    private fun installFirstGhost() { try { assets.open("nanidroid.zip").use { input -> val target = File(externalCacheDir, "nanidroid.nar"); NarUtil.copyFile(input, FileOutputStream(target)); gm!!.installFirstGhost("nanidroid", target.path) } } catch (e: IOException) { e.printStackTrace() } }
    private fun showReadme(readme: File, ghostId: String) { AnalyticsUtils.getInstance(applicationContext).trackPageView("/${Setup.DLG_README}:$ghostId"); ReadmeDialogFragment.newInstance(readme, ghostId).show(supportFragmentManager, Setup.DLG_README) }
    private fun showGhostInstalledDlg(ghostId: String) { AnalyticsUtils.getInstance(applicationContext).trackPageView("/${Setup.DLG_NO_REAMDE}:$ghostId"); NoReadmeSwitchDlg.newInstance(ghostId, gm!!.getGhostDispName(ghostId)).show(supportFragmentManager, Setup.DLG_NO_REAMDE) }
    fun onNextGhost(v: View) { DbgMsgDlg.newInstance(currentGhost!!.mgr!!.dumpSurfaces()).show(supportFragmentManager, Setup.DLG_DBG_MSG) }
    fun switchGhost(nextId: String) { val name = gm!!.getGhostSakuraName(nextId) ?: run { Log.d(TAG, "invalid next ghost id"); return }; nextGhostId = nextId; runner!!.clearMsgQueue(); runner!!.setCallback(mscb); runner!!.doGhostChanging(name, "manual", gm!!.getGhostPath(nextId)); AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_PGM_FLOW, "ghost_switch", nextGhostId, 0) }
    @Suppress("DEPRECATION") fun ghostSwitchStep2() { object : AsyncTask<Void, Void, Void>() { override fun onPreExecute() { mGH.sendEmptyMessage(MSG_LOAD_N); showProgress() }; override fun doInBackground(vararg params: Void?): Void? { try { val ghost = gm!!.createGhost(nextGhostId!!)!!; nextGhostId = null; CrashReporting.setCustomKey("current_ghost", ghost.getGhostId()); currentGhost = ghost; sv!!.mgr = ghost.mgr; kv!!.mgr = ghost.mgr; updateSurfaceKeys(ghost); keyindex = 0; currentSurfaceKey = surfaceKeys!![keyindex] } catch (e: Exception) { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "ghost_switch", nextGhostId, -1); Log.d(TAG, "failed to switch to ghost:$nextGhostId"); nextGhostId = null; e.printStackTrace() }; return null }; override fun onPostExecute(result: Void?) { hideProgress(); lm!!.checkAndUpdateLayoutParam(); gm!!.setLastRunGhost(currentGhost!!); runner!!.setGhost(currentGhost!!) } }.execute() }
    private fun handleIncomingIntent(incoming: Intent?) { if (!IncomingNarIntent.isApprovedDownload(incoming)) { if (incoming != null && Intent.ACTION_VIEW == incoming.action) { Log.w(TAG, "Rejected unapproved external install URI"); Toast.makeText(this, R.string.err_https_nar_only, Toast.LENGTH_LONG).show() }; return }; Log.d(TAG, "Accepted HTTPS NAR download URI"); addNarToDownload(incoming!!.data!!) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIncomingIntent(intent) }
    fun onUpdate(v: View) { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "Update", "", 0); val home = runner!!.getStringValueFromShiori("homeurl") ?: return; runner!!.doShioriEvent("OnUpdateBegin", arrayOf(currentGhost!!.getGhostName(), currentGhost!!.getGhostPath())); startModernService(NanidroidService.createUpdateIntent(this, home, currentGhost!!.getGhostId(), currentGhost!!.getGhostPath())) }
    fun onListGhost(v: View) { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "list_ghost", "", 0); showGhostListDlg() }
    fun onHelp(v: View) { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "help", "", 0); AnalyticsUtils.getInstance(this).trackPageView("/Help_menu"); openContextMenu(v) }
    fun getMoreGhost(source: Int) { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_BTN, "MoreGhost", if (source == 0) "MainUI" else Setup.DLG_G_LIST, source); AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_MORE_G}"); MoreGhostFuncDlg().show(supportFragmentManager, Setup.DLG_MORE_G) }
    override fun startInstallFromSDCard() { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_UI_TOUCH, "more_ghost_install_sd", "install_from_sd", 0); Toast.makeText(this, R.string.err_legacy_local_install_disabled, Toast.LENGTH_LONG).show() }
    fun showNarErrDlg(dir: Boolean) { AnalyticsUtils.getInstance(applicationContext).trackEvent(Setup.ANA_ERR, "more_ghost_install_sd", if (dir) "no_nar_folder" else "no_nar_file", if (dir) -1 else -2); ErrMsgDlg.newInstance(R.string.err_nar_title, if (dir) R.string.err_no_nar_folder else R.string.err_no_nar_file).show(supportFragmentManager, Setup.DLG_ERR) }
    fun showNarPickDlg(narz: Array<String>) { Toast.makeText(this, "multiple nar exist", Toast.LENGTH_SHORT).show(); AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_NAR_PICK}"); NarPickDlg(narz).show(supportFragmentManager, Setup.DLG_NAR_PICK) }
    override fun onNarPick(narName: String) { Toast.makeText(this, R.string.err_legacy_local_install_disabled, Toast.LENGTH_LONG).show() }
    override fun showUrlDlg() { AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_E_URL}"); EnterUrlDlg().show(supportFragmentManager, Setup.DLG_E_URL) }
    override fun onFinishURL(url: String) = addNarToDownload(Uri.parse(url))
    override fun showGhostTown() { AnalyticsUtils.getInstance(this).trackPageView("/ghost_town_portal"); NotImplementedDlg().show(supportFragmentManager, Setup.DLG_NOT_IMPL) }
    fun onMoreGhost(v: View) = getMoreGhost(0)
    private fun showGhostListDlg() { val names = gm!!.getGnames()!!; gAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names); AnalyticsUtils.getInstance(this).trackPageView("/${Setup.DLG_G_LIST}"); GhostListDialogFragment.newInstance(names, gm).show(supportFragmentManager, Setup.DLG_G_LIST) }
    override fun onContextItemSelected(item: MenuItem): Boolean = when (item.itemId) { R.id.item_about -> { showAbout(); true }; R.id.item_feedback -> { showFeedback(); true }; R.id.item_general_help -> { showHelp(); true }; else -> super.onContextItemSelected(item) }
    private fun showHelp() { AnalyticsUtils.getInstance(applicationContext).trackPageView("/help"); HelpFuncDlg().show(supportFragmentManager, Setup.DLG_GEN_HELP) }
    private fun showFeedback() { AnalyticsUtils.getInstance(applicationContext).trackPageView("/feedback"); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.feedback_url)))) }
    private fun showAbout() { AnalyticsUtils.getInstance(applicationContext).trackPageView("/about"); AboutDialogFragment().show(supportFragmentManager, Setup.DLG_ABOUT) }
    override fun onCreateContextMenu(menu: ContextMenu, v: View, menuInfo: ContextMenu.ContextMenuInfo?) { super.onCreateContextMenu(menu, v, menuInfo); menuInflater.inflate(R.menu.main_help_menu, menu) }
    fun onSetupClick(v: View) = showPreference()
    private fun showPreference() { val target = Intent(Intent.ACTION_VIEW); target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET); target.setClassName(this, Preferences::class.java.name); AnalyticsUtils.getInstance(this).trackPageView("/Preference"); startActivity(target) }
    fun frameClick(v: View) {
        if (!toolbarVisible) AnalyticsUtils.getInstance(this).trackPageView("/main_btn_bar")
        toolbarVisible = !toolbarVisible
    }
    private val ecb = ErrMsgDlg.ErrDlgCallback { flag -> if (FLAG_SD_ERR == flag) finish() }
    override fun onFinishUserInput(id: String, userinput: String) { Log.d(TAG, "got user input:$userinput"); runner!!.resumeEvt(); runner!!.doUserInput(id, userinput) }
    override fun onCancelInput() { Log.d(TAG, "user cancel"); runner!!.resumeEvt() }
    override fun showUserInputBox(id: String) { UserInputDlg(id).show(supportFragmentManager, Setup.DLG_USR_INPUT) }
    override fun onChoiceSelect(id: String) { runner!!.doOnChoiceSelect(id) }
    override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) { UserSelectDlg.newInstance(textlabel, ids).show(supportFragmentManager, Setup.DLG_USR_SEL) }

    companion object { private const val TAG = "Nanidroid"; private const val PREF_KEY_LAUNCH_TIME = "keylaunchtime"; private const val MIN_TAG = "minimized"; private const val FLAG_SD_ERR = 42; private const val MSG_START = 2019; private const val MSG_LOAD_F = 2020; private const val MSG_LOAD_N = 2021; @JvmField var gAdapter: ArrayAdapter<String>? = null }
}
