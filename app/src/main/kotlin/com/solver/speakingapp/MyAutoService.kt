package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false // 매크로 중복 실행 방지

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 서비스가 켜지면 실행되는 초기화 코드
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT
        info.notificationTimeout = 500 // 0.5초마다 화면 변화 감지
        serviceInfo = info
    }

    // 화면이 바뀔 때마다 실행됨 (문제 화면 감지용)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isTaskRunning) return
        
        val rootNode = rootInActiveWindow ?: return

        // 1. 현재 화면이 "문제 화면"인지 감지
        // 스크린샷 하단의 "다시 듣기" 버튼이 있는지 확인
        val listenButtons = rootNode.findAccessibilityNodeInfosByText("다시 듣기")
        
        if (!listenButtons.isNullOrEmpty()) {
            // "다시 듣기" 버튼을 찾았다면, 문제 풀이 루틴 시작!
            startSolvingRoutine(listenButtons[0], rootNode)
        }
    }

    // 문제 풀이 핵심 루틴
    private fun startSolvingRoutine(listenButtonNode: AccessibilityNodeInfo, rootNode: AccessibilityNodeInfo) {
        isTaskRunning = true
        
        // --- 루틴 A: "다시 듣기" 버튼 클릭 ---
        // 사용자의 요청대로, 가장 먼저 다시 듣기를 꼭 누름
        if (listenButtonNode.isClickable) {
            listenButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // --- 루틴 B: 잠깐 대기 (난이도 유지 및 앱 반응 시간) ---
        // 소리가 다 끝나기를 기다림 (여기서는 2초 대기)
        handler.postDelayed({
            // --- 루틴 C: 단어 조각들 찾아 알파벳 순으로 누르기 (임시 정답 로직) ---
            val allNodes = rootNode.findAccessibilityNodeInfosByText("") // 화면의 모든 텍스트 노드 탐색
            val wordNodes = mutableListOf<AccessibilityNodeInfo>()

            for (node in allNodes) {
                // 단어 버튼은 클릭 가능해야 하고, 텍스트가 영어 알파벳으로만 구성되어 있어야 함
                if (node.isClickable && node.text != null && node.text.toString().matches(Regex("^[a-zA-Z]+$"))) {
                    wordNodes.add(node)
                }
            }

            // [임시 정답 Logic]: 찾은 단어들을 알파벳 순으로 정렬 (나중에 STT로 정답 문장 받으면 이 부분을 교체)
            wordNodes.sortBy { it.text.toString().lowercase() }

            // [순서대로 클릭]: 정렬된 단어들을 0.3초 간격으로 터치
            wordNodes.forEachIndexed { index, node ->
                handler.postDelayed({
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }, (index + 1) * 300L) // 300ms, 600ms, 900ms... 간격
            }

            // 문제 풀이가 다 끝났으므로 다른 문제가 나올 때까지 대기
            handler.postDelayed({
                isTaskRunning = false
            }, (wordNodes.size + 1) * 300L + 1000L) // 퀴즈 다 푼 후 1초 뒤에 다시 감지 시작

        }, 2000) // 2초 대기
    }

    override fun onInterrupt() {
        // 서비스 중단 시
        isTaskRunning = false
    }
}
