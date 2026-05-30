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
        showToast("🔥 무조건 터치 모드 가동!")
        
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 500
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isTaskRunning) return
        val rootNode = rootInActiveWindow ?: return

        // [핵심 변경] 화면 전체에서 "클릭 가능한 모든 버튼"을 강제로 수집합니다.
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodes(rootNode, clickableNodes)

        // 화면에 클릭 가능한 요소가 최소 5개 이상 있을 때만 작동 (상단 바 버튼 + 단어들)
        if (clickableNodes.size >= 5) {
            
            // 영어 단어 조각들만 필터링 (텍스트가 2자 이상 영문인 것들)
            val wordNodes = clickableNodes.filter { 
                it.text != null && it.text.toString().matches(Regex("^[a-zA-Z]{2,}$")) 
            }

            // 화면에 영어 단어가 4개쯤 보인다면 퀴즈 화면으로 확신!
            if (wordNodes.size >= 3) {
                isTaskRunning = true
                showToast("🎯 퀴즈 화면 포착! 매크로 구동")
                
                // 1. [다시 듣기] 버튼 찾기: 보통 영어 단어들 바로 근처나 화면 맨 아래에 있습니다.
                // 영어 단어가 아닌 클릭 가능 버튼 중 가장 하단에 있는 것을 '다시 듣기'로 추정합니다.
                val listenButton = clickableNodes.lastOrNull { 
                    it.text == null || !it.text.toString().matches(Regex("^[a-zA-Z]+$")) 
                }

                if (listenButton != null) {
                    listenButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    showToast("🔊 하단 보라색 버튼 클릭 완료")
                }

                // 2. 소리 끝날 때까지 2.5초 대기 후 단어 클릭
                handler.postDelayed({
                    // 알파벳 순 정렬
                    val sortedWords = wordNodes.sortedBy { it.text.toString().lowercase() }
                    showToast("🔤 단어 ${sortedWords.size}개 정렬 터치 시작")

                    sortedWords.forEachIndexed { index, node ->
                        handler.postDelayed({
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }, (index + 1) * 400L) // 0.4초 간격 터치
                    }

                    // 초기화 대기 (퀴즈를 풀고 다음 화면 넘어갈 시간 제공)
                    handler.postDelayed({
                        isTaskRunning = false
                        showToast("✅ 한 문제 완료, 다음 대기 중")
                    }, (sortedWords.size + 1) * 400L + 2000L)

                }, 2500)
            }
        }
    }

    // 화면 트리를 뒤져서 클릭 가능한 모든 요소를 재귀적으로 찾아내는 함수
    private fun findAllClickableNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            findAllClickableNodes(node.getChild(i), list)
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupt() {
        isTaskRunning = false
    }
}
