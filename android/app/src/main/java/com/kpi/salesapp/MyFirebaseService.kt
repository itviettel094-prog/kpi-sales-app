package com.kpi.salesapp

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// Nhận thông báo đẩy FCM kể cả khi app đã đóng / tắt màn hình.
// Server gửi tin "data-only" nên hàm onMessageReceived luôn chạy và tự hiện thông báo kèm âm thanh riêng.
class MyFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Token mới sẽ được gửi lên server ở lần mở app kế tiếp (MainActivity.registerPushToken)
        getSharedPreferences("session", MODE_PRIVATE).edit().putString("fcmToken", token).apply()
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val type = d["type"] ?: "open"
        val title = d["title"] ?: msg.notification?.title ?: "KPI Bán Hàng"
        val body = d["body"] ?: msg.notification?.body ?: ""

        PushChannels.ensure(this)
        val isClose = type == "close"
        val channel = if (isClose) PushChannels.CLOSE else PushChannels.OPEN
        val raw = if (isClose) R.raw.sup_ton_cuoi else R.raw.sup_ton_dau_ca

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, type.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)   // nên thay bằng icon riêng (trắng, nền trong suốt)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(PushChannels.soundUri(this, raw))            // Android < 8 (>= 8 dùng âm của kênh)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}
