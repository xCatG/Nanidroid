package com.cattailsw.nanidroid.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cattailsw.nanidroid.Ghost
import com.cattailsw.nanidroid.GhostMgr
import com.cattailsw.nanidroid.OverlayMascotService
import com.cattailsw.nanidroid.SScriptRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainScreenViewModel : ViewModel() {
    private val _activeGhost = MutableStateFlow<Ghost?>(null)
    val activeGhost: StateFlow<Ghost?> = _activeGhost.asStateFlow()

    private val _installedGhosts = MutableStateFlow<List<GhostInfo>>(emptyList())
    val installedGhosts: StateFlow<List<GhostInfo>> = _installedGhosts.asStateFlow()

    private val _isOverlayEnabled = MutableStateFlow(false)
    val isOverlayEnabled: StateFlow<Boolean> = _isOverlayEnabled.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs: StateFlow<List<String>> = _consoleLogs.asStateFlow()

    // State for interactive UI dialogs prompted by Shiori/Sakura Script
    var currentInputBoxId by mutableStateOf<String?>(null)
        private set
    var currentSelection by mutableStateOf<SelectionData?>(null)
        private set

    data class SelectionData(val labels: Array<String>, val ids: Array<String>)

    fun init(context: Context) {
        val gm = GhostMgr(context)
        val lastGhostId = gm.getLastRunGhostId() ?: "nanidroid"
        val ghost = gm.createGhost(lastGhostId)
        _activeGhost.value = ghost
        refreshInstalledGhosts(context)
        updateOverlayState(context)
        
        val runner = SScriptRunner.getInstance(context)
        runner.setUICallback(object : SScriptRunner.UICallback {
            override fun showUserInputBox(id: String) {
                logConsole("Prompt: User Input requested for '$id'")
                currentInputBoxId = id
            }

            override fun showUserSelection(textlabel: Array<String>, ids: Array<String>) {
                logConsole("Prompt: Choice selection requested")
                currentSelection = SelectionData(textlabel, ids)
            }
        })
    }

    fun onInputSubmit(context: Context, id: String, text: String) {
        currentInputBoxId = null
        val runner = SScriptRunner.getInstance(context)
        runner.doUserInput(id, text)
    }

    fun onInputCancel(context: Context) {
        currentInputBoxId = null
        val runner = SScriptRunner.getInstance(context)
        runner.resumeEvt()
    }

    fun onSelectionSelect(context: Context, id: String) {
        currentSelection = null
        val runner = SScriptRunner.getInstance(context)
        runner.doOnChoiceSelect(id)
    }

    fun onSelectionCancel(context: Context) {
        currentSelection = null
        val runner = SScriptRunner.getInstance(context)
        runner.resumeEvt()
    }

    fun refreshInstalledGhosts(context: Context) {
        viewModelScope.launch {
            val gm = GhostMgr(context)
            gm.refreshGhost()
            val ids = gm.getGnames() ?: emptyArray()
            val dispNames = gm.getGDispNames() ?: emptyArray()
            val list = ids.mapIndexed { index, id ->
                GhostInfo(
                    id = id,
                    displayName = dispNames.getOrNull(index) ?: id,
                    path = gm.getGhostPath(id)
                )
            }
            _installedGhosts.value = list
        }
    }

    fun switchGhost(context: Context, id: String) {
        viewModelScope.launch {
            logConsole("Switching ghost to $id...")
            val gm = GhostMgr(context)
            val runner = SScriptRunner.getInstance(context)
            
            val g = gm.createGhost(id)
            if (g != null) {
                gm.setLastRunGhost(g)
                _activeGhost.value = g
                runner.setGhost(g)
                runner.clearMsgQueue()
                runner.run()
                logConsole("Successfully loaded ghost: ${g.getGhostId()}")
                
                if (_isOverlayEnabled.value) {
                    toggleOverlay(context, false)
                    toggleOverlay(context, true)
                }
            } else {
                logConsole("Error loading ghost: $id")
            }
        }
    }

    fun toggleOverlay(context: Context, enabled: Boolean) {
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                logConsole("Permission missing: System Alert Window overlay")
                return
            }
            val intent = Intent(context, OverlayMascotService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            _isOverlayEnabled.value = true
            logConsole("Overlay Mode enabled")
        } else {
            val intent = Intent(context, OverlayMascotService::class.java)
            context.stopService(intent)
            _isOverlayEnabled.value = false
            logConsole("Overlay Mode disabled")
        }
    }

    fun updateOverlayState(context: Context) {
        // Overlay service status check
    }

    fun logConsole(msg: String) {
        val current = _consoleLogs.value.toMutableList()
        current.add(msg)
        if (current.size > 20) {
            current.removeAt(0)
        }
        _consoleLogs.value = current
    }
}

data class GhostInfo(
    val id: String,
    val displayName: String,
    val path: String
)
