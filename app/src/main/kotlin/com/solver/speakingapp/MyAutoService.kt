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

class MyAutoService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayTextView: TextView? = null

    private val ocrClient by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

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
                text = "🔍 OCR 테스트 모드\n(볼륨[-] 화면 단어 읽기)"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.parseColor("#EE000000"))
                setPadding(30, 30, 30, 30)
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
            ).apply { gravity = Gravity.TOP; y = 120 }
            windowManager?.addView(overlayTextView, params)
        } catch (t: Throwable) {
            Log.e("OCR", "onServiceConnected 예외", t)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        try {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                captureAndOcr()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
                updateLog("🛑 정지")
                return true
            }
        } catch (t: Throwable) {
            Log.e("OCR", "onKeyEvent 예외(앱 종료 막음)", t)
            updateLog("❌ 예외: ${t.javaClass.simpleName}")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun captureAndOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e("OCR", "takeScreenshot은 Android 11+ 필요")
            updateLog("❌ Android 11+ 필요")
            return
        }
        updateLog("📸 화면 캡처 중...")
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                applicationContext.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            hb.close()
                            if (bmp == null) {
                                Log.e("OCR", "비트맵 변환 실패(null)")
                                updateLog("❌ 비트맵 null")
                                return
                            }
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            try { bmp.recycle() } catch (_: Throwable) {}
                            if (sw == null) {
                                Log.e("OCR", "소프트웨어 복사 실패(null)")
                                return
                            }
                            runOcr(sw)
                        } catch (t: Throwable) {
                            Log.e("OCR", "캡처 처리 예외", t)
                            updateLog("❌ 캡처처리: ${t.javaClass.simpleName}")
                        }
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.e("OCR", "스크린샷 실패 code=$errorCode (1초에 1회만 가능)")
                        updateLog("❌ 스크린샷 실패 code=$errorCode")
                    }
                }
            )
        } catch (t: Throwable) {
            Log.e("OCR", "takeScreenshot 호출 예외", t)
            updateLog("❌ 스크린샷 호출: ${t.javaClass.simpleName}")
        }
    }

    private fun runOcr(bmp: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bmp, 0)
            ocrClient.process(image)
                .addOnSuccessListener { result ->
                    Log.d("OCR", "===== OCR 결과 (화면 ${bmp.width}x${bmp.height}) =====")
                    var count = 0
                    for (block in result.textBlocks) for (line in block.lines) for (el in line.elements) {
                        val b = el.boundingBox
                        Log.d("OCR", "word='${el.text}' center=(${b?.centerX()},${b?.centerY()}) box=$b")
                        count++
                    }
                    Log.d("OCR", "===== 총 단어 $count 개 =====")
                    updateLog("✅ OCR 완료: $count 단어 (logcat 확인)")
                }
                .addOnFailureListener { e ->
                    Log.e("OCR", "OCR 처리 실패", e)
                    updateLog("❌ OCR 실패: ${e.message}")
                }
        } catch (t: Throwable) {
            Log.e("OCR", "runOcr 예외", t)
            updateLog("❌ OCR 예외: ${t.javaClass.simpleName}")
        }
    }

    private fun clickAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 25)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun updateLog(message: String) {
        handler.post { try { overlayTextView?.text = message } catch (_: Throwable) {} }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { windowManager?.removeView(overlayTextView) } catch (_: Throwable) {}
    }

    override fun onInterrupt() {}
}
