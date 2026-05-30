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
    private val LISTEN_BTN = Pair(480f, 2220f) // 하단 보라색 '다시 듣기'
    private val NEXT_BTN = Pair(800f, 2100f)   // 우측 하단 '다음 문제'
    
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
                text = "♾️ 무제한 무한 루프 모드\n(볼륨[-] 무한시작 / 볼륨[+] 즉시종료)"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#DD000000"))
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
        // 볼륨[-] 누르면 무한 루프 시작
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (!isTaskRunning) {
                isTaskRunning = true
                currentLoopCount = 0
                updateLog("🚀 무한 자동화 루프 가동 시작!")
                executeAutoSequence()
            }
            return true
        }
        // 볼륨[+] 누르면 그 즉시 모든 타이머와 루프 파괴 (긴급 정지)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            isTaskRunning = false
            handler.removeCallbacksAndMessages(null) // 대기 중인 모든 터치/루프 예약 작업 즉시 캔슬
            updateLog("🛑 [긴급 정지] 모든 매크로 스케줄이 취소되었습니다.")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 🔄 무한 반복되는 핵심 시퀀스 함수
    private fun executeAutoSequence() {
        if (!isTaskRunning) return

        currentLoopCount++
        updateLog("🔄 무한 루프 [ $currentLoopCount 번째 문제 ] 진행 중...")

        // 단계 1. 다시 듣기 버튼 클릭
        clickAt(LISTEN_BTN.first, LISTEN_BTN.second)

        // 단계 2. 1.2초 대기 후 단어 슬롯 4칸 폭풍 난타 시작
        handler.postDelayed({
            if (!isTaskRunning) return@postDelayed
            
            val totalSpamLoops = 10 // 4칸을 10바퀴 (총 40번 터치)
            var delayAccumulator = 0L

            for (loop in 0 until totalSpamLoops) {
                WORD_SLOTS.forEach { spot ->
                    handler.postDelayed({
                        if (isTaskRunning) clickAt(spot.first, spot.second)
                    }, delayAccumulator)
                    delayAccumulator += 140L // 0.14초의 무자비한 광클 간격
                }
            }

            // 단계 3. 모든 단어가 입력되고 성적표("Perfect!!!") 애니메이션이 끝날 때까지 대기 후 '다음 문제' 터치
            // 난타 완료 시점(약 5.6초)에 2초를 더해 안정적으로 넥스트 버튼 유도
            handler.postDelayed({
                if (!isTaskRunning) return@postDelayed
                updateLog("⏭️ 문제 풀이 완료 -> [다음 문제] 버튼 터치")
                clickAt(NEXT_BTN.first, NEXT_BTN.second)
            }, delayAccumulator + 2000L)

            // 단계 4. 다음 문제 화면이 완전히 로딩되는 시간(3초) 대기 후, 스스로 다시 executeAutoSequence() 호출 (재귀 루프)
            handler.postDelayed({
                if (isTaskRunning) {
                    executeAutoSequence() // ♾️ 방아쇠를 다시 당겨 다음 문제로 진입
                }
            }, delayAccumulator + 5000L)

        }, 1200)
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40) // 0.04초 스피드 터치
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
