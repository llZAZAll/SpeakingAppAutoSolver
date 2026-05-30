package com.solver.speakingapp

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 화면을 안드로이드 스튜디오 없이 코드로 직접 구성합니다.
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val titleView = TextView(this).apply {
            text = "📚 영어 회화 매크로 봇"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }

        val statusView = TextView(this).apply {
            text = "상태: 앱 실행 중 (접근성 권한을 켜주세요)"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 100)
        }

        val button = Button(this).apply {
            text = "접근성 설정 바로가기"
            setOnClickListener {
                // 클릭 시 스마트폰 접근성 설정 화면으로 바로 이동시켜줍니다.
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(titleView)
        layout.addView(statusView)
        layout.addView(button)
        setContentView(layout)
    }
}
