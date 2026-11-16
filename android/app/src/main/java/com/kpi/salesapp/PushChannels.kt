package com.kpi.salesapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build

object PushChannels {
    const val OPEN = "sup_open_v1"
    const val CLOSE = "sup_close_v1"

    fun soundUri(ctx: Context, raw: Int): Uri =
        Uri.parse("android.resource://${ctx.packageName}/$raw")

    // Tạo kênh thông báo (an toàn khi gọi nhiều lần). Âm thanh của kênh chỉ đặt được 1 lần,
    // muốn đổi âm thì đổi ID (vd. sup_open_v2).
    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val attr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        fun ch(id: String, name: String, raw: Int) {
            val c = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            c.setSound(soundUri(ctx, raw), attr)
            c.enableVibration(true)
            c.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(c)
        }
        ch(OPEN, "Báo cáo tồn đầu ca", R.raw.sup_ton_dau_ca)
        ch(CLOSE, "Báo cáo tồn cuối ca", R.raw.sup_ton_cuoi)
    }
}
