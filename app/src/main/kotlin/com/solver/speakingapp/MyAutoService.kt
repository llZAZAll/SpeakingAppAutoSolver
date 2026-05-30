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

    // 🎯 [Z Fold 7 커버 스크린 전용 좌표]
    // 1. 문제 화면 좌표
    private val LISTEN_BTN = Pair(480f, 2220f) // 하단 보라색 '다시 듣기' 정중앙
    
    // 2x2 단어 슬롯 4개의 고정 좌표 (빈자리에 새 단어가 나와도 무조건 이 4곳만 팹니다)
    private val WORD_SLOTS = arrayOf(
        Pair(240f, 1710f), // 1번 슬롯 (좌측 상단, 예: what)
        Pair(720f, 1710f), // 2번 슬롯 (우측 상단, 예: is)
        Pair(240f, 1980f), // 3번 슬롯 (좌측 하단, 예: time)
        Pair(720f, 1980f)  // 4번 슬롯 (우측 하단, 예: it)
    )

    // 2. 성공 화면 좌표
    private val NEXT_BTN = Pair(800f, 2100f) // 우측 하단 '다음 문제' 버튼 정중앙

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
                text = "🤖 Z폴드 오토 봇 대기중\n(볼륨[-] 시작 / 볼륨[+] 강제중지)"
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
        // 볼륨[-] 누르면 시작
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (!isTaskRunning) {
                updateLog("🚀 매크로 시작! (기관총 터치 가동)")
                startAutoBot()
            }
            return true
        }
        // 볼륨[+] 누르면 긴급 정지
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            isTaskRunning = false
            handler.removeCallbacksAndMessages(null)
            updateLog("🛑 매크로 긴급 정지됨")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun startAutoBot() {
        isTaskRunning = true
        
        // 1. 다시 듣기 터치
        clickAt(LISTEN_BTN.first, LISTEN_BTN.second)
        updateLog("🔊 다시 듣기 클릭")

        // 2. 1.5초 뒤부터 단어 4칸을 무차별 스캔(터치) 시작
        handler.postDelayed({
            updateLog("⚔️ 4칸 슬롯 무차별 타격 중...")
            
            // 8단어 이상이 나와도 충분히 다 누를 수 있도록 4칸을 10바퀴(총 40번) 미친듯이 돕니다.
            val totalSpamLoops = 10 
            var delayAccumulator = 0L

            for (loop in 0 until totalSpamLoops) {
                WORD_SLOTS.forEachIndexed { index, spot ->
                    handler.postDelayed({
                        if(isTaskRunning) clickAt(spot.first, spot.second)
                    }, delayAccumulator)
                    delayAccumulator += 150L // 0.15초 간격으로 터치
                }
            }

            // 3. 문제 풀이가 끝날 때까지 넉넉히 대기 후 '다음 문제' 터치
            handler.postDelayed({
                if(isTaskRunning) {
                    updateLog("⏭️ 다음 문제 버튼 터치")
                    clickAt(NEXT_BTN.first, NEXT_BTN.second)
                }
            }, delayAccumulator + 1500L)

            // 4. 다음 문제로 넘어간 뒤 다시 초기화 및 루프 준비
            handler.postDelayed({
                if(isTaskRunning) {
                    updateLog("✅ 사이클 완료. 계속하려면 볼륨[-]을 누르세요.")
                    isTaskRunning = false
                }
            }, delayAccumulator + 3000L)

        }, 1500)
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50) // 0.05초의 매우 짧은 터치
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
