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

    // ===== 보정 값 (Note10 / 1080x2280) =====
    private val slots = arrayOf(
        Pair(279f, 1489f), // S0 좌상
        Pair(279f, 1759f), // S1 좌하
        Pair(799f, 1489f), // S2 우상
        Pair(799f, 1759f)  // S3 우하
    )

    // 💡 비상 30연사 및 다시 듣기 공통 타겟
    private val LISTEN_BTN = Pair(540f, 1999f) // 하단 다시 듣기 버튼
    private val safeSpamSlots = arrayOf(
        Pair(279f, 1489f), // 좌상
        Pair(799f, 1489f), // 우상
        Pair(279f, 1759f), // 좌하
        Pair(799f, 1759f)  // 우하
    )

    private val bandTop = 1340
    private val bandBottom = 1920
    private val snapRadius = 320f
    private val NEXT_BTN = Pair(886f, 1930f)   // '다음 문제' 버튼 (Note10 결과화면 측정값)

    private val stepDelay = 1000L
    private val pollDelay = 1000L
    private val cardWaitPolls = 8      // 카드 대기 최대 횟수 (1초 × 8 ≈ 8초)
    private val maxLoops = 300         // 자동 풀이 최대 문제 수 (도달 시 자동 종료)
    private val tapDuration = 60L
    private val sttWaitPolls = 8       // 새 음성 인식 대기 최대 횟수 (1초 × 8 ≈ 8초)
    private val sttPollDelay = 1000L

    // 빈칸/카드 판별용 (카드가 커서 영역도 키움)
    private val occBrightThreshold = 170
    private val occMinBrightPixels = 4
    private val occHalfW = 150
    private val occHalfH = 70

    @Volatile private var solving = false
    private var retryAtSameStep = 0
    private var captureRetry = 0
    private var target: List<String> = emptyList()
    private var currentLoopCount = 0
    private var listenStartTime = 0L

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
                text = "🤖 자동 풀이 모드\n(볼륨[-] 시작 / 볼륨[+] 중단)"
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
                updateLog("🛑 중단됨")
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

    // 다음 문제로 진행하되, 최대 반복 수에 도달하면 자동 종료
    private fun nextOrStop() {
        if (!solving) return
        if (currentLoopCount >= maxLoops) {
            solving = false
            handler.removeCallbacksAndMessages(null)
            updateLog("✅ $maxLoops 문제 완료 — 자동 종료")
            Log.d("SOLVE", "최대 반복($maxLoops) 도달 → 종료")
            return
        }
        retryAtSameStep = 0
        captureRetry = 0
        updateLog("⏳ 새 문제 대기 중... ($currentLoopCount/$maxLoops)")
        waitForCards(0)
    }

    private fun waitForCards(attempt: Int) {
        if (!solving) return
        captureThen { result, _ ->
            val slotWord = extractSlotWords(result)
            if (slotWord.isNotEmpty()) {
                Log.d("SOLVE", "카드 감지(${slotWord.size}). 다시 듣기 후 새 음성 인식 대기")
                updateLog("🔊 다시 듣고 인식 중...")
                // 이전 문제 문장 재사용 방지: 지금 이후에 인식된 문장만 사용
                listenStartTime = System.currentTimeMillis()
                clickAt(LISTEN_BTN.first, LISTEN_BTN.second)
                waitForFreshStt(0)
            } else if (attempt < cardWaitPolls) {
                updateLog("⏳ 카드 대기...(${attempt + 1})")
                handler.postDelayed({ waitForCards(attempt + 1) }, pollDelay)
            } else {
                Log.e("SOLVE", "카드 안 뜸")
                triggerSpamFallback("❌ 카드 화면 진입 실패")
            }
        }
    }

    // 다시 듣기를 누른 시점 이후에 새로 인식된 문장이 들어올 때까지 대기
    private fun waitForFreshStt(attempt: Int) {
        if (!solving) return
        if (AudioCaptureService.lastUpdateTime > listenStartTime) {
            val raw = AudioCaptureService.lastSentence
            target = raw.lowercase().split(Regex("\\s+")).map { normalize(it) }.filter { it.isNotEmpty() }
            Log.d("SOLVE", "새 STT: \"$raw\" → $target")
            if (target.isEmpty()) { triggerSpamFallback("❌ STT(음성) 인식 실패"); return }
            updateLog("🤖 ${target.joinToString(" ")}")
            handler.postDelayed({ solveStep(0) }, stepDelay)
        } else if (attempt < sttWaitPolls) {
            handler.postDelayed({ waitForFreshStt(attempt + 1) }, sttPollDelay)
        } else {
            triggerSpamFallback("❌ 새 음성 인식 시간 초과")
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

                handler.postDelayed({ nextOrStop() }, 2000L)
            }, 500)
            return
        }

        captureThen { result, bmp ->
            if (!solving) return@captureThen
            val slotWord = extractSlotWords(result)
            val occ = IntArray(4) { brightCount(bmp, it) }
            val occupied = BooleanArray(4) { occ[it] >= occMinBrightPixels }
            val needed = target[p]
            Log.d("SOLVE", "[$p] 필요='$needed' 단어=${(0..3).associateWith { slotWord[it] }} 밝기=${occ.toList()} 카드=${occupied.toList()}")

            var slotToTap = (0..3).firstOrNull { si ->
                occupied[si] && slotWord[si]?.let { it == needed || lev(it, needed) <= 1 } == true
            } ?: -1

            if (slotToTap < 0) {
                val unrecOcc = (0..3).filter { occupied[it] && slotWord[it] == null }
                if (unrecOcc.size == 1) { slotToTap = unrecOcc[0]; Log.d("SOLVE", "[$p] 소거법 → S$slotToTap") }
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
        if (captureRetry > 6) { triggerSpamFallback("❌ 화면 캡처 반복 에러") }
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

    // 보조 모드: OCR/STT 실패 시 카드 좌표를 순서대로 빠르게 탭
    private fun triggerSpamFallback(reason: String) {
        if (!solving) return
        currentLoopCount++
        updateLog("$reason\n보조 방식으로 다시 시도 중...")

        var delayAccumulator = 0L

        for (i in 0 until 30) {
            val spot = safeSpamSlots[i % 4]
            handler.postDelayed({ if (solving) clickAt(spot.first, spot.second) }, delayAccumulator)
            delayAccumulator += 50L
        }

        handler.postDelayed({
            if (solving) {
                updateLog("⏭️ 보조 시도 완료 → 다음 문제로")
                clickAt(NEXT_BTN.first, NEXT_BTN.second)
            }
        }, delayAccumulator + 1000L)

        handler.postDelayed({ nextOrStop() }, delayAccumulator + 5000L)
    }

    // ===== 유틸 =====
    private fun extractSlotWords(result: Text): HashMap<Int, String> {
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

    private fun brightCount(bmp: Bitmap, slot: Int): Int {
        val cx = slots[slot].first.toInt(); val cy = slots[slot].second.toInt()
        val x0 = (cx - occHalfW).coerceAtLeast(0); val x1 = (cx + occHalfW).coerceAtMost(bmp.width - 1)
        val y0 = (cy - occHalfH).coerceAtLeast(0); val y1 = (cy + occHalfH).coerceAtMost(bmp.height - 1)
        var bright = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val px = bmp.getPixel(x, y)
                val r = (px shr 16) and 0xff; val g = (px shr 8) and 0xff; val bch = px and 0xff
                if (r * 0.299 + g * 0.587 + bch * 0.114 > occBrightThreshold) bright++
                x += 2
            }
            y += 2
        }
        return bright
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

    private fun updateLog(msg: String) { handler.post { try { overlayTextView?.text = msg } catch (_: Throwable) {} } }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager?.removeView(overlayTextView) } catch (_: Throwable) {}
    }

    override fun onInterrupt() { solving = false }
}
