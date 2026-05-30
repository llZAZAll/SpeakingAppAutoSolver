package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false
    private var currentLoopCount = 0
    
    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null

    // 🎯 Z Fold 7 커버 스크린 전용 픽셀 좌표
    private val NEXT_BTN = Pair(800f, 2100f)   // 우측 하단 '다음 문제' 버튼 위치
    
    private val WORD_SLOTS = arrayOf(
        Pair(240f, 1710f), // 1번 슬롯 (좌상)
        Pair(720f, 1710f), // 2번 슬롯 (우상)
        Pair(240f, 1980f), // 3번 슬롯 (좌하)
        Pair(720f, 1980f)  // 4번 슬롯 (우하)
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 500
        }

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayTextView = TextView(this).apply {
                text = "⚡ 1.5초 30연사 초고속 모드\n(볼륨[-] 시작 / 볼륨[+] 즉시종료)"
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
            if (!isTaskRunning) {
                isTaskRunning = true
                currentLoopCount = 0
                updateLog("🚀 30연사 초고속 루프 시작!")
                executeAutoSequence()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            isTaskRunning = false
            handler.removeCallbacksAndMessages(null)
            updateLog("🛑 [긴급 정지] 매크로가 즉시 종료되었습니다.")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 🔄 1.5초 30연사 시퀀스 엔진
    private fun executeAutoSequence() {
        if (!isTaskRunning) return

        currentLoopCount++
        updateLog("⚡ 루프 [ $currentLoopCount 번째 문제 ] 30연사 폭격 중...")

        // 💡 [변경 구역] 진입 즉시 1.5초 동안 정확히 30번 터치 제어
        val totalTouches = 30
        var delayAccumulator = 0L
        val touchInterval = 50L // 50ms = 0.05초 간격

        for (i in 0 until totalTouches) {
            val spot = WORD_SLOTS[i % 4] // 0, 1, 2, 3번 슬롯을 계속 순환 구조로 터치
            handler.postDelayed({
                if (isTaskRunning) clickAt(spot.first, spot.second)
            }, delayAccumulator)
            delayAccumulator += touchInterval
        }

        // 단계 3. 30연타가 완전히 끝난 시점(1.5초 뒤)에 정확히 1초(1000ms) 더 쉬고 다음 문제 버튼 터치
        handler.postDelayed({
            if (!isTaskRunning) return@postDelayed
            updateLog("⏭️ [다음 문제] 버튼 터치 시도")
            clickAt(NEXT_BTN.first, NEXT_BTN.second)
        }, delayAccumulator + 1000L)

        // 단계 4. 다음 문제 버튼을 누른 시점으로부터 정확히 '4초' 뒤에 다시 재귀 호출
        // (delayAccumulator + 1000L) 상태에서 4000L을 더하므로 총 마진은 오차 없이 5000L이 유지됩니다.
        handler.postDelayed({
            if (isTaskRunning) {
                executeAutoSequence()
            }
        }, delayAccumulator + 5000L) 
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 25) // 초고속 연사를 위해 잔상 시간을 0.025초로 단축
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
