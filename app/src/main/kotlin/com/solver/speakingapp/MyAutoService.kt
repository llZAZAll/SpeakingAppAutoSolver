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
    
    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null

    // 💡 [설정] 본인 스마트폰 해상도에 맞는 실제 X, Y 좌표값으로 수정이 필요합니다.
    // 아래 수치는 일반적인 1080 x 2400 해상도 스마트폰 기준 대략적인 예시 위치입니다.
    private val LISTEN_BUTTON_X = 540f   // 다시 듣기 버튼 (화면 가로 중앙)
    private val LISTEN_BUTTON_Y = 1950f  // 다시 듣기 버튼 (화면 하단 보라색 버튼 위치)

    // 화면 하단에 배치된 단어 조각들의 예상 좌표 목록 (4개 배치 기준 예시)
    private val WORD_SPOTS = arrayOf(
        Pair(220f, 1600f),  // 1번째 단어 위치
        Pair(430f, 1600f),  // 2번째 단어 위치
        Pair(650f, 1600f),  // 3번째 단어 위치
        Pair(860f, 1600f)   // 4번째 단어 위치
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
                text = "🎯 절대 좌표 터치 모드 가동\n(영어 앱 켜고 볼륨[-] 누르기)"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#CC000000"))
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
                updateLog("🚀 지정된 X, Y 좌표로 강제 터치 신호 주입 시작...")
                simulateCoordinateClicks()
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun simulateCoordinateClicks() {
        isTaskRunning = true

        // 1. 다시 듣기 버튼 위치 강제 터치
        clickAt(LISTEN_BUTTON_X, LISTEN_BUTTON_Y)
        updateLog("🔊 다시 듣기 터치 주입 (X:$LISTEN_BUTTON_X, Y:$LISTEN_BUTTON_Y)")

        // 2. 오디오 재생 동기화 대기 후(2.5초) 단어 영역 순차 터치
        handler.postDelayed({
            updateLog("🔤 단어 슬롯 좌표 ${WORD_SPOTS.size}개 매크로 가동")
            
            WORD_SPOTS.forEachIndexed { index, spot ->
                handler.postDelayed({
                    clickAt(spot.first, spot.second)
                    updateLog("👆 [순서 ${index + 1}] 터치 완료 (X:${spot.first}, Y:${spot.second})")
                }, (index + 1) * 400L) // 0.4초 간격 터치
            }

            handler.postDelayed({
                isTaskRunning = false
                updateLog("✅ 좌표 시퀀스 완료. 다음 문제에서 볼륨[-]을 누르세요.")
            }, (WORD_SPOTS.size + 1) * 400L + 1000L)

        }, 2500)
    }

    // 💡 안드로이드 시스템에 좌표 신호를 다이렉트로 찔러넣는 핵심 함수
    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gestureBuilder = GestureDescription.Builder().apply {
            addStroke(stroke)
        }
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    private fun updateLog(message: String) {
        handler.post {
            overlayTextView?.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (windowManager != null && overlayTextView != null) {
                windowManager?.removeView(overlayTextView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        isTaskRunning = false
    }
}
