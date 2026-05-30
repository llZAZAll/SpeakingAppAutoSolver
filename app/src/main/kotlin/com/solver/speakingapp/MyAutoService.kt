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

    // ===== 보정 값 (필요시 숫자만 바꾸세요) =====
    private val slots = arrayOf(
        Pair(278f, 1730f), // S0 좌상
        Pair(279f, 2005f), // S1 좌하
        Pair(798f, 1732f), // S2 우상
        Pair(799f, 2002f)  // S3 우하
    )
    private val fillOrder = listOf(0, 1, 2, 3)
    private val bandTop = 1640
    private val bandBottom = 2140
    private val snapRadius = 300f
    private val NEXT_BTN = Pair(900f, 2370f)   // '다음 문제' 버튼 (안 맞으면 조정)

    private val stepDelay = 1200L      // 탭 사이 간격 (≈1.2초)
    private val pollDelay = 1200L      // 카드 대기 폴링 간격
    private val cardWaitPolls = 15     // 카드 최대 대기 횟수 (≈18초)
    private val tapDuration = 60L      // 탭 누르는 시간(ms)

    @Volatile private var solving = false
    private var retryAtSameStep = 0
    private var target: List<String> = emptyList()

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
                text = "🤖 풀이 모드\n(볼륨[-] 한 문제 / 볼륨[+] 중단)"
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
        } catch (t: Throwable) {
            Log.e("SOLVE", "onServiceConnected 예외", t)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        try {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                startSolve(); return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
                solving = false
                handler.removeCallbacksAndMessages(null)
                updateLog("🛑 중단됨"); return true
            }
        } catch (t: Throwable) {
            Log.e("SOLVE", "onKeyEvent 예외", t); return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun startSolve() {
        if (solving) { updateLog("이미 풀이 중..."); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { Log.e("SOLVE", "Android 11+ 필요"); return }
        solving = true
        retryAtSameStep = 0
        updateLog("⏳ 카드 대기 중...")
        Log.d("SOLVE", "===== 트리거됨, 카드 대기 시작 =====")
        waitForCards(0)
    }

    // 카드가 화면에 나타날 때까지 폴링 → 나타나면 STT 문장 읽고 풀이 시작
    private fun waitForCards(attempt: Int) {
        if (!solving) return
        captureThen { result ->
            val slotWord = extractSlotWords(result)
            if (slotWord.isNotEmpty()) {
                val raw = AudioCaptureService.lastSentence
                target = raw.lowercase().split(Regex("\\s+")).map { normalize(it) }.filter { it.isNotEmpty() }
                Log.d("SOLVE", "카드 감지(${slotWord.size}개). 사용할 STT 문장: \"$raw\" → 토큰 $target")
                if (target.isEmpty()) {
                    updateLog("❌ STT 문장이 비어있음. 오디오를 먼저 들려주세요"); solving = false; return@captureThen
                }
                updateLog("🤖 풀이: ${target.joinToString(" ")}")
                handler.postDelayed({ solveStep(0) }, stepDelay)
            } else {
                if (attempt < cardWaitPolls) {
                    updateLog("⏳ 카드 대기 중...(${attempt + 1})")
                    handler.postDelayed({ waitForCards(attempt + 1) }, pollDelay)
                } else {
                    Log.e("SOLVE", "카드가 안 떴습니다"); updateLog("❌ 카드가 안 떴어요"); solving = false
                }
            }
        }
    }

    private fun solveStep(p: Int) {
        if (!solving) return
        if (p >= target.size) {
            updateLog("✅ 전부 배치 완료 → 다음 문제")
            Log.d("SOLVE", "전부 배치 완료, NEXT 탭")
            handler.postDelayed({ clickAt(NEXT_BTN.first, NEXT_BTN.second); solving = false }, 900)
            return
        }
        captureThen { result ->
            if (!solving) return@captureThen
            val slotWord = extractSlotWords(result)
            val needed = target[p]
            val k = min(4, target.size - p)
            val occupied = fillOrder.take(k)
            Log.d("SOLVE", "[$p] 필요='$needed' k=$k 인식=${occupied.associateWith { slotWord[it] }}")

            var slotToTap = occupied.firstOrNull { si ->
                slotWord[si]?.let { it == needed || lev(it, needed) <= 1 } == true
            } ?: -1
            if (slotToTap < 0) {
                val unrec = occupied.filter { slotWord[it] == null }
                if (unrec.size == 1) { slotToTap = unrec[0]; Log.d("SOLVE", "[$p] 소거법 → S$slotToTap") }
            }

            if (slotToTap >= 0) {
                val (x, y) = slots[slotToTap]
                Log.d("SOLVE", "[$p] '$needed' → S$slotToTap ($x,$y) 탭")
                updateLog("[${p + 1}/${target.size}] '$needed'")
                clickAt(x, y)
                retryAtSameStep = 0
                handler.postDelayed({ solveStep(p + 1) }, stepDelay)
            } else {
                retryOrAbort(p, "'$needed' 못 찾음(인식=${slotWord.values})")
            }
        }
    }

    // 스크린샷 → 소프트웨어 비트맵 → OCR → 콜백(result)
    private fun captureThen(onResult: (com.google.mlkit.vision.text.Text) -> Unit) {
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
                            .addOnSuccessListener { r -> try { onResult(r) } catch (t: Throwable) { Log.e("SOLVE", "결과처리 예외", t) } }
                            .addOnFailureListener { e -> retryCurrent("OCR실패 ${e.message}") }
                    } catch (t: Throwable) { retryCurrent("캡처처리 ${t.message}") }
                }
                override fun onFailure(code: Int) { retryCurrent("스크린샷실패 code=$code") }
            })
    }

    // 현재 동작(대기/풀이) 중 캡처 단계 실패 시: 단순히 잠시 후 같은 단계 재시도를 위해 로그만.
    // (solveStep/waitForCards 자체가 재귀 호출되므로 여기선 가벼운 재시도)
    private var captureRetry = 0
    private fun retryCurrent(reason: String) {
        if (!solving) return
        captureRetry++
        Log.w("SOLVE", "캡처 재시도($captureRetry): $reason")
        if (captureRetry > 5) { updateLog("❌ 캡처 반복 실패"); solving = false; captureRetry = 0 }
        // 다음 solveStep/waitForCards 호출에서 자연스럽게 다시 캡처됨
    }

    private fun retryOrAbort(p: Int, reason: String) {
        if (!solving) return
        retryAtSameStep++
        Log.w("SOLVE", "[$p] 재시도($retryAtSameStep): $reason")
        if (retryAtSameStep <= 3) handler.postDelayed({ solveStep(p) }, stepDelay)
        else { Log.e("SOLVE", "[$p] 중단: $reason"); updateLog("❌ 중단: $reason"); solving = false }
    }

    private fun extractSlotWords(result: com.google.mlkit.vision.text.Text): HashMap<Int, String> {
        val slotWord = HashMap<Int, String>()
        for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
            val b = el.boundingBox ?: continue
            val cy = b.centerY()
            if (cy < bandTop || cy > bandBottom) continue
            val si = nearestSlot(b.centerX().toFloat(), cy.toFloat()) ?: continue
            val w = normalize(el.text)
            if (w.isNotEmpty()) slotWord[si] = w
        }
        return slotWord
    }

    private fun nearestSlot(x: Float, y: Float): Int? {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in slots.indices) {
            val d = hypot(x - slots[i].first, y - slots[i].second)
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD <= snapRadius) best else null
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun lev(a: String, b: String): Int {
        if (abs(a.length - b.length) > 1) return 2
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, tapDuration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun updateLog(msg: String) {
        handler.post { try { overlayTextView?.text = msg } catch (_: Throwable) {} }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager?.removeView(overlayTextView) } catch (_: Throwable) {}
    }

    override fun onInterrupt() { solving = false }
}
