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

    // 🎯 [이미지 정밀 분석] Z 폴드 7 커버 스크린 최적화 안전 좌표
    private val NEXT_BTN = Pair(800f, 2100f)   // 우측 하단 '다음 문제' 버튼 위치
    
    private val WORD_SLOTS = arrayOf(
        Pair(240f, 1580f), // 1번 슬롯 (좌상 - 정중앙 타격)
        Pair(720f, 1580f), // 2번 슬롯 (우상 - 정중앙 타격)
        Pair(240f, 1790f), // 3번 슬롯 (좌하 - ✨북마크를 피하기 위해 타일 최상단 저격)
        Pair(720f, 1790f)  // 4번 슬롯 (우하 - ✨북마크를 피하기 위해 타일 최상단 저격)
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
                text = "⚡ 안전 좌표 무한 오토 봇\n(볼륨[-] 시작 / 볼륨[+] 즉시종료)"
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
                updateLog("🚀 안전지대 초고속 연사 시작!")
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

    // 🔄 정밀 조율된 무한 반복 시퀀스 엔지니어링
    private fun executeAutoSequence() {
        if (!isTaskRunning) return

        currentLoopCount++
        updateLog("⚡ 루프 [ $currentLoopCount 번째 문제 ] 1.5초 30연타 가동")

        // [단계 1 & 2] 진입 즉시 1.5초 동안 정확히 30번 초고속 터치 (다시 듣기 완전 패스)
        val totalTouches = 30
        var delayAccumulator = 0L
        val touchInterval = 50L // 0.05초 주기로 다다다닥 터치

        for (i in 0 until totalTouches) {
            val spot = WORD_SLOTS[i % 4]
            handler.postDelayed({
                if (isTaskRunning) clickAt(spot.first, spot.second)
            }, delayAccumulator)
            delayAccumulator += touchInterval
        }

        // [단계 3] 30연타 폭격 종료 후, 정확히 1초(1000ms) 대기 후 다음 문제 버튼 터치
        handler.postDelayed({
            if (!isTaskRunning) return@postDelayed
            updateLog("⏭️ [다음 문제] 버튼 터치 시도")
            clickAt(NEXT_BTN.first, NEXT_BTN.second)
        }, delayAccumulator + 1000L)

        // [단계 4] 다음 문제 버튼 누르고 폰이 완전히 로딩되도록 정확히 '4초' 대기 후 자동 재귀 호출
        handler.postDelayed({
            if (isTaskRunning) {
                executeAutoSequence()
            }
        }, delayAccumulator + 5000L) // 단계3(1초) + 단계4(4초) = 총 5000L
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
