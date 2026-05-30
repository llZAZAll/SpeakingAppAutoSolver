package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isTaskRunning = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        showToast("✅ 볼륨 하단 버튼을 누르면 매크로가 실행됩니다!")
        
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 500
        }
    }

    // 💡 [핵심] 볼륨 하단 버튼을 눌렀을 때 강제 실행
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (!isTaskRunning) {
                showToast("🔍 볼륨 버튼 감지! 화면 강제 스캔 시작...")
                forceScanAndClick()
            }
            return true // 시스템 볼륨이 줄어드는 것을 막음
        }
        return super.onKeyEvent(event)
    }

    // 화면 자동 감지는 일단 켜두지만 메인은 볼륨 버튼으로 씁니다.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // 화면을 강제로 뜯어보고 클릭하는 함수
    private fun forceScanAndClick() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            showToast("❌ 에러: 이 앱은 화면 정보를 완전히 차단하고 있습니다.")
            return
        }

        isTaskRunning = true
        val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableNodes(rootNode, clickableNodes)

        showToast("발견된 클릭 가능 버튼: ${clickableNodes.size}개")

        if (clickableNodes.isEmpty()) {
            isTaskRunning = false
            return
        }

        // 1. 단어 버튼들 찾기 (2자 이상 영어)
        val wordNodes = clickableNodes.filter { 
            it.text != null && it.text.toString().matches(Regex("^[a-zA-Z]{2,}$")) 
        }

        // 2. 다시 듣기 버튼 찾기 (영어 단어가 아닌 버튼 중 가장 하단)
        val listenButton = clickableNodes.lastOrNull { 
            it.text == null || !it.text.toString().matches(Regex("^[a-zA-Z]+$")) 
        }

        if (listenButton != null) {
            listenButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            showToast("🔊 하단 버튼(다시 듣기) 클릭함")
        }

        // 3. 소리 재생 후(2.5초) 단어 터치
        handler.postDelayed({
            val sortedWords = wordNodes.sortedBy { it.text.toString().lowercase() }
            if (sortedWords.isNotEmpty()) {
                showToast("🔤 단어 ${sortedWords.size}개 터치 시작")
                sortedWords.forEachIndexed { index, node ->
                    handler.postDelayed({
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }, (index + 1) * 400L)
                }
            } else {
                showToast("영어 단어를 찾지 못했습니다.")
            }

            handler.postDelayed({
                isTaskRunning = false
            }, (sortedWords.size + 1) * 400L + 1000L)

        }, 2500)
    }

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
