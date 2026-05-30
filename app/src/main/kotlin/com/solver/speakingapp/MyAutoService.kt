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
                text = "⚡ 맞춤형 무제한 루프 가동\n(볼륨[-] 시작 / 볼륨[+] 즉시종료)"
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
                updateLog("🚀 맞춤형 자동화 루프 가동 시작!")
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

    // 🔄 변경된 맞춤형 무한 반복 시퀀스
    private fun executeAutoSequence() {
        if (!isTaskRunning) return

        currentLoopCount++
        updateLog("⚡ 루프 [ $currentLoopCount 번째 문제 ] 연사 중...")

        // 단계 1 & 2. 진입 즉시 2초 동안 폭풍 난타 가동
        val totalSpamLoops = 5 // 4개 슬롯 x 5바퀴 = 총 20번 터치
        var delayAccumulator = 0L

        for (loop in 0 until totalSpamLoops) {
            WORD_SLOTS.forEach { spot ->
                handler.postDelayed({
                    if (isTaskRunning) clickAt(spot.first, spot.second)
                }, delayAccumulator)
                // 터치 간격 0.1초(100L). 20번 터치 시 정확히 2초(2000ms) 소요
                delayAccumulator += 100L 
            }
        }

        // 단계 3. 난타가 끝난 직후(2초 뒤) 아주 약간의 마진(0.5초)을 두고 다음 문제 버튼 터치
        handler.postDelayed({
            if (!isTaskRunning) return@postDelayed
            updateLog("⏭️ [다음 문제] 버튼 터치 시도")
            clickAt(NEXT_BTN.first, NEXT_BTN.second)
        }, delayAccumulator + 500L)

        // 단계 4. ✨ 다음 문제 버튼 누른 후 정확히 '4초' 뒤에 다음 문제 사이클로 자동 재귀 호출
        // (delayAccumulator + 500L) 시점이 다음 버튼을 누른 시간이므로, 거기에 4000L(4초)을 더합니다.
        handler.postDelayed({
            if (isTaskRunning) {
                executeAutoSequence()
            }
        }, delayAccumulator + 4500L) 
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30) // 0.03초 터치
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
