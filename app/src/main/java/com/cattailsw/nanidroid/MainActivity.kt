package com.cattailsw.nanidroid

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.cattailsw.nanidroid.theme.NanidroidTheme
import com.cattailsw.nanidroid.util.NarUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    lateinit var gm: GhostMgr
    private lateinit var runner: SScriptRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        gm = GhostMgr(this)
        runner = SScriptRunner.getInstance(this)

        // Initialize default ghost if none are present
        if (gm.getGhostCount() == 0) {
            installDefaultGhost()
        }

        enableEdgeToEdge()
        setContent {
            NanidroidTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }

        intent?.let { handleIntent(it) }
    }

    private fun installDefaultGhost() {
        val a = assets
        try {
            val isStream = a.open("nanidroid.zip")
            val extDir = externalCacheDir
            val targetPath = File(extDir, "nanidroid.nar")
            NarUtil.copyFile(isStream, FileOutputStream(targetPath))
            gm.installFirstGhost("nanidroid", targetPath.path)
            gm.refreshGhost()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (intent.hasExtra("DL_PKG")) {
            intent.data?.path?.let { extractNar(it) }
        }
        if (Intent.ACTION_VIEW == action) {
            intent.data?.let { uri ->
                if (uri.scheme == "file") {
                    uri.path?.let { extractNar(it) }
                } else {
                    addNarToDownload(uri)
                }
            }
        }
    }

    private fun addNarToDownload(uri: Uri) {
        val i = Intent(this, NanidroidService::class.java).apply {
            action = Intent.ACTION_RUN
            data = uri
        }
        startService(i)
    }

    fun extractNar(path: String) {
        val ghostId = NarUtil.readNarGhostId(path)
        if (ghostId == null) {
            runner.doShioriEvent("OnInstallFailure", null)
            return
        }
        if (!gm.hasSameGhostId(ghostId)) {
            runner.doInstallBegin(ghostId)
            CoroutineScope(Dispatchers.Main).launch {
                val gPath = withContext(Dispatchers.IO) {
                    gm.installGhost(ghostId, path)
                }
                if (gPath != null) {
                    runner.doInstallComplete(ghostId)
                    gm.refreshGhost()
                    Toast.makeText(this@MainActivity, "Ghost $ghostId installed!", Toast.LENGTH_SHORT).show()
                } else {
                    runner.doShioriEvent("OnInstallFailure", null)
                }
            }
        } else {
            runner.doShioriEvent("OnInstallRefuse", null)
        }
    }

    override fun onResume() {
        super.onResume()
        runner.startClock()
        runner.run()
    }

    override fun onPause() {
        super.onPause()
        runner.stopClock()
        sendStopIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        sendStopIntent()
    }

    private fun sendStopIntent() {
        val i = Intent(this, NanidroidService::class.java).apply {
            action = NanidroidService.ACTION_CAN_STOP
        }
        startService(i)
    }
}
