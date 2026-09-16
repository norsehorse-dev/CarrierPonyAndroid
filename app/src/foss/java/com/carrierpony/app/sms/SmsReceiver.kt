package com.carrierpony.app.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.carrierpony.app.AppConfig

/**
 * Manifest-registered receiver for our port-addressed data SMS, so segments are
 * captured even when the app is not running. Reassembles on disk and, once a message
 * is complete, delivers to a live store if one is running, otherwise queues the
 * envelope and posts a wake notification. It never decrypts here: decryption happens
 * when the app is next live, matching the relay's contentless-wake model.
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        // Set by AppModel while the store is live: returns true if it ingested the
        // envelope now, false to fall back to queue + notify. Defaults to false so a
        // receiver firing in a dead process queues instead of dropping.
        @Volatile var liveSink: (ByteArray) -> Boolean = { false }
        private const val CHANNEL_ID = "cp.messages"
        private const val NOTIF_ID = 2
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!AppConfig.smsEnabled(context)) return
        val msgs = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (e: Exception) { null } ?: return
        val store = SmsReassembly(context.applicationContext)
        var queued = false
        for (m in msgs) {
            val frame = m.userData ?: continue
            if (frame.size <= SmsWire.HEADER || (frame[0].toInt() and 0xFF) != SmsWire.MAGIC) continue
            val id = ((frame[1].toInt() and 0xFF) shl 24) or ((frame[2].toInt() and 0xFF) shl 16) or
                ((frame[3].toInt() and 0xFF) shl 8) or (frame[4].toInt() and 0xFF)
            val index = ((frame[5].toInt() and 0xFF) shl 8) or (frame[6].toInt() and 0xFF)
            val total = ((frame[7].toInt() and 0xFF) shl 8) or (frame[8].toInt() and 0xFF)
            if (total <= 0 || index < 0 || index >= total) continue
            val body = frame.copyOfRange(SmsWire.HEADER, frame.size)
            val envelope = store.offer(id, index, total, body) ?: continue
            val deliveredLive = try { liveSink(envelope) } catch (e: Exception) { false }
            if (!deliveredLive) { store.enqueue(envelope); queued = true }
        }
        if (queued) notifyWake(context)
    }

    private fun notifyWake(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(com.carrierpony.app.R.string.push_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(com.carrierpony.app.R.string.push_channel_desc)
                }
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(context.getString(com.carrierpony.app.R.string.app_name))
            .setContentText(context.getString(com.carrierpony.app.R.string.push_body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        mgr.notify(NOTIF_ID, n)
    }
}
