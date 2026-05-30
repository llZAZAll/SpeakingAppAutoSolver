package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false
    private var currentLoopCount = 0
    private var nodeCount = 0

    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null

    // 🎯 Z Fold 7 커버 스크린 전용 픽셀 좌표 (노드 방식으로 바꾸면 이건 안 써도 됨)
    private val NEXT_BTN = Pair(800f, 2100f)

    private val WORD_SLOTS = arrayOf(
        Pair(240f, 1710f),
        Pair(720f, 1710f),
        Pair(240f, 1980f),
        Pair(720f, 1980f)
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // ✅ FLAG_RETRIEVE_INTERACTIVE_WINDOWS 추가 (이게 빠져서 root가 null로 나올 수 있었음)
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 500
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayTextView = TextView(this).apply {
                text = "🔍 진단 모드\n(볼륨[-] 트리 덤프 / 볼륨[+] 정지)"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#EE000000"))
                setPadding(30, 30, 30, 30)
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = 120
            }

            windowManager?.addView(overlayTextView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            // 진단: 살짝 지연 후 덤프 (키 이벤트 직후 root가 일시적으로 null인 경우 대비)
            handler.postDelayed({ runDiagnostic() }, 150)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            isTaskRunning = false
            handler.removeCallbacksAndMessages(null)
            updateLog("🛑 [정지]")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ===== 진단용 =====
    private fun runDiagnostic() {
        Log.d("TREE", "================ DUMP START ================")
        val root = rootInActiveWindow
        Log.d("TREE", "rootInActiveWindow == null ? -> ${root == null}")

        val wins = windows
        Log.d("TREE", "windows.size = ${wins?.size ?: -1}")
        wins?.forEachIndexed { i, w ->
            Log.d("TREE", "window[$i] type=${w.type} active=${w.isActive} pkg=${w.root?.packageName} childOfRoot=${w.root?.childCount}")
        }

        if (root == null) {
            Log.d("TREE", "root가 null -> 활성 window의 root로 재시도")
            val active = wins?.firstOrNull { it.isActive }?.root
            if (active != null) {
                Log.d("TREE", "active window root pkg=${active.packageName} childCount=${active.childCount}")
                nodeCount = 0
                dumpTree(active)
                Log.d("TREE", "total nodes = $nodeCount")
            } else {
                Log.d("TREE", "활성 window root도 없음")
            }
        } else {
            Log.d("TREE", "root pkg=${root.packageName} childCount=${root.childCount}")
            nodeCount = 0
            dumpTree(root)
            Log.d("TREE", "total nodes = $nodeCount")
        }
        Log.d("TREE", "================ DUMP END ================")
    }

    // 텍스트 유무와 상관없이 모든 노드를 찍음 (커스텀 렌더링 앱인지 판별 위해)
    private fun dumpTree(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null) return
        nodeCount++
        val r = Rect()
        node.getBoundsInScreen(r)
        Log.d(
            "TREE",
            "  ".repeat(depth) +
                "[${node.className}] text='${node.text}' desc='${node.contentDescription}' " +
                "clickable=${node.isClickable} bounds=$r"
        )
        for (i in 0 until node.childCount) dumpTree(node.getChild(i), depth + 1)
    }

    // ===== (나중에 쓸) 좌표 난타 엔진 - 지금은 호출 안 함 =====
    private fun executeAutoSequence() {
        if (!isTaskRunning) return
        currentLoopCount++
        val totalTouches = 30
        var delayAccumulator = 0L
        val touchInterval = 50L
        for (i in 0 until totalTouches) {
            val spot = WORD_SLOTS[i % 4]
            handler.postDelayed({ if (isTaskRunning) clickAt(spot.first, spot.second) }, delayAccumulator)
            delayAccumulator += touchInterval
        }
        handler.postDelayed({
            if (!isTaskRunning) return@postDelayed
            clickAt(NEXT_BTN.first, NEXT_BTN.second)
        }, delayAccumulator + 1000L)
        handler.postDelayed({ if (isTaskRunning) executeAutoSequence() }, delayAccumulator + 5000L)
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 25)
        val gestureBuilder = GestureDescription.Builder().apply { addStroke(stroke) }
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun updateLog(message: String) {
        handler.post { overlayTextView?.text = message }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager?.removeView(overlayTextView) } catch (e: Exception) {}
    }

    override fun onInterrupt() {
        isTaskRunning = false
    }
}
