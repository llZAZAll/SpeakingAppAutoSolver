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
                text = "⚡ 초고속 무제한 루프 가동\n(볼륨[-] 시작 / 볼륨[+] 즉시종료)"
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
                updateLog("🚀 초고속 자동화 루프 가동 시작!")
                executeAutoSequence()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            isTaskRunning = false
            handler.removeCallbacksAndMessages(null) // 모든 예약 스케줄 전면 파괴
            updateLog("🛑 [긴급 정지] 매크로가 즉시 종료되었습니다.")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 🔄 변경된 초고속 무한 반복 시퀀스
    private fun executeAutoSequence() {
        if (!isTaskRunning) return

        currentLoopCount++
        updateLog("⚡ 루프 [ $currentLoopCount 번째 문제 ] 초고속 연사 중...")

        // 단계 1 & 2. 진입 즉시 4초 동안 폭풍 난타 가동 (다시 듣기 생략)
        val totalSpamLoops = 10 // 4개 슬롯 x 10바퀴 = 총 40번 터치
        var delayAccumulator = 0L

        for (loop in 0 until totalSpamLoops) {
            WORD_SLOTS.forEach { spot ->
                handler.postDelayed({
                    if (isTaskRunning) clickAt(spot.first, spot.second)
                }, delayAccumulator)
                delayAccumulator += 100L // ✨ 터치 간격을 0.1초로 축소하여 40번 터치가 정확히 4초(4000ms) 내에 완료됨
            }
        }

        // 단계 3. 난타가 끝난 직후(4초 뒤) 아주 약간의 마진(0.5초)을 두고 다음 문제 버튼 터치
        handler.postDelayed({
            if (!isTaskRunning) return@postDelayed
            updateLog("⏭️ [다음 문제] 버튼 터치 시도")
            clickAt(NEXT_BTN.first, NEXT_BTN.second)
        }, delayAccumulator + 500L)

        // 단계 4. ✨ 다음 문제 버튼 누른 후 정확히 2초 뒤에 다음 문제 사이클로 자동 재귀 호출
        handler.postDelayed({
            if (isTaskRunning) {
                executeAutoSequence()
            }
        }, delayAccumulator + 2500L)
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 30) // 0.03초 초고속 터치 잔상
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
