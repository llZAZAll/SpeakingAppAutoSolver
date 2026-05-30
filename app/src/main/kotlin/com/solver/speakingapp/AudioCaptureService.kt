package com.solver.speakingapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CHANNEL_ID = "audio_capture"
        const val ACTION_RESULT = "com.solver.speakingapp.STT_RESULT"
        const val EXTRA_TEXT = "text"

        // 인식된 최신 문장. 나중에 접근성 서비스가 여기서 읽어감.
        @Volatile
        var lastSentence: String = ""
    }

    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    @Volatile private var capturing = false
    private val sampleRate = 16000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Vosk 모델 비동기 언팩 (assets/vosk-model-en -> filesDir/model)
        StorageService.unpack(
            this, "vosk-model-en", "model",
            { m -> model = m; Log.d("STT", "✅ Vosk 모델 로드 완료") },
            { e -> Log.e("STT", "❌ 모델 로드 실패: ${e.message}") }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture(); stopSelf(); return START_NOT_STICKY
        }

        // Android 10+ : getMediaProjection 전에 mediaProjection 타입 포그라운드여야 함
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, buildNotification())
        }

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code == Activity.RESULT_OK && data != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture() }
            }, null)
            startCapture()
        } else {
            Log.e("STT", "❌ MediaProjection 권한이 없습니다")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startCapture() {
        val mp = projection ?: return

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        record = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.e("STT", "❌ AudioRecord 생성 실패: ${e.message}")
            null
        } ?: return

        record?.startRecording()
        capturing = true
        Log.d("STT", "🎧 오디오 캡처 시작 (영어앱 오디오를 재생해 보세요)")

        thread(name = "stt-loop") {
            var waited = 0
            while (model == null && waited < 8000) { Thread.sleep(100); waited += 100 }
            val m = model
            if (m == null) { Log.e("STT", "❌ 모델이 준비되지 않아 중단"); return@thread }

            val rec = Recognizer(m, sampleRate.toFloat())
            recognizer = rec
            val buffer = ByteArray(minBuf)
            var silentReads = 0

            while (capturing) {
                val n = record?.read(buffer, 0, buffer.size) ?: 0
                if (n > 0) {
                    // 무음(0) 데이터가 계속 오면 = 앱이 캡처를 막았을 가능성
                    if (buffer.take(n).all { it.toInt() == 0 }) {
                        silentReads++
                        if (silentReads == 100) Log.w("STT", "⚠️ 계속 무음입니다. 이 앱이 오디오 캡처를 차단했을 수 있어요.")
                    } else {
                        silentReads = 0
                    }
                    if (rec.acceptWaveForm(buffer, n)) {
                        val text = JSONObject(rec.result).optString("text").trim()
                        if (text.isNotEmpty()) emit(text)
                    } else {
                        val p = JSONObject(rec.partialResult).optString("partial").trim()
                        if (p.isNotEmpty()) Log.v("STT", "… $p")
                    }
                }
            }
            val finalText = JSONObject(rec.finalResult).optString("text").trim()
            if (finalText.isNotEmpty()) emit(finalText)
        }
    }

    private fun emit(text: String) {
        lastSentence = text
        Log.d("STT", "🗣️ 인식 문장: \"$text\"")
        sendBroadcast(Intent(ACTION_RESULT).putExtra(EXTRA_TEXT, text).setPackage(packageName))
    }

    private fun stopCapture() {
        capturing = false
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        record = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        Log.d("STT", "⏹️ 캡처 종료")
    }

    override fun onDestroy() {
        stopCapture()
        try { model?.close() } catch (_: Exception) {}
        model = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "오디오 캡처", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return b.setContentTitle("오디오 캡처 중")
            .setContentText("STT 동작 중")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }
}
