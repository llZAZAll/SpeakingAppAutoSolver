package com.solver.speakingapp

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 화면/오디오 캡처 권한 요청 결과를 받아 캡처 서비스를 시작
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            Toast.makeText(this, "오디오 캡처 시작됨 (logcat의 STT 태그 확인)", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "캡처 권한이 거부되었습니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val titleView = TextView(this).apply {
            text = "📚 영어 매크로 봇 — 1단계(STT) 테스트"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        val statusView = TextView(this).apply {
            text = "순서대로 누르세요.\n[1] 접근성 켜기 → [2] 오버레이 허용 → [3] 오디오 캡처 시작\n\n그 다음 영어앱으로 가서 문제 오디오를 재생하면, logcat의 'STT' 태그에 인식된 문장이 찍힙니다."
            textSize = 14f
            setPadding(0, 0, 0, 40)
        }

        val btnAccessibility = Button(this).apply {
            text = "[1] 접근성 설정 바로가기"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val btnOverlay = Button(this).apply {
            text = "[2] 다른 앱 위에 그리기 권한 허용"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                } else {
                    Toast.makeText(this@MainActivity, "이미 허용되어 있습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnCapture = Button(this).apply {
            text = "[3] 오디오 캡처 시작 (STT 테스트)"
            setOnClickListener { ensurePermsThenCapture() }
        }

        val btnStop = Button(this).apply {
            text = "[정지] 오디오 캡처 종료"
            setOnClickListener {
                startService(Intent(this@MainActivity, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_STOP
                })
            }
        }

        layout.addView(titleView)
        layout.addView(statusView)
        layout.addView(btnAccessibility)
        layout.addView(btnOverlay)
        layout.addView(btnCapture)
        layout.addView(btnStop)
        setContentView(layout)
    }

    private fun ensurePermsThenCapture() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(android.Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(android.Manifest.permission.POST_NOTIFICATIONS)

        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), 100)
        } else {
            launchProjection()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                launchProjection()
            } else {
                Toast.makeText(this, "마이크/알림 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
