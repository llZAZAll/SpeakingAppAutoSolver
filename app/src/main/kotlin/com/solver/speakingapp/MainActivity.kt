package com.solver.speakingapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(50, 50, 50, 50)
        }

        val titleView = TextView(this).apply {
            text = "📚 영어 회화 매크로 봇 (디버그 모드)"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 50)
        }

        val statusView = TextView(this).apply {
            text = "⚠️ 아래 두 권한을 모두 허용해야 정상 작동합니다.\n\n1. 접근성 설정 켜기\n2. 다른 앱 위에 그리기 허용"
            textSize = 14f
            setPadding(0, 0, 0, 50)
        }

        val btnAccessibility = Button(this).apply {
            text = "[1] 접근성 설정 바로가기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val btnOverlay = Button(this).apply {
            text = "[2] 다른 앱 위에 그리기 권한 허용"
            setOnClickListener {
                // 💡 수정된 부분: VERSION_CODES.M 으로 마침표를 찍었습니다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } else {
                        android.widget.Toast.makeText(this@MainActivity, "이미 오버레이 권한이 허용되어 있습니다.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        layout.addView(titleView)
        layout.addView(statusView)
        layout.addView(btnAccessibility)
        layout.addView(btnOverlay)
        setContentView(layout)
    }
}
