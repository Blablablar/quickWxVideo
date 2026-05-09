package com.awll.nfcmiaoshi

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.google.android.accessibility.selecttospeak.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Application 类
 *
 * 参考 GKD 项目的 App.kt，实现：
 * 1. 崩溃自恢复 — UncaughtExceptionHandler 崩溃后重启应用
 * 2. 启动时自动拉起保活服务
 */
class App : Application() {

    companion object {
        private const val TAG = "App"
        private var isActivityVisible = false

        fun onActivityResumed() {
            isActivityVisible = true
        }

        fun onActivityPaused() {
            isActivityVisible = false
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 设置崩溃自恢复
        setupCrashHandler()

        // 对齐 GKD: App.onCreate → syncFixState → autoStart
        appScope.launch {
            delay(500)
            KeepAliveService.autoStart(this@App)
        }
    }

    /**
     * 参考 GKD 的 UncaughtExceptionHandler
     * 崩溃后自动重启 Activity
     */
    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UncaughtException in thread: ${thread.name}", throwable)

            appScope.launch {
                try {
                    delay(1500)
                    if (isActivityVisible) {
                        startLaunchActivity()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "restart failed", e)
                } finally {
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            }
        }
    }

    private fun startLaunchActivity() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) {
            startActivity(intent)
        }
    }
}
