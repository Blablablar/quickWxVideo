package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.awll.nfcmiaoshi.PinyinUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.coroutineContext

/**
 * 微信自动视频通话无障碍服务
 *
 * 使用自定义扩展函数实现节点查找和操作，零外部依赖。
 *
 * 流程：跳转微信 → 点击底部Tab「通讯录」→ 查找联系人 → 点击联系人 → 点击「音视频通话」→ 点击「视频通话」
 */
class SelectToSpeakService : AccessibilityService() {

    companion object {
        private const val TAG = "SelectToSpeakService"
        private const val WECHAT_PKG = "com.tencent.mm"
        private const val PREFS_NAME = "nfc_prefs"
        private const val KEY_TARGET_NAME = "target_name"
        private const val KEY_SHOULD_START = "should_start"
        private const val MAX_RETRY = 20

        var instance: SelectToSpeakService? = null
            private set

        /** 服务是否正在运行 */
        val isRunningFlow: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private var targetName = ""
    private var callJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ==================== 生命周期 ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        (isRunningFlow as MutableStateFlow).value = true
        toast("微信视频通话服务已启动")
        Log.i(TAG, "onServiceConnected")
        addAliveOverlayView()

        // 对齐 GKD: A11yService.onCreated { StatusService.autoStart() }
        KeepAliveService.autoStart(this)

        // 恢复未完成的任务
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SHOULD_START, false)) {
            targetName = prefs.getString(KEY_TARGET_NAME, "") ?: ""
            if (targetName.isNotEmpty()) {
                prefs.edit().putBoolean(KEY_SHOULD_START, false).apply()
                startCall(targetName)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要监听事件，由协程主动轮询
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt")
    }

    override fun onDestroy() {
        instance = null
        (isRunningFlow as MutableStateFlow).value = false
        callJob?.cancel()
        serviceScope.cancel()
        removeAliveOverlayView()
        super.onDestroy()
    }

    // ==================== 外部接口 ====================

    fun startCall(name: String) {
        if (name.isEmpty()) return
        targetName = name

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TARGET_NAME, name)
            .putBoolean(KEY_SHOULD_START, true)
            .apply()

        Log.i(TAG, "startCall: targetName=$targetName")

        callJob?.cancel()
        callJob = serviceScope.launch {
            try {
                // 启动微信
                launchApp(WECHAT_PKG)
                delay(1500)

                // 步骤0：确保回到微信首页
                stepBackToHomePage()

                // 步骤1：点击通讯录Tab
                stepClickContactsTab()

                // 步骤2：查找并点击联系人
                stepFindAndClickContact()

                // 步骤3：点击「音视频通话」
                stepClickVideoCallBtn()

                // 步骤4：点击「视频通话」
                // stepClickVideoChat()

                // toast("正在发起视频通话…")
            } catch (e: CancellationException) {
                Log.d(TAG, "startCall: 任务已取消")
            } catch (e: Exception) {
                Log.e(TAG, "startCall: 执行失败", e)
                toast("操作失败：${e.message}")
            } finally {
                reset()
            }
        }
    }

    // ==================== 步骤实现 ====================

    /**
     * 步骤0：确保回到微信首页
     * 微信可能在聊天页、小程序、设置等子页面，循环按返回直到底部Tab栏出现
     */
    private suspend fun stepBackToHomePage() {
        var retry = 0
        while (retry < 10) {
            currentCoroutineContext().ensureActive()

            if (isOnMainPage()) {
                Log.d(TAG, "stepBackToHomePage: 已在微信首页")
                return
            }

            performGlobalAction(GLOBAL_ACTION_BACK)
            Log.d(TAG, "stepBackToHomePage: 按返回键，重试 $retry/10")
            delay(800)
            retry++
        }
        // 返回键仍无法回到首页，尝试重新启动微信
        Log.w(TAG, "stepBackToHomePage: 返回键未回到首页，重新启动微信")
        launchApp(WECHAT_PKG)
        delay(1500)
    }

    /**
     * 步骤1：查找并点击「通讯录」Tab
     */
    private suspend fun stepClickContactsTab() {
        var retry = 0
        while (retry < MAX_RETRY) {
            currentCoroutineContext().ensureActive()

            // 先验证是否已在通讯录页面
            if (isInContactsPage()) {
                Log.d(TAG, "stepClickContactsTab: 已在通讯录页面")
                dumpAllNodes()
                return
            }

            // 查找「通讯录」节点
            val nodes = findByText("通讯录")
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                Log.d(TAG, "stepClickContactsTab: 找到通讯录 cls=${node.className} text=${node.text} desc=${node.contentDescription} click=${node.isClickable}")

                val clicked = node.click()
                if (clicked) {
                    delay(800)
                    if (isInContactsPage()) {
                        Log.d(TAG, "stepClickContactsTab: 点击成功，已进入通讯录")
                        // 打印通讯录页面所有节点，用于调试
                        dumpAllNodes()
                        return
                    }
                }
                // click() 返回 false 时用手势兜底
                val rect = node.getBoundsInScreen()
                if (!rect.isEmpty) {
                    val x = (rect.left + rect.right) / 2f
                    val y = (rect.top + rect.bottom) / 2f
                    gestureClick(x, y)
                    delay(800)
                    if (isInContactsPage()) {
                        Log.d(TAG, "stepClickContactsTab: 手势点击成功")
                        dumpAllNodes()
                        return
                    }
                }
            }

            retry++
            Log.d(TAG, "stepClickContactsTab: 未找到通讯录节点，重试 $retry/$MAX_RETRY")
            delay(500)
        }
        Log.e(TAG, "stepClickContactsTab: 点击通讯录超时")
        return
    }

    /**
     * 步骤2：在通讯录列表中查找并点击联系人
     * 优先通过右侧字母索引跳转，再查找联系人
     */
    private suspend fun stepFindAndClickContact() {
        // 先通过右侧字母索引跳转到对应首字母区域
        val firstLetter = PinyinUtils.getPinyinInitial(targetName)
        if (firstLetter.isNotEmpty()) {
            stepClickLetterIndex(firstLetter)
        }

        // 在当前可见区域查找联系人，找不到则继续滚动
        var retry = 0
        while (retry < MAX_RETRY) {
            currentCoroutineContext().ensureActive()

            // 打印当前可见联系人节点，辅助调试
            Log.d(TAG, "stepFindAndClickContact: 第${retry}次查找 targetName=$targetName")
            dumpVisibleContactNodes()

            val nodes = findByText(targetName).filter {
                it.text?.toString() == targetName || it.contentDescription?.toString() == targetName
            }
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                Log.d(TAG, "stepFindAndClickContact: 找到 $targetName cls=${node.className} bounds=${node.getBoundsInScreen()}")

                val clicked = node.click()
                if (clicked) {
                    delay(800)
                    if (findByText("音视频通话").isNotEmpty()) {
                        Log.d(TAG, "stepFindAndClickContact: 已进入联系人详情页")
                        return
                    }
                }
                // 手势点击兜底
                val rect = node.getBoundsInScreen()
                if (!rect.isEmpty) {
                    gestureClick(
                        (rect.left + rect.right) / 2f,
                        (rect.top + rect.bottom) / 2f
                    )
                    delay(800)
                    if (findByText("音视频通话").isNotEmpty()) {
                        Log.d(TAG, "stepFindAndClickContact: 手势点击成功进入详情页")
                        return
                    }
                }
            }

            // 继续滚动查找
            // 优先通过微信通讯录列表的 viewId 查找可滚动容器
            val contactList = findNodeByViewId("com.tencent.mm:id/mg")
            if (contactList != null) {
                val scrolled = contactList.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Log.d(TAG, "stepFindAndClickContact: 通过viewId滚动列表 scrolled=$scrolled retry=$retry")
            } else {
                val scrollable = findScrollableNode()
                if (scrollable != null) {
                    scrollable.scrollForward()
                    Log.d(TAG, "stepFindAndClickContact: 滚动列表 retry=$retry")
                } else {
                    gestureScrollUp()
                    Log.d(TAG, "stepFindAndClickContact: 手势滑动兜底 retry=$retry")
                }
            }

            delay(500)
            retry++
        }
        Log.e(TAG, "stepFindAndClickContact: 查找联系人 $targetName 超时")
        return
    }

    /**
     * 打印当前可见的联系人节点信息
     */
    private fun dumpVisibleContactNodes() {
        val root = rootInActiveWindow ?: return
        val allNodes = root.bfsCollectPublic()
        var contactCount = 0
        for (node in allNodes) {
            val bounds = node.getBoundsInScreen()
            // 只打印中间区域（排除顶部标题栏和底部tab栏）且有文本的节点
            if (bounds.top > resources.displayMetrics.heightPixels * 0.1
                && bounds.bottom < resources.displayMetrics.heightPixels * 0.9
                && !node.txt().isEmpty()
            ) {
                Log.d(TAG, "dumpVisibleContactNodes: text='${node.text}' desc='${node.contentDescription}' viewId=${node.viewIdResourceName} bounds=$bounds")
                contactCount++
            }
        }
        Log.d(TAG, "dumpVisibleContactNodes: 共 $contactCount 个可见文本节点")
    }

    /**
     * 点击通讯录右侧字母索引中的指定字母
     * 微信字母索引是自定义 View，字母直接绘制在 Canvas 上，
     * 不是独立的 AccessibilityNodeInfo，因此无法通过 text/contentDescription 查找。
     * 通过 viewIdResourceName 精确定位索引条容器，再用手势点击对应字母位置。
     *
     * 微信字母索引布局：箭头 收藏 A-Z # 共 29 项
     */
    private suspend fun stepClickLetterIndex(letter: String) {
        Log.d(TAG, "stepClickLetterIndex: 尝试点击字母 '$letter'")
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val root = rootInActiveWindow

        // 微信字母索引条：箭头、收藏、A-Z、# 共 29 项
        val indexItems = listOf("↑", "★") + ('A'..'Z').map { it.toString() } + listOf("#")
        val totalItems = indexItems.size // 29
        val targetIndex = indexItems.indexOf(letter)
        if (targetIndex < 0) {
            Log.w(TAG, "stepClickLetterIndex: 未知字母 '$letter'")
            return
        }

        // 默认估算位置
        var sidebarLeft = screenWidth * 0.88f
        var sidebarRight = screenWidth.toFloat()
        var sidebarTop = screenHeight * 0.1f
        var sidebarBottom = screenHeight * 0.88f

        // 通过 viewId 精确查找微信字母索引条容器
        val SIDEBAR_VIEW_ID = "com.tencent.mm:id/mx"
        if (root != null) {
            val allNodes = root.bfsCollectPublic()
            for (node in allNodes) {
                if (node.viewIdResourceName == SIDEBAR_VIEW_ID) {
                    val bounds = node.getBoundsInScreen()
                    sidebarLeft = bounds.left.toFloat()
                    sidebarRight = bounds.right.toFloat()
                    sidebarTop = bounds.top.toFloat()
                    sidebarBottom = bounds.bottom.toFloat()
                    Log.d(TAG, "stepClickLetterIndex: 通过viewId找到索引条 bounds=$bounds")
                    break
                }
            }
            if (sidebarLeft == screenWidth * 0.88f) {
                Log.w(TAG, "stepClickLetterIndex: 未找到viewId=$SIDEBAR_VIEW_ID 的节点，使用估算位置")
            }
        }

        // 计算目标字母在索引条中的位置（均匀分布）
        val clickX = (sidebarLeft + sidebarRight) / 2f
        val itemHeight = (sidebarBottom - sidebarTop) / totalItems
        val clickY = sidebarTop + itemHeight * targetIndex + itemHeight / 2f

        Log.d(TAG, "stepClickLetterIndex: 点击字母 '$letter' index=$targetIndex/$totalItems x=$clickX y=$clickY itemHeight=$itemHeight")
        gestureClick(clickX, clickY)
        delay(800)
    }

    /**
     * 通过 viewIdResourceName 查找节点
     */
    private fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.bfsCollectPublic().firstOrNull { it.viewIdResourceName == viewId }
    }

    /**
     * 打印当前页面所有无障碍节点信息，用于调试
     */
    private fun dumpAllNodes() {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "dumpAllNodes: rootInActiveWindow is null")
            return
        }
        val allNodes = root.bfsCollectPublic()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        Log.d(TAG, "dumpAllNodes: ===== 共 ${allNodes.size} 个节点 (屏幕 ${screenWidth}x${screenHeight}) =====")
        for ((index, node) in allNodes.withIndex()) {
            val bounds = node.getBoundsInScreen()
            val inRightArea = bounds.left > screenWidth * 0.8
            val isNarrowTall = bounds.width() < screenWidth * 0.15 && bounds.height() > screenHeight * 0.5
            val marker = when {
                isNarrowTall && inRightArea -> " [可能是字母索引条]"
                inRightArea -> " [右侧区域]"
                else -> ""
            }
            Log.d(TAG, "dumpAllNodes: [$index] cls=${node.className} viewId=${node.viewIdResourceName} windowId=${node.windowId}" +
                    " text='${node.text}' desc='${node.contentDescription}'" +
                    " bounds=$bounds clickable=${node.isClickable} scrollable=${node.isScrollable} childCount=${node.childCount}$marker")
        }
        Log.d(TAG, "dumpAllNodes: ===== END =====")
    }

    /**
     * 步骤3：点击「音视频通话」
     */
    private suspend fun stepClickVideoCallBtn() {
        var retry = 0
        while (retry < MAX_RETRY) {
            currentCoroutineContext().ensureActive()

            val nodes = findByText("音视频通话")
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                val clicked = node.click()
                Log.d(TAG, "stepClickVideoCallBtn: 点击音视频通话 result=$clicked")
                if (clicked) {
                    delay(800)
                    return
                }
                // 手势兜底
                val rect = node.getBoundsInScreen()
                if (!rect.isEmpty) {
                    gestureClick(
                        (rect.left + rect.right) / 2f,
                        (rect.top + rect.bottom) / 2f
                    )
                    delay(800)
                    return
                }
            }

            retry++
            Log.d(TAG, "stepClickVideoCallBtn: 未找到，重试 $retry/$MAX_RETRY")
            delay(500)
        }
        Log.e(TAG, "stepClickVideoCallBtn: 点击音视频通话超时")
        return
    }

    /**
     * 步骤4：点击「视频通话」
     */
    private suspend fun stepClickVideoChat() {
        var retry = 0
        while (retry < MAX_RETRY) {
            currentCoroutineContext().ensureActive()

            val nodes = findByText("视频通话")
            if (nodes.isNotEmpty()) {
                val node = nodes.first()
                val clicked = node.click()
                Log.d(TAG, "stepClickVideoChat: 点击视频通话 result=$clicked")
                if (clicked) {
                    return
                }
                // 手势兜底
                val rect = node.getBoundsInScreen()
                if (!rect.isEmpty) {
                    gestureClick(
                        (rect.left + rect.right) / 2f,
                        (rect.top + rect.bottom) / 2f
                    )
                    return
                }
            }

            retry++
            Log.d(TAG, "stepClickVideoChat: 未找到，重试 $retry/$MAX_RETRY")
            delay(500)
        }
        Log.e(TAG, "stepClickVideoChat: 点击视频通话超时")
        return
    }

    // ==================== 页面判断 ====================

    /**
     * 判断当前是否在微信首页（底部有微信/通讯录/发现/我 Tab栏）
     */
    private fun isOnMainPage(): Boolean {
        val tabs = listOf("微信", "通讯录", "发现", "我")
        var found = 0
        for (tab in tabs) {
            if (findByText(tab).isNotEmpty()) {
                found++
            }
        }
        // 至少找到3个Tab才认为是首页
        return found >= 3
    }

    /**
     * 判断当前是否在通讯录页面
     * 关键特征：页面顶部有"通讯录"标题文字（与底部Tab的"通讯录"区分）
     */
    private fun isInContactsPage(): Boolean {
        val nodes = findByText("通讯录")
        for (node in nodes) {
            val bounds = node.getBoundsInScreen()
            // 页面标题在屏幕上半部分（y < 屏幕高度1/3），底部Tab在下半部分
            val screenHeight = resources.displayMetrics.heightPixels
            if (bounds.top < screenHeight / 3) {
                Log.d(TAG, "isInContactsPage: 找到顶部'通讯录'标题 bounds=$bounds")
                return true
            }
        }
        return false
    }

    // ==================== 辅助方法 ====================

    private fun reset() {
        callJob = null
        targetName = ""
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TARGET_NAME)
            .remove(KEY_SHOULD_START)
            .apply()
    }

    private fun toast(msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@SelectToSpeakService, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "toast failed", e)
        }
    }

    // ==================== Overlay 窗口保活 ====================

    /**
     * 添加一个 1x1 的 TYPE_ACCESSIBILITY_OVERLAY 透明窗口
     * 系统会认为服务正在使用窗口，降低被回收的概率
     * 参考 GKD 项目的保活逻辑
     */
    private var aliveView: View? = null
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private fun addAliveOverlayView() {
        removeAliveOverlayView()
        val tempView = View(this)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            packageName = this@SelectToSpeakService.packageName
        }
        try {
            wm.addView(tempView, lp)
            aliveView = tempView
            Log.i(TAG, "addAliveOverlayView: 保活窗口添加成功")
        } catch (e: Throwable) {
            aliveView = null
            Log.e(TAG, "addAliveOverlayView: 保活窗口添加失败", e)
            toast("添加无障碍保活失败，请尝试重启无障碍")
        }
    }

    private fun removeAliveOverlayView() {
        if (aliveView != null) {
            try {
                wm.removeView(aliveView)
            } catch (e: Throwable) {
                Log.e(TAG, "removeAliveOverlayView: 移除保活窗口失败", e)
            }
            aliveView = null
        }
    }
}
