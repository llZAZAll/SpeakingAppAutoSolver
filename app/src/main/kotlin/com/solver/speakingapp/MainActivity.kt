package com.solver.speakingapp

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val bgColor = Color.parseColor("#121214")
    private val cardColor = Color.parseColor("#1E1E26")
    private val accent = Color.parseColor("#7C6FF0")
    private val textMain = Color.parseColor("#ECECEC")
    private val textSub = Color.parseColor("#9A9AA5")

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            Toast.makeText(this, "오디오 캡처를 시작했어요", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "캡처 권한이 거부되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(40))
        }
        scroll.addView(root)

        // ===== 헤더 =====
        root.addView(TextView(this).apply {
            text = "🎧 영어 듣기 자동 풀이"
            textSize = 26f
            setTextColor(textMain)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "듣기 문제를 음성 인식으로 받아 카드를 순서대로 눌러줍니다"
            textSize = 13f
            setTextColor(textSub)
            setPadding(0, dp(6), 0, dp(20))
        })

        // ===== 앱 설명 =====
        root.addView(sectionTitle("이 앱은?"))
        root.addView(card(
            "• 문제 오디오를 듣고, 화면의 단어 카드를 정답 순서대로 자동으로 눌러줍니다.\n" +
            "• 음성 인식(STT) + 화면 글자 인식(OCR)을 함께 사용합니다.\n" +
            "• 볼륨 버튼으로 시작/중단하며, 한 번 시작하면 다음 문제까지 이어서 풉니다."
        ))

        // ===== 준비 순서 =====
        root.addView(sectionTitle("준비 순서 (위에서부터 차례로)"))
        root.addView(stepButton("1️⃣  접근성 권한 켜기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(hint("목록에서 이 앱의 서비스를 켜주세요. (이미 켰다면 껐다 켜면 최신 설정 적용)"))

        root.addView(stepButton("2️⃣  다른 앱 위에 표시 권한 켜기") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "이미 허용되어 있습니다", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(hint("진행 상태를 화면 위에 표시하기 위해 필요합니다."))

        root.addView(stepButton("3️⃣  오디오 캡처 시작") { ensurePermsThenCapture() })
        root.addView(hint("마이크·알림 권한과 화면/오디오 캡처를 허용하면 음성 인식이 켜집니다."))

        root.addView(stopButton("■  오디오 캡처 종료") {
            startService(Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            })
            Toast.makeText(this, "오디오 캡처를 종료했어요", Toast.LENGTH_SHORT).show()
        })

        // ===== 사용법 =====
        root.addView(sectionTitle("사용법"))
        root.addView(card(
            "1. 위 1~3 준비를 마칩니다.\n" +
            "2. 영어 학습 앱으로 이동해 문제 화면을 엽니다.\n" +
            "3. 문제 오디오를 한 번 들려줍니다.\n" +
            "4. 볼륨[-] 버튼을 누르면 자동 풀이가 시작됩니다.\n" +
            "5. 멈추려면 볼륨[+] 버튼을 누릅니다.\n\n" +
            "한 번 시작하면 다음 문제까지 이어서 풀며, 더 이상 문제가 없으면(세트 종료 등) 자동으로 멈춥니다."
        ))

        root.addView(TextView(this).apply {
            text = "※ 본인 학습 진행용 도구입니다. 사용하는 학습 앱의 약관도 확인하세요."
            textSize = 11f
            setTextColor(textSub)
            setPadding(0, dp(16), 0, 0)
        })

        setContentView(scroll)
    }

    // ===== 권한 → 캡처 =====
    private fun ensurePermsThenCapture() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(android.Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 100) else launchProjection()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) launchProjection()
            else Toast.makeText(this, "마이크/알림 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    // ===== UI 헬퍼 =====
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(topMargin), 0, 0) }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTextColor(textMain)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = lp(24)
    }

    private fun card(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(textMain)
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(cardColor) }
        layoutParams = lp(10)
    }

    private fun hint(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(textSub)
        setPadding(dp(6), dp(4), dp(6), dp(2))
        layoutParams = lp(2)
    }

    private fun stepButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(accent) }
        layoutParams = lp(12)
        setOnClickListener { onClick() }
    }

    private fun stopButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(Color.parseColor("#FF6B6B"))
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(16), dp(20), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#241E1E"))
            setStroke(dp(1), Color.parseColor("#FF6B6B"))
        }
        layoutParams = lp(16)
        setOnClickListener { onClick() }
    }
}
