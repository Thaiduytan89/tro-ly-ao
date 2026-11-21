package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Tay chân" của agent: đọc cây UI và thực hiện chạm/gõ/cuộn trên mọi app.
 * Người dùng phải bật dịch vụ này trong Settings > Accessibility một lần.
 */
class AssistantAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Giữ cây UI mới nhất để các action đọc ngay khi cần.
        lastEventTime = System.currentTimeMillis()
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility interrupted")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Tóm tắt các node có chữ/id để LLM biết đang ở màn hình nào. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Không đọc được màn hình hiện tại."
        val out = StringBuilder()
        collect(root, out, 0)
        root.recycle()
        return out.toString().take(8000).ifBlank { "Màn hình không có văn bản nào." }
    }

    private fun collect(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 14 || out.length > 8000) return
        val text = node.text?.toString().orEmpty().trim()
        val desc = node.contentDescription?.toString().orEmpty().trim()
        val id = node.viewIdResourceName.orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            out.append(if (node.isClickable) "[click] " else "[view] ")
            if (text.isNotEmpty()) out.append("text=").append(text).append(' ')
            if (desc.isNotEmpty()) out.append("desc=").append(desc).append(' ')
            if (id.isNotEmpty()) out.append("id=").append(id)
            out.append('\n')
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collect(child, out, depth + 1)
                child.recycle()
            }
        }
    }

    fun clickText(target: String): Boolean {
        if (target.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(target)
        val node = nodes.firstOrNull { it.isEnabled && it.isClickable }
            ?: nodes.firstOrNull { it.isEnabled }
            ?: nodes.firstOrNull()
        val ok = node?.let { ascendToClickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
        nodes.forEach { it.recycle() }
        root.recycle()
        return ok
    }

    fun clickId(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString().orEmpty()
        val fullId = if (resourceId.contains(":")) resourceId else "$pkg:id/$resourceId"
        val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
        val ok = nodes.firstOrNull()
            ?.let { ascendToClickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK) } ?: false
        nodes.forEach { it.recycle() }
        root.recycle()
        return ok
    }

    fun typeText(value: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: findEditable(root)
        if (target == null) {
            root.recycle()
            return false
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        root.recycle()
        return ok
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root)
        val ok = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
        root.recycle()
        return ok
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun ascendToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) {
            current = current.parent
        }
        return current
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findEditable(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                val found = findScrollable(child)
                child.recycle()
                if (found != null) return found
            }
        }
        return null
    }

    fun isReady(): Boolean = rootInActiveWindow != null

    companion object {
        private const val TAG = "AssistantA11y"
        @Volatile
        var instance: AssistantAccessibilityService? = null
            private set
        @Volatile
        var lastEventTime: Long = 0L
            private set
    }
}
