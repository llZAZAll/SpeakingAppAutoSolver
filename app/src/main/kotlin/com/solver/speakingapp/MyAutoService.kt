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

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayTextView = TextView(this).apply {
                text = "🤖 딥스캔 대기 중...\n(영어 앱 켜고 볼륨[-] 누르기)"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#CC000000")) // 조금 더 진하게
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
                updateLog("🔍 볼륨 신호 감지! 모든 텍스트 딥스캔 시작...")
                forceDeepScanAndClick()
            }
            return true 
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 💡 [핵심] 클릭 여부 무시하고 화면의 모든 요소를 싹 다 가져옵니다.
    private fun forceDeepScanAndClick() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            updateLog("❌ 에러: 화면을 전혀 읽을 수 없습니다 (유니티 엔진 등 보안 락)")
            return
        }

        isTaskRunning = true
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllNodes(rootNode, allNodes) // 클릭 필터 제거하고 무조건 수집

        // 텍스트가 존재하는 노드만 추출
        val textNodes = allNodes.filter { it.text != null && it.text.toString().isNotBlank() }

        if (textNodes.isEmpty()) {
            updateLog("❌ 에러: 화면에 텍스트 데이터가 0개입니다. (그림으로 처리됨)")
            isTaskRunning = false
            return
        }

        // 1. 순수 영단어(2글자 이상) 노드 찾기
        val wordNodes = textNodes.filter { 
            it.text.toString().matches(Regex("^[a-zA-Z]{2,}$")) 
        }

        updateLog("📊 딥스캔 완료\n- 전체 텍스트 조각: ${textNodes.size}개\n- 매칭된 영어 단어: ${wordNodes.size}개")

        if (wordNodes.isEmpty()) {
            updateLog("⚠️ 영어 단어를 찾지 못했습니다. 텍스트 추출 목록:\n${textNodes.take(3).joinToString { it.text.toString() }}...")
            isTaskRunning = false
            return
        }

        // 2. 다시 듣기 버튼 찾기 (영어 단어가 아닌 것 중 가장 밑에 있는 것)
        val listenButton = textNodes.lastOrNull { 
            !it.text.toString().matches(Regex("^[a-zA-Z]+$")) 
        }

        if (listenButton != null) {
            forceClick(listenButton)
            updateLog("🔊 하단 텍스트 [${listenButton.text}] 강제 터치 완료")
        }

        // 3. 소리 재생 후 단어 터치
        handler.postDelayed({
            val sortedWords = wordNodes.sortedBy { it.text.toString().lowercase() }
            
            updateLog("🔤 단어 ${sortedWords.size}개 강제 터치 시작")
            sortedWords.forEachIndexed { index, node ->
                handler.postDelayed({
                    forceClick(node)
                    updateLog("👆 타겟 강제 터치: [${node.text}]")
                }, (index + 1) * 400L)
            }

            handler.postDelayed({
                isTaskRunning = false
                updateLog("✅ 시퀀스 종료. 다음 문제에서 볼륨[-]을 누르세요.")
            }, (sortedWords.size + 1) * 400L + 1200L)

        }, 2500)
    }

    // 화면의 모든 노드를 무조건 긁어모으는 함수
    private fun findAllNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        list.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAllNodes(child, list)
            }
        }
    }

    // 💡 [핵심] 자신이 클릭 안 되면, 클릭 가능한 부모(배경 패널)를 찾을 때까지 거슬러 올라가서 터치하는 강력한 함수
    private fun forceClick(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            current = current.parent
        }
        // 부모도 클릭 불가능하면 그냥 자신에게 터치 신호를 구겨 넣음
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
