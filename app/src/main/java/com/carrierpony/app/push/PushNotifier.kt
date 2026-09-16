// PushNotifier.kt
// CarrierPony Android
//
// Flavor-independent push presentation: the notification channel and the one
// generic "something arrived" banner. Both build flavors share this — it holds
// no Firebase, no Google dependency, nothing proprietary. The FCM receiving
// side (play flavor's PushService) and the flavor PushSupport shims call into
// it, so the FOSS build gets identical local-notification behavior without any
// Google code on the classpath.
//
// Contentless by design: the banner never carries message text. A push is a
// wake signal; the app fetches over the encrypted transport once awake.

package com.carrierpony.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object PushNotifier {
    const val CHANNEL_ID = "cp.messages"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(com.carrierpony.app.R.string.push_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(com.carrierpony.app.R.string.push_channel_desc)
            }
        )
    }

    /** Post the single generic wake banner. Silently skips if POST_NOTIFICATIONS
     *  is not granted (Android 13+). */
    fun postGenericNotification(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("CarrierPony", "wake received but POST_NOTIFICATIONS not granted; banner skipped")
            return
        }
        ensureChannel(context)
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(context.getString(com.carrierpony.app.R.string.app_name))
            .setContentText(context.getString(com.carrierpony.app.R.string.push_body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(1, notification)
    }
}
