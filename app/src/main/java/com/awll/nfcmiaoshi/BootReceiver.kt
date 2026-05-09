package com.awll.nfcmiaoshi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.accessibility.selecttospeak.KeepAliveService

/**
 * 开机自启动接收器
 *
 * 参考 GKD 的多重恢复触发机制
 * 设备启动完成后，自动拉起保活服务
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "开机完成，触发 autoStart")
                KeepAliveService.autoStart(context)
            }
        }
    }
}
