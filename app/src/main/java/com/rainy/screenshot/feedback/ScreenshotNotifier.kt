package com.rainy.screenshot.feedback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rainy.screenshot.R
import com.rainy.screenshot.capture.Screenshot

object ScreenshotNotifier {

    // Channel importance is fixed once a channel exists, so the ID carries the priority.
    private const val CHANNEL_ID = "screenshot_alert_high"

    private const val PREVIEW_MAX_EDGE = 1280

    fun show(context: Context, shot: Screenshot) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shot.uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            shot.uri.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_screenshot)
            .setContentTitle(context.getString(R.string.screenshot_notif_title))
            .setContentText(context.getString(R.string.screenshot_notif_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        decodePreview(shot.png)?.let {
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(it))
        }
        NotificationManagerCompat.from(context).notify(shot.uri.hashCode(), builder.build())
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.screenshot_notif_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun decodePreview(png: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(
            png,
            0,
            png.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
