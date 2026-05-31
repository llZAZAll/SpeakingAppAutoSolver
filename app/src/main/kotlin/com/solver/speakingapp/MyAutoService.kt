package com.solver.speakingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null
    private val ocrClient by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // ===== 보정 값 (OCR 정밀 타격용) =====
    private val slots = arrayOf(
        Pair(278f, 1730f), // S0 좌상
        Pair(279f, 2005f), // S1 좌하
        Pair(798f, 1732f), // S2 우상
        Pair(799f, 2002f)  // S3 우하
    )
    
    // 💡 ===== 비상 30연사 및 다시 듣기 공통 타겟 =====
    private val LISTEN_BTN = Pair(480f, 2220f)
    private val safeSpamSlots = arrayOf(
        Pair(278f, 1730f), // S0 좌상
        Pair(279f, 2005f), // S1 좌하
        Pair(798f, 1732f), // S2 우상
        Pair(799f, 2002f)  // S3 우하
    )

    private val bandTop = 1640
    private val bandBottom = 2140
    private val snapRadius = 300f
    private val NEXT_BTN = Pair(900f, 2100f)

    private val stepDelay = 1200L
    private val pollDelay = 1200L
    private val cardWaitPolls = 15
    private val tapDuration = 60L

    // 빈칸/카드 판별용
    private val occBrightThreshold = 170
    private val occMinBrightPixels = 4
    private val occHalfW = 90
    private val occHalfH = 35

    @Volatile private var solving = false
    private var retryAtSameStep = 0
    private var captureRetry = 0
    private var target: List<String> = emptyList()
    private var currentLoopCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.DEFAULT or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                notificationTimeout = 500
            }
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            overlayTextView = TextView(this).apply {
                text = "🤖 하이브리드 무한 모드\n(볼륨[-] 시작 / 볼륨[+] 중단)"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#EE000000"))
                setPadding(24, 24, 24, 24)
            }
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP; y = 90 }
            windowManager?.addView(overlayTextView, params)
        } catch (t: Throwable) { Log.e("SOLVE", "onServiceConnected 예외", t) }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        try {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                if (!solving) {
                    currentLoopCount = 0
                    startSolve()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
                solving = false
                handler.removeCallbacksAndMessages(null)
                updateLog("🛑 매크로 완전히 중단됨")
                return true
            }
        } catch (t: Throwable) { Log.e("SOLVE", "onKeyEvent 예외", t); return true }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun startSolve() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { Log.e("SOLVE", "Android 11+ 필요"); return }
        solving = true; retryAtSameStep = 0; captureRetry = 0
        updateLog("⏳ 카드 대기 중...")
        Log.d("SOLVE", "===== 트리거됨, 카드 대기 =====")
        waitForCards(0)
    }

    private fun waitForCards(attempt: Int) {
        if (!solving) return
        captureThen { result, _ ->
            val slotWord = extractSlotWords(result)
            if (slotWord.isNotEmpty()) {
                val raw = AudioCaptureService.lastSentence
                target = raw.lowercase().split(Regex("\\s+")).map { normalize(it) }.filter { it.isNotEmpty() }
                Log.d("SOLVE", "카드 감지(${slotWord.size}). STT: \"$raw\" → $target")
                
                if (target.isEmpty()) { 
                    triggerSpamFallback("❌ STT(음성) 인식 실패")
                    return@captureThen 
                }
                
                updateLog("🤖 ${target.joinToString(" ")}")
                
                // 💡 [정상 루프] 첫 단어 터치 직전 '다시 듣기' 1회 타격
                clickAt(LISTEN_BTN.first, LISTEN_BTN.second)
                
                // 다시 듣기 누르고 1.2초(stepDelay) 대기 후 정답 찾기 시작
                handler.postDelayed({ solveStep(0) }, stepDelay)
                
            } else if (attempt < cardWaitPolls) {
                updateLog("⏳ 카드 대기...(${attempt + 1})")
                handler.postDelayed({ waitForCards(attempt + 1) }, pollDelay)
            } else { 
                Log.e("SOLVE", "카드 안 뜸")
                triggerSpamFallback("❌ 카드 화면 진입 실패")
            }
        }
    }

    private fun solveStep(p: Int) {
        if (!solving) return
        
        if (p >= target.size) {
            currentLoopCount++
            updateLog("✅ [$currentLoopCount] 정답 완료 → 다음 문제 진입")
            Log.d("SOLVE", "완료, NEXT 탭")
            handler.postDelayed({
                if (solving) clickAt(NEXT_BTN.first, NEXT_BTN.second)
                
                handler.postDelayed({
                    if (solving) {
                        retryAtSameStep = 0
                        captureRetry = 0
                        updateLog("⏳ 새 문제 대기 중...")
                        waitForCards(0) 
                    }
                }, 3500L) 
            }, 900)
            return
        }
        
        captureThen { result, bmp ->
            if (!solving) return@captureThen
            val slotWord = extractSlotWords(result)
            val occ = IntArray(4) { brightCount(bmp, it) }
            val occupied = BooleanArray(4) { occ[it] >= occMinBrightPixels }
            val needed = target[p]

            var slotToTap = (0..3).firstOrNull { si ->
                occupied[si] && slotWord[si]?.let { it == needed || lev(it, needed) <= 1 } == true
            } ?: -1
            
            if (slotToTap < 0) {
                val unrecOcc = (0..3).filter { occupied[it] && slotWord[it] == null }
                if (unrecOcc.size == 1) { slotToTap = unrecOcc[0] }
            }

            if (slotToTap >= 0) {
                val (x, y) = slots[slotToTap]
                Log.d("SOLVE", "[$p] '$needed' → S$slotToTap 탭")
                updateLog("[${p + 1}/${target.size}] '$needed'")
                clickAt(x, y)
                retryAtSameStep = 0
                handler.postDelayed({ solveStep(p + 1) }, stepDelay)
            } else {
                retryOrAbort(p, "단어 '$needed' 탐색 실패")
            }
        }
    }

    private fun captureThen(onResult: (Text, Bitmap) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, applicationContext.mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    try {
                        val hb = s.hardwareBuffer
                        val raw = Bitmap.wrapHardwareBuffer(hb, s.colorSpace)
                        hb.close()
                        if (raw == null) { retryCurrent("비트맵 null"); return }
                        val bmp = raw.copy(Bitmap.Config.ARGB_8888, false)
                        try { raw.recycle() } catch (_: Throwable) {}
                        ocrClient.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { r ->
                                try { onResult(r, bmp) } catch (t: Throwable) { Log.e("SOLVE", "결과처리 예외", t) }
                                finally { try { bmp.recycle() } catch (_: Throwable) {} }
                            }
                            .addOnFailureListener { e -> try { bmp.recycle() } catch (_: Throwable) {}; retryCurrent("OCR실패 ${e.message}") }
                    } catch (t: Throwable) { retryCurrent("캡처처리 ${t.message}") }
                }
                override fun onFailure(code: Int) { retryCurrent("스크린샷실패 code=$code") }
            })
    }

    private fun retryCurrent(reason: String) {
        if (!solving) return
        captureRetry++
        Log.w("SOLVE", "캡처 재시도($captureRetry): $reason")
        
        if (captureRetry > 6) { 
            triggerSpamFallback("❌ 화면 캡처 반복 에러")
        }
    }

    private fun retryOrAbort(p: Int, reason: String) {
        if (!solving) return
        retryAtSameStep++
        Log.w("SOLVE", "[$p] 재시도($retryAtSameStep): $reason")
        
        if (retryAtSameStep <= 3) handler.postDelayed({ solveStep(p) }, stepDelay)
        else { 
            Log.e("SOLVE", "[$p] 중단: $reason")
            triggerSpamFallback("❌ 정답 매칭 실패")
        }
    }

    // 🚀 ===== 비상 탈출 무지성 30연사 엔진 =====
    private fun triggerSpamFallback(reason: String) {
        if (!solving) return
        
        currentLoopCount++
        updateLog("$reason\n⚠️ 비상 돌파! 오리지널 30연사 가동 중...")

        var delayAccumulator = 0L

        // 💡 1) 비상 연사 시작 전 '다시 듣기' 1회 타격
        handler.postDelayed({
            if (solving) clickAt(LISTEN_BTN.first, LISTEN_BTN.second)
        }, delayAccumulator)

        delayAccumulator += 800L 

        // 💡 2) 0.8초 후 오리지널 좌표 30연타 시작
        for (i in 0 until 30) {
            val spot = safeSpamSlots[i % 4]
            handler.postDelayed({
                if (solving) clickAt(spot.first, spot.second)
            }, delayAccumulator)
            delayAccumulator += 50L // 0.05초 간격
        }

        // 💡 3) 연타 종료 1초 후 다음 문제 터치
        handler
