package com.google.android.accessibility.selecttospeak

import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 快捷磁贴服务
 *
 * 对齐 GKD 项目的 BaseTileService + GkdTileService：
 * 1. activeFlow — combine(A11yService.isRunning) 判断磁贴激活状态
 * 2. listeningFlow — 监听磁贴是否正在显示
 * 3. onStartListening — 触发 fixRestartAutomatorService（3秒防抖）
 * 4. onClick — 触发 StatusService.autoStart()
 * 5. combine(activeFlow, listeningFlow) 更新磁贴状态
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    companion object {
        private const val TAG = "QuickTileService"

        /** 对齐 GKD BaseTileService: 修复防抖时间 */
        private const val FIX_DEBOUNCE_MS = 3_000L
        private var lastFixTime = 0L
    }

    private val scope = MainScope()

    /**
     * 对齐 GKD BaseTileService.listeningFlow
     * 磁贴是否正在显示（下拉通知栏可见）
     */
    private val listeningFlow = MutableStateFlow(false)

    /**
     * 对齐 GKD GkdTileService.activeFlow
     * 无障碍服务是否正在运行
     */
    private val activeFlow: StateFlow<Boolean> = SelectToSpeakService.isRunningFlow

    override fun onCreate() {
        super.onCreate()

        // 对齐 GKD BaseTileService: combine(activeFlow, listeningFlow) 更新磁贴状态
        scope.launch {
            combine(
                activeFlow,
                listeningFlow
            ) { active, listening -> active to listening }.collect { (active, listening) ->
                if (listening) {
                    qsTile?.apply {
                        state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                        label = if (active) "视频通话运行中" else "视频通话未启动"
                        updateTile()
                    }
                }
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningFlow.value = true
        updateTileState()

        // 对齐 GKD BaseTileService: 下拉通知栏时触发 fixRestartAutomatorService（3秒防抖）
        fixRestartAutomatorService()
    }

    override fun onStopListening() {
        super.onStopListening()
        listeningFlow.value = false
    }

    override fun onClick() {
        super.onClick()

        // 对齐 GKD BaseTileService: 点击磁贴触发 StatusService.autoStart()
        KeepAliveService.autoStart(this)
        updateTileState()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 对齐 GKD BaseTileService.onStartListening 中的 fixRestartAutomatorService
     * 带3秒防抖的服务修复
     */
    private fun fixRestartAutomatorService() {
        val t = System.currentTimeMillis()
        if (t - lastFixTime < FIX_DEBOUNCE_MS) return
        lastFixTime = t

        // 对齐 GKD: 检查并修复无障碍服务
        if (KeepAliveService.needRestart(this)) {
            Log.i(TAG, "fixRestartAutomatorService: 检测到服务异常，触发 autoStart")
            KeepAliveService.autoStart(this)
        }

        // 确保保活服务在运行
        if (!KeepAliveService.isRunning.value) {
            KeepAliveService.start(this)
        }
    }

    private fun updateTileState() {
        val active = activeFlow.value
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = if (active) "视频通话运行中" else "视频通话未启动"
            updateTile()
        }
    }
}
