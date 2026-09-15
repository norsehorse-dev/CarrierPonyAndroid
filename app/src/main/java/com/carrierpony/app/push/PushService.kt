// PushService.kt
// CarrierPony Android
//
// The FCM receiving side, the Android counterpart of iOS Core/App/PushService
// (contentless APNs). Pushes are wake signals only: the relay sends a
// data-only FCM message with zero message content, and this service either
// nudges a live ChatStore to refresh (app in foreground) or posts one generic
// local notification (app in background). Message content never rides the
// push pipe — Google's servers see that *something* arrived, never what.
//
// Token changes re-register with the relay through the hook AppModel installs;
// tokens are meaningless without the relay's challenge auth, and the relay
// scopes them to (fingerprint, device), matching the APNs design.

package com.carrierpony.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "cp.messages"

        /** Installed by AppModel: called with a fresh token so it can be
         *  re-registered with the relay. No-op until the app wires it. */
        @Volatile
        var onNewToken: (String) -> Unit = {}

        /** Installed by AppModel: returns true if a live ChatStore handled the
         *  wake (app foregrounded and refreshing), in which case no
         *  notification is posted. */
        @Volatile
        var onWake: () -> Boolean = { false }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(com.carrierpony.app.R.string.push_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(com.carrierpony.app.R.string.push_channel_desc)
                }
            )
        }
    }

    override fun onNewToken(token: String) {
        android.util.Log.i("CarrierPony", "FCM token rotated (${token.take(12)}...)")
        onNewToken.invoke(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // A silent wake (relay sets data["silent"]="1") is a control message,
        // self-copy, or profile sync — not a real message. We still let a live
        // ChatStore refresh, but we never post a banner for it. This is the
        // Android half of the phantom-notification fix.
        val silent = message.data["silent"] == "1"
        android.util.Log.i("CarrierPony", "push wake received (data=${message.data.keys}, silent=$silent)")
        // Contentless by design: ignore any payload beyond the wake itself.
        val handledLive = try {
            onWake.invoke()
        } catch (e: Exception) {
            false
        }
        when {
            handledLive -> android.util.Log.i("CarrierPony", "wake handled live (foreground refresh)")
            silent -> android.util.Log.i("CarrierPony", "silent wake; banner suppressed")
            else -> {
                android.util.Log.i("CarrierPony", "posting generic notification")
                postGenericNotification()
            }
        }
    }

    private fun postGenericNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w("CarrierPony", "wake received but POST_NOTIFICATIONS not granted; banner skipped")
            return
        }
        ensureChannel(this)
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        val pending = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(getString(com.carrierpony.app.R.string.app_name))
            .setContentText(getString(com.carrierpony.app.R.string.push_body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(1, notification)
    }
}
