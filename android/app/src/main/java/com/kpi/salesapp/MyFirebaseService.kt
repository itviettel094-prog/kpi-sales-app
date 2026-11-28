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
import me.leolin.shortcutbadger.ShortcutBadger

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
        val raw = if (type == "anom") R.raw.lio else if (isClose) R.raw.sup_ton_cuoi else R.raw.sup_ton_dau_ca

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val intent = Intent(this, LoginActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, type.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sp = getSharedPreferences("session", MODE_PRIVATE)
        val unread = sp.getInt("unread", 0) + 1

        val oldLines = (sp.getString("notif_lines", "") ?: "")
            .split("\u0001").filter { it.isNotBlank() }.toMutableList()
        oldLines.add(body)
        while (oldLines.size > 5) oldLines.removeAt(0)

        sp.edit()
            .putInt("unread", unread)
            .putString("notif_lines", oldLines.joinToString("\u0001"))
            .apply()

        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle("$title ($unread tin nhắn)")
        for (line in oldLines) inbox.addLine(line)

        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)   // nên thay bằng icon riêng (trắng, nền trong suốt)
            .setContentTitle(title)
            .setContentText("($unread tin nhắn) " + oldLines.lastOrNull().orEmpty())
            .setStyle(inbox)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setNumber(unread)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
        ShortcutBadger.applyCount(this, unread)
        PushChannels.play(this, raw)
    }

    companion object {
        private const val NOTIF_ID = 2001
    }
}
