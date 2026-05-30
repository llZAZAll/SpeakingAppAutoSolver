package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false
    
    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 500
        }

        // 💡 화면 최상단에 고정될 투명 안내창 생성
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayTextView = TextView(this).apply {
                text = "🤖 매크로 대기 중...\n(영어 앱을 켜고 볼륨 단추[-]를 누르세요)"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#AA000000")) // 반투명 검은색 뷰
                setPadding(30, 30, 30, 30)
            }

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES_O) {
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
                y = 120 // 상단 바 아래에 위치하도록 설정
            }

            windowManager?.addView(overlayTextView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (!isTaskRunning) {
                updateLog("🔍 볼륨 신호 감지! 화면 구조 분석을 시작합니다...")
                forceScanAndClick()
            } else {
                updateLog("⏳ 이미 퀴즈 연산이 실행 중입니다.")
            }
            return true // 볼륨 다운 동작 완전 격리
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun forceScanAndClick() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            updateLog("❌ 에러: 시스템이 영어 앱의 화면 구조를 가져오지 못했습니다. (보안 락 또는 유니티 엔진 가능성)")
            return
        }

        isTaskRunning = true
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodes(rootNode, clickableNodes)

        if (clickableNodes.isEmpty()) {
            updateLog("❌ 에러: 화면 내에 클릭 가능한 레이아웃 버튼이 0개입니다.")
            isTaskRunning = false
            return
        }

        // 텍스트 속성이 온전한 영단어 노드 수집
        val wordNodes = clickableNodes.filter { 
            it.text != null && it.text.toString().matches(Regex("^[a-zA-Z]{2,}$")) 
        }
        
        updateLog("📊 분석 리포트\n- 검출된 총 UI 버튼: ${clickableNodes.size}개\n- 매칭된 영어 단어: ${wordNodes.size}개")

        // 하단 제어 구역(다시 듣기) 지정
        val listenButton = clickableNodes.lastOrNull { 
            it.text == null || !it.text.toString().matches(Regex("^[a-zA-Z]+$")) 
        }

        if (listenButton != null) {
            listenButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            updateLog("🔊 하단 보라색 [다시 듣기] 영역 터치 완료. 오디오 재생 동기화 대기 중...")
        }

        handler.postDelayed({
            val sortedWords = wordNodes.sortedBy { it.text.toString().lowercase() }
            if (sortedWords.isNotEmpty()) {
                updateLog("🔤 알파벳 정렬 시퀀스 제어 시작 (${sortedWords.size}개 단어)")
                sortedWords.forEachIndexed { index, node ->
                    handler.postDelayed({
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        updateLog("👆 자동 터치 타겟: [${node.text}]")
                    }, (index + 1) * 400L)
                }
            } else {
                updateLog("⚠️ 경고: 화면 뷰 노드에서 일반 영단어 텍스트를 파싱하지 못했습니다.")
            }

            handler.postDelayed({
                isTaskRunning = false
                updateLog("✅ 한 문제 시퀀스 종료. 다음 문제에서 볼륨[-]을 다시 누르세요.")
            }, (sortedWords.size + 1) * 400L + 1200L)

        }, 2500)
    }

    private fun findAllClickableNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllClickableNodes(child, list)
            }
        }
    }

    // 상단 텍스트 레이어에 실시간 문자열 인쇄
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
