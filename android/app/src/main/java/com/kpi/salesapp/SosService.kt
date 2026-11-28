package com.kpi.salesapp

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

// Phát chuông khẩn cấp qua luồng BÁO THỨC: vẫn kêu khi máy im lặng / rung, tự bật to hết cỡ
class SosService : Service() {

    private var player: MediaPlayer? = null
    private var am: AudioManager? = null
    private var oldVol = -1
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        val title = intent?.getStringExtra("title") ?: "🚨 KHẨN CẤP"
        val body = intent?.getStringExtra("body") ?: "Liên hệ admin ngay!"
        PushChannels.ensure(this)

        val flagsPi = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, SosService::class.java).setAction(ACTION_STOP), flagsPi)
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flagsPi)

        val n = NotificationCompat.Builder(this, PushChannels.SOS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(openPi, true)
            .setContentIntent(openPi)
            .addAction(0, "TẮT CHUÔNG", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)

        startAlarm()
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ stopSelf() }, 120_000L)   // tự tắt sau 2 phút
        return START_NOT_STICKY
    }

    private fun startAlarm() {
        try {
            am = getSystemService(AUDIO_SERVICE) as AudioManager
            try {
                if (oldVol < 0) oldVol = am!!.getStreamVolume(AudioManager.STREAM_ALARM)
                am!!.setStreamVolume(AudioManager.STREAM_ALARM, am!!.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: Exception) { }

            player?.release()
            var id = resources.getIdentifier("sos", "raw", packageName)
            if (id == 0) id = resources.getIdentifier("lio", "raw", packageName)
            if (id == 0) id = R.raw.sup_ton_cuoi

            val afd = resources.openRawResourceFd(id)
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { player?.stop() } catch (e: Exception) { }
        player?.release()
        player = null
        try { if (oldVol >= 0) am?.setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0) } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.kpi.salesapp.SOS_STOP"
        private const val NOTIF_ID = 3001
    }
}
