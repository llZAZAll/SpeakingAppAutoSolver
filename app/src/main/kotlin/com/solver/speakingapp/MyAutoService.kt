package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 서비스가 완벽하게 켜지면 화면에 알림을 띄웁니다!
        showToast("매크로 서비스가 가동되었습니다!")
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 300
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isTaskRunning) return
        val rootNode = rootInActiveWindow ?: return

        // 화면에서 "다시 듣기" 버튼 탐색
        val listenButtons = rootNode.findAccessibilityNodeInfosByText("다시 듣기")
        
        if (!listenButtons.isNullOrEmpty()) {
            showToast("영어 문제 화면 감지! 매크로 시작")
            startSolvingRoutine(listenButtons[0], rootNode)
        }
    }

    private fun startSolvingRoutine(listenButtonNode: AccessibilityNodeInfo, rootNode: AccessibilityNodeInfo) {
        isTaskRunning = true
        
        // 1. 다시 듣기 클릭
        if (listenButtonNode.isClickable) {
            listenButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            showToast("🔊 다시 듣기 버튼을 자동으로 눌렀습니다.")
        }

        // 2. 소리 재생 대기 (2초 뒤 단어 터치)
        handler.postDelayed({
            val allNodes = rootNode.findAccessibilityNodeInfosByText("") ?: return@postDelayed
            val wordNodes = mutableListOf<AccessibilityNodeInfo>()

            for (node in allNodes) {
                if (node.isClickable && node.text != null && node.text.toString().matches(Regex("^[a-zA-Z]+$"))) {
                    wordNodes.add(node)
                }
            }

            if (wordNodes.isEmpty()) {
                showToast("화면에서 터치할 영어 단어를 찾지 못했습니다.")
                isTaskRunning = false
                return@postDelayed
            }

            // 알파벳 순 정렬
            wordNodes.sortBy { it.text.toString().lowercase() }
            showToast("${wordNodes.size}개의 단어 자동 터치 시작!")

            // 순차 클릭
            wordNodes.forEachIndexed { index, node ->
                handler.postDelayed({
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }, (index + 1) * 300L)
            }

            // 초기화 대기
            handler.postDelayed({
                isTaskRunning = false
            }, (wordNodes.size + 1) * 300L + 1500L)

        }, 2000)
    }

    // 화면에 알림 메시지를 띄우는 편의 기능
    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        isTaskRunning = false
    }
}
