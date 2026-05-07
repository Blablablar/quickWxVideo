package com.google.android.accessibility.selecttospeak

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务扩展函数
 *
 * 实现 assists 库的核心 API，零外部依赖。
 */

private const val EXT_TAG = "AccessibilityExt"

// ==================== 节点查找 ====================

/**
 * 在当前活动窗口中查找包含指定文本的节点（匹配 text 或 contentDescription）
 */
fun AccessibilityService.findByText(text: String): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    val root = rootInActiveWindow ?: return result

    // 先通过系统 API 查找 text 匹配
    root.findAccessibilityNodeInfosByText(text).let { result.addAll(it) }

    // 再查找 contentDescription 匹配（系统 API 不会搜 contentDescription）
    val descMatches = root.bfsCollect { node ->
        node.contentDescription?.contains(text) == true
    }

    // 去重（用 boundsInScreen + className 作为唯一标识）
    val existingKeys = result.map { it.nodeKey() }.toMutableSet()
    descMatches.filter { it.nodeKey() !in existingKeys }.forEach {
        result.add(it)
        existingKeys.add(it.nodeKey())
    }

    Log.d(EXT_TAG, "findByText('$text'): found ${result.size} nodes")
    return result
}

/**
 * 在当前窗口查找可滚动的节点
 * 优先查找 isScrollable，兜底查找 RecyclerView/ListView/ScrollView 类名
 */
fun AccessibilityService.findScrollableNode(): AccessibilityNodeInfo? {
    val root = rootInActiveWindow ?: return null

    // 优先：isScrollable 属性
    val scrollable = root.bfsFind { it.isScrollable && it.isEnabled }
    if (scrollable != null) {
        Log.d(EXT_TAG, "findScrollableNode: found scrollable node cls=${scrollable.className}")
        return scrollable
    }

    // 兜底：按类名查找常见的可滚动容器
    val scrollClassNames = listOf(
        "androidx.recyclerview.widget.RecyclerView",
        "android.widget.ListView",
        "android.widget.ScrollView",
        "android.widget.RecyclerView",
        "android.support.v7.widget.RecyclerView",
        "androidx.core.widget.NestedScrollView",
    )
    for (cls in scrollClassNames) {
        val node = root.bfsFind { it.className?.toString() == cls }
        if (node != null) {
            Log.d(EXT_TAG, "findScrollableNode: fallback found cls=$cls")
            return node
        }
    }

    Log.w(EXT_TAG, "findScrollableNode: no scrollable node found")
    return null
}

// ==================== 节点操作 ====================

/**
 * 点击节点：如果自身可点击则直接点击，否则向上查找可点击的父节点
 */
fun AccessibilityNodeInfo.click(): Boolean {
    if (isClickable && isEnabled) {
        return performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    val clickableParent = findFirstParentClickable()
    if (clickableParent != null) {
        val result = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        clickableParent.recycle()
        return result
    }
    return false
}

/**
 * 向前滚动
 */
fun AccessibilityNodeInfo.scrollForward(): Boolean {
    return performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
}

// ==================== 节点属性扩展 ====================

/**
 * 获取节点在屏幕上的位置
 */
fun AccessibilityNodeInfo.getBoundsInScreen(): Rect {
    val rect = Rect()
    getBoundsInScreen(rect)
    return rect
}

/**
 * 获取 text 安全字符串
 */
fun AccessibilityNodeInfo.txt(): String = text?.toString() ?: ""

/**
 * 节点唯一标识（用于去重）
 */
private fun AccessibilityNodeInfo.nodeKey(): String {
    val rect = Rect()
    getBoundsInScreen(rect)
    return "${className}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
}

// ==================== 父节点查找 ====================

/**
 * 查找第一个可点击的父节点
 */
fun AccessibilityNodeInfo.findFirstParentClickable(): AccessibilityNodeInfo? {
    var parent = parent
    while (parent != null) {
        if (parent.isClickable && parent.isEnabled) {
            return parent
        }
        val grandParent = parent.parent
        if (grandParent == null) {
            parent.recycle()
            break
        }
        parent.recycle()
        parent = grandParent
    }
    return null
}

// ==================== 手势 ====================

/**
 * 通过 AccessibilityService 执行手势点击
 */
fun AccessibilityService.gestureClick(x: Float, y: Float): Boolean {
    val path = Path()
    path.moveTo(x, y)
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
        .build()
    return dispatchGesture(gesture, null, null)
}

/**
 * 通过手势向上滑动（模拟手指向上滑 = 列表向下滚动）
 */
fun AccessibilityService.gestureScrollUp(): Boolean {
    val screenWidth = resources.displayMetrics.widthPixels
    val screenHeight = resources.displayMetrics.heightPixels
    val path = Path()
    path.moveTo(screenWidth / 2f, screenHeight * 0.7f)
    path.lineTo(screenWidth / 2f, screenHeight * 0.3f)
    val gesture = GestureDescription.Builder()
        .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
        .build()
    return dispatchGesture(gesture, null, null)
}

// ==================== 启动应用 ====================

/**
 * 启动指定包名的应用
 */
fun AccessibilityService.launchApp(packageName: String): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    return true
}

// ==================== 遍历辅助 ====================

/**
 * BFS 遍历，收集所有节点（不过滤）
 */
fun AccessibilityNodeInfo.bfsCollectPublic(): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return result
}

/**
 * BFS 遍历，收集满足条件的节点
 */
private fun AccessibilityNodeInfo.bfsCollect(
    predicate: (AccessibilityNodeInfo) -> Boolean
): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (predicate(node)) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return result
}

/**
 * BFS 遍历，查找第一个满足条件的节点
 */
private fun AccessibilityNodeInfo.bfsFind(
    predicate: (AccessibilityNodeInfo) -> Boolean
): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)

    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (predicate(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { queue.add(it) }
        }
    }
    return null
}
