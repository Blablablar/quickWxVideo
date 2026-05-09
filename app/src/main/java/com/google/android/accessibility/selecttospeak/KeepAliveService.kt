package com.google.android.accessibility.selecttospeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 前台通知保活服务
 *
 * 对齐 GKD 项目 StatusService 的保活架构：
 * 1. isRunning 使用 MutableStateFlow（与 GKD 一致）
 * 2. ServiceCompat.startForeground + FOREGROUND_SERVICE_TYPE_MANIFEST（与 GKD 一致）
 * 3. needRestart 条件检测 + autoStart 1秒防抖（与 GKD 一致）
 * 4. 屏幕点亮触发 autoStart（与 GKD 的 A11yFeat 触发方式一致）
 * 5. START_STICKY + 定时检测
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "keep_alive_channel"
        private const val NOTIFICATION_ID = 1

        /** 检测间隔：30秒 */
        private const val CHECK_INTERVAL_MS = 30_000L

        /** isRunning 状态流，与 GKD StatusService.isRunning 一致 */
        val isRunning = MutableStateFlow(false)

        /**
         * needRestart 条件：无障碍已开启但实例为空
         * 对齐 GKD: enableStatusService && !isRunning && notificationState && foregroundServiceSpecialUseState
         * quickWxVideo 简化为：a11y已开启 && 服务未运行
         */
        fun needRestart(context: Context): Boolean {
            return com.example.nfcdemo.ActionHelper.isAccessibilityServiceEnabled(context) && !isRunning.value
        }

        /**
         * 自动启动保活服务，带1秒防抖
         * 对齐 GKD StatusService.autoStart()
         * 需要已有服务或前台才能自主启动，否则报错 startForegroundService() not allowed due to mAllowStartForeground false
         */
        private var lastAutoStart = 0L
        fun autoStart(context: Context) {
            if (System.currentTimeMillis() - lastAutoStart < 1000) return
            if (needRestart(context)) {
                start(context)
                lastAutoStart = System.currentTimeMillis()
            }
        }

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Throwable) {
                Log.e(TAG, "start: 启动保活服务失败", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var checkJob: Job? = null

    /** 屏幕状态广播接收器 */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.i(TAG, "屏幕点亮，触发 autoStart")
                    // 对齐 GKD: 屏幕点亮时触发 autoStart 而非 tryFixService
                    autoStart(context)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.value = true
        createNotificationChannel()
        notifyService()
        Log.i(TAG, "onCreate: 保活服务已启动")

        // 注册屏幕状态监听
        registerScreenReceiver()

        // 启动持续检测循环
        startCheckLoop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 当用户从最近任务列表滑掉应用时触发
     * 必须重启服务，否则进程会被杀掉
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved: 任务被移除，重启服务")
        // 重新启动保活服务
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateNotification()

        // 每次 onStartCommand 也触发 autoStart
        autoStart(this)

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning.value = false
        checkJob?.cancel()
        serviceScope.cancel()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
        Log.i(TAG, "onDestroy: 保活服务已关闭")
    }

    // ==================== 持续检测循环 ====================

    /**
     * 定时检查并 autoStart
     * 对齐 GKD: 多个触发点调用 autoStart，此处作为兜底轮询
     */
    private fun startCheckLoop() {
        checkJob?.cancel()
        checkJob = serviceScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                autoStart(this@KeepAliveService)
                // 更新通知状态
                withContext(Dispatchers.Main) {
                    updateNotification()
                }
            }
        }
    }

    // ==================== 屏幕状态监听 ====================

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
    }

    // ==================== 通知（对齐 GKD Notif） ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "微信视频通话",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持无障碍服务运行"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 对齐 GKD Notif.notifyService()
     * 使用 ServiceCompat.startForeground + FOREGROUND_SERVICE_TYPE_MANIFEST
     */
    private fun notifyService() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                } else {
                    -1
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "notifyService: startForeground 失败", e)
        }
    }

    private fun buildNotification(): Notification {
        val a11yRunning = SelectToSpeakService.instance != null
        val text = if (a11yRunning) {
            "无障碍服务运行中"
        } else if (com.example.nfcdemo.ActionHelper.isAccessibilityServiceEnabled(this)) {
            "无障碍服务异常，正在恢复…"
        } else {
            "无障碍服务未开启"
        }

        // 点击通知打开 MainActivity
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_invisible)
            .setContentTitle("微信视频通话")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed", e)
        }
    }
}
