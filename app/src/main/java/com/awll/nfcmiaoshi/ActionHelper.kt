package com.awll.nfcmiaoshi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.google.android.accessibility.selecttospeak.SelectToSpeakService

/**
 * 公共操作工具类
 *
 * 提取 MainActivity 和 TestActivity 中重复的逻辑：
 * - 微信通话发起
 * - 拨打电话
 * - 无障碍服务状态检查
 */

object ActionHelper {

    private const val PREFS_NAME = "nfc_prefs"
    private const val KEY_TARGET_NAME = "target_name"
    private const val KEY_SHOULD_START = "should_start"

    // ==================== 无障碍服务检查 ====================

    /**
     * 检查本应用的无障碍服务是否已在系统中开启
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val list = am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return list.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    // ==================== 微信通话 ====================

    /**
     * 发起微信视频通话
     * 优先通过无障碍服务实例调用，否则通过 SharedPreferences 传递参数并启动微信
     */
    fun startWeChatCall(context: Context, name: String) {
        Toast.makeText(context, R.string.wechat_call_started, Toast.LENGTH_SHORT).show()
        val service = SelectToSpeakService.instance
        if (service != null) {
            service.startCall(name)
        } else {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TARGET_NAME, name)
                .putBoolean(KEY_SHOULD_START, true)
                .apply()
            val wechatIntent = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (wechatIntent != null) {
                wechatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(wechatIntent)
            } else {
                Toast.makeText(context, "未安装微信", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 拨打电话 ====================

    /**
     * 直接拨打电话（需要 CALL_PHONE 权限）
     */
    fun makePhoneCall(context: Context, phone: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        context.startActivity(intent)
    }

    /**
     * 打开拨号页面（不需要权限）
     */
    fun openDialPage(context: Context, phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        context.startActivity(intent)
    }

    /**
     * 请求拨号权限或直接拨打
     * @return true 已发起权限请求，需要 caller 在 onRequestPermissionsResult 中处理
     */
    fun requestCallOrDial(
        context: Context,
        phone: String,
        requestCode: Int = 100
    ): Boolean {
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            makePhoneCall(context, phone)
            return false
        } else {
            if (context is androidx.appcompat.app.AppCompatActivity) {
                context.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), requestCode)
            }
            return true
        }
    }
}
